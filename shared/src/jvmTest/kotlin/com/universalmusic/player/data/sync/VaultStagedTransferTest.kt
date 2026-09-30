package com.universalmusic.player.data.sync

import com.universalmusic.player.data.playlist.PlaylistsSyncDocument
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Serves [remote] bytes; [failAfterChunks] / [shortChunks] / [emptyChunks] inject faults. */
internal class FakeVaultClient(
    private val remote: ByteArray,
    private val remoteIndex: VaultIndexDocument = VaultIndexDocument("hub"),
    private val failAfterChunks: Int = Int.MAX_VALUE,
    private val shortChunks: Boolean = false,
    private val emptyChunks: Boolean = false,
) : HomeLanSyncClient {
    var downloads = 0
    var closed = false
    val uploads = mutableListOf<Triple<Long, Long, Int>>()

    override fun close() {
        closed = true
    }

    override suspend fun syncHearts(): Result<HeartsSyncDocument> =
        Result.success(HeartsSyncDocument(deviceId = "hub", spotifyAccountId = null))

    override suspend fun syncPlaylists(): Result<PlaylistsSyncDocument> =
        Result.success(PlaylistsSyncDocument(deviceId = "hub"))

    override suspend fun probeHub(): Result<HomeLanHealthResponse> =
        Result.success(HomeLanHealthResponse(deviceId = "hub"))

    override suspend fun fetchRemoteVaultIndex(local: VaultIndexDocument): Result<VaultIndexDocument> =
        Result.success(remoteIndex)

    override suspend fun downloadBlob(relPath: String, offset: Long, length: Int): Result<ByteArray> {
        if (downloads >= failAfterChunks) return Result.failure(IllegalStateException("network dropped"))
        downloads += 1
        if (emptyChunks) return Result.success(ByteArray(0))
        val end = minOf(offset + length, remote.size.toLong()).toInt()
        val slice = remote.copyOfRange(offset.toInt(), end)
        return Result.success(if (shortChunks) slice.copyOf(slice.size / 2) else slice)
    }

    override suspend fun uploadBlob(
        relPath: String,
        offset: Long,
        totalSize: Long,
        chunk: ByteArray,
    ): Result<Unit> {
        uploads += Triple(offset, totalSize, chunk.size)
        return Result.success(Unit)
    }
}

internal fun tempVaultStore(): Pair<Path, JvmHomeLanVaultStore> {
    val dir = Files.createTempDirectory("kainos-vault-test")
    val root = dir.resolve("vault").also { Files.createDirectories(it) }
    return root to JvmHomeLanVaultStore(
        vaultRootProvider = { root.toString() },
        statePath = dir.resolve("vault-sync-state.json"),
    )
}

class VaultStagedTransferTest {
    private val size = VAULT_BLOB_CHUNK_BYTES * 2 + 1234
    private val noProgress: suspend (String, Long, Long) -> Unit = { _, _, _ -> }

    @Test
    fun interruptedReplaceKeepsExistingFileIntact() = runTest {
        val (root, store) = tempVaultStore()
        val original = Random(1).nextBytes(size)
        Files.write(root.resolve("a.flac"), original)
        val client = FakeVaultClient(Random(2).nextBytes(size), failAfterChunks = 1)

        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(
                store,
                client,
                PendingVaultTransfer("a.flac", VaultCopyDirection.TO_LOCAL, size.toLong()),
                replaceExisting = true,
                onProgress = noProgress,
            )
        }

        assertContentEquals(original, Files.readAllBytes(root.resolve("a.flac")))
        val index = store.buildIndex("pc")
        assertEquals(listOf("a.flac"), index.entries.map { it.relPath })
        assertEquals(VAULT_BLOB_CHUNK_BYTES.toLong(), store.stagedSize("a.flac"))
    }

    @Test
    fun interruptedNewDownloadLeavesNoZeroPaddedFinalFile() = runTest {
        val (root, store) = tempVaultStore()
        val client = FakeVaultClient(Random(3).nextBytes(size), failAfterChunks = 1)

        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(
                store,
                client,
                PendingVaultTransfer("Album/b.flac", VaultCopyDirection.TO_LOCAL, size.toLong()),
                onProgress = noProgress,
            )
        }

        assertFalse(Files.exists(root.resolve("Album/b.flac")))
        assertNull(store.localSize("Album/b.flac"))
        assertTrue(store.buildIndex("pc").entries.isEmpty(), "staging file must not be indexed")
        // Staged length is exactly the bytes received (no setLength pre-extension).
        assertEquals(VAULT_BLOB_CHUNK_BYTES.toLong(), Files.size(root.resolve("Album/b.flac.kainos-part")))
    }

    @Test
    fun completedStagedTransferPublishesAndResumes() = runTest {
        val (root, store) = tempVaultStore()
        val remote = Random(4).nextBytes(size)
        val transfer = PendingVaultTransfer("c.flac", VaultCopyDirection.TO_LOCAL, size.toLong())
        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(store, FakeVaultClient(remote, failAfterChunks = 1), transfer, onProgress = noProgress)
        }

        val resumed = FakeVaultClient(remote)
        executeVaultTransfer(store, resumed, transfer, onProgress = noProgress)

        assertEquals(2, resumed.downloads, "resume should continue from the staged offset")
        assertContentEquals(remote, Files.readAllBytes(root.resolve("c.flac")))
        assertNull(store.stagedSize("c.flac"))
        assertFalse(Files.exists(root.resolve("c.flac.kainos-part")))
    }

    @Test
    fun shortChunkFailsWithoutPublishing() = runTest {
        val (root, store) = tempVaultStore()
        val original = Random(5).nextBytes(size)
        Files.write(root.resolve("d.flac"), original)

        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(
                store,
                FakeVaultClient(Random(6).nextBytes(size), shortChunks = true),
                PendingVaultTransfer("d.flac", VaultCopyDirection.TO_LOCAL, size.toLong()),
                replaceExisting = true,
                onProgress = noProgress,
            )
        }
        assertContentEquals(original, Files.readAllBytes(root.resolve("d.flac")))
    }

    @Test
    fun nonContiguousChunkIsRejected() = runTest {
        val (root, store) = tempVaultStore()
        assertFailsWith<IllegalArgumentException> {
            store.writeRange("e.flac", VAULT_BLOB_CHUNK_BYTES.toLong(), ByteArray(10), size.toLong())
        }
        assertFailsWith<IllegalArgumentException> {
            store.writeRange("e.flac", 0L, ByteArray(10), 5L)
        }
        assertFalse(Files.exists(root.resolve("e.flac")))
    }

    @Test
    fun readRangeIsBoundedToOneChunk() = runTest {
        val (root, store) = tempVaultStore()
        Files.write(root.resolve("f.flac"), ByteArray(size))
        assertEquals(VAULT_BLOB_CHUNK_BYTES, store.readRange("f.flac", 0L, Long.MAX_VALUE).size)
        assertEquals(0, store.readRange("f.flac", -1L, 10L).size)
        assertEquals(0, store.readRange("f.flac", 10L, 5L).size)
    }
}
