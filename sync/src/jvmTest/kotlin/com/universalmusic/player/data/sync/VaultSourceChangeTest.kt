package com.universalmusic.player.data.sync

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Serves the routes the hub serves from a real store, with [afterChunk] firing after each blob call. */
private class HubStoreClient(
    private val hub: HomeLanVaultStore,
    private val reportsHash: Boolean = true,
    private val afterChunk: suspend (Int) -> Unit = {},
) : HomeLanSyncClient by FakeVaultClient(ByteArray(0)) {
    val uploadedOffsets = mutableListOf<Long>()
    private var chunks = 0

    override suspend fun fetchRemoteVaultIndex(local: VaultIndexDocument) = Result.success(hub.buildIndex("hub"))

    override suspend fun fetchBlobHash(relPath: String): Result<String?> =
        Result.success(if (reportsHash) hub.contentSha256(relPath) else null)

    override suspend fun downloadBlob(relPath: String, offset: Long, length: Int): Result<ByteArray> =
        Result.success(hub.readRange(relPath, offset, offset + length - 1)).also { afterChunk(++chunks) }

    override suspend fun uploadBlob(relPath: String, offset: Long, totalSize: Long, chunk: ByteArray): Result<Unit> {
        hub.writeRange(relPath, offset, chunk, totalSize)
        uploadedOffsets += offset
        afterChunk(++chunks)
        return Result.success(Unit)
    }
}

class VaultSourceChangeTest {
    private val chunk = VAULT_BLOB_CHUNK_BYTES
    private val path = "song.flac"
    private val noProgress: suspend (String, Long, Long) -> Unit = { _, _, _ -> }

    private fun fill(size: Int, value: Int) = ByteArray(size) { value.toByte() }

    private fun upload(size: Int) = PendingVaultTransfer(path, VaultCopyDirection.TO_REMOTE, size.toLong())

    private fun download(size: Int) = PendingVaultTransfer(path, VaultCopyDirection.TO_LOCAL, size.toLong())

    /** A rewrite no index can see: same size, same mtime and the same final 64 KiB. */
    private fun rewriteInvisibly(file: Path, offset: Int, length: Int) {
        val mtime = Files.getLastModifiedTime(file)
        val bytes = Files.readAllBytes(file)
        bytes.fill(0x7B, offset, offset + length)
        Files.write(file, bytes)
        Files.setLastModifiedTime(file, mtime)
    }

    @Test
    fun uploadRewrittenAtSameSizeAfterFirstChunkFailsAndKeepsTheHubFile() = runTest {
        val (sourceRoot, source) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        val size = chunk * 2
        Files.write(hubRoot.resolve(path), fill(size, 9))
        Files.write(sourceRoot.resolve(path), fill(size, 1))
        val client = HubStoreClient(hub) { Files.write(sourceRoot.resolve(path), fill(size, 2)) }

        val failure = assertFailsWith<IllegalStateException> {
            executeVaultTransfer(source, client, upload(size), onProgress = noProgress)
        }

        assertContains(failure.message.orEmpty(), "changed")
        assertEquals(listOf(0L), client.uploadedOffsets, "the publishing chunk is never sent")
        assertContentEquals(fill(size, 9), Files.readAllBytes(hubRoot.resolve(path)))
    }

    @Test
    fun uploadRetryAfterFailureRestartsAndPublishesOnlyTheNewContent() = runTest {
        val (sourceRoot, source) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        val size = chunk * 3
        Files.write(hubRoot.resolve(path), fill(size, 9))
        Files.write(sourceRoot.resolve(path), fill(size, 1))
        val failing = HubStoreClient(hub) { Files.write(sourceRoot.resolve(path), fill(size, 2)) }
        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(source, failing, upload(size), onProgress = noProgress)
        }
        assertEquals(chunk * 2L, hub.stagedSize(path), "version 1 stays staged on the hub")

        val retry = HubStoreClient(hub)
        executeVaultTransfer(source, retry, upload(size), onProgress = noProgress)

        assertEquals(listOf(0L, chunk.toLong(), chunk * 2L), retry.uploadedOffsets)
        assertContentEquals(fill(size, 2), Files.readAllBytes(hubRoot.resolve(path)))
        assertEquals(null, hub.stagedSize(path))
    }

    @Test
    fun uploadGrownDuringTransferFailsInsteadOfPublishingAPrefix() = runTest {
        val (sourceRoot, source) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        val size = chunk * 2
        Files.write(sourceRoot.resolve(path), fill(size, 1))
        val client = HubStoreClient(hub) { Files.write(sourceRoot.resolve(path), fill(size + 10, 1)) }

        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(source, client, upload(size), onProgress = noProgress)
        }

        assertFalse(Files.exists(hubRoot.resolve(path)))
    }

    @Test
    fun uploadShrunkDuringTransferFailsWithAChangedMessage() = runTest {
        val (sourceRoot, source) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        val size = chunk * 2
        Files.write(sourceRoot.resolve(path), fill(size, 1))
        val client = HubStoreClient(hub) { Files.write(sourceRoot.resolve(path), fill(chunk + 5, 1)) }

        val failure = assertFailsWith<IllegalStateException> {
            executeVaultTransfer(source, client, upload(size), onProgress = noProgress)
        }

        assertContains(failure.message.orEmpty(), "changed")
        assertFalse(Files.exists(hubRoot.resolve(path)))
    }

    @Test
    fun singleChunkUploadChangedAfterItsReadNeverPublishes() = runTest {
        val (sourceRoot, source) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        Files.write(sourceRoot.resolve(path), fill(100, 1))
        var reads = 0
        val changing = object : HomeLanVaultStore by source {
            override suspend fun readRange(relPath: String, start: Long, endInclusive: Long): ByteArray =
                source.readRange(relPath, start, endInclusive).also {
                    if (++reads == 1) Files.write(sourceRoot.resolve(path), fill(100, 2))
                }
        }

        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(changing, HubStoreClient(hub), upload(100), onProgress = noProgress)
        }

        assertFalse(Files.exists(hubRoot.resolve(path)))
    }

    @Test
    fun emptyUploadOfAFileThatGainedBytesDoesNotBlankTheHubCopy() = runTest {
        val (sourceRoot, source) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        Files.write(hubRoot.resolve(path), fill(10, 9))
        Files.write(sourceRoot.resolve(path), fill(10, 1))

        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(source, HubStoreClient(hub), upload(0), onProgress = noProgress)
        }

        assertContentEquals(fill(10, 9), Files.readAllBytes(hubRoot.resolve(path)))
    }

    @Test
    fun unchangedUploadPublishesEveryByteOnce() = runTest {
        val (sourceRoot, source) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        val size = chunk * 2 + 3
        val bytes = ByteArray(size) { (it % 251).toByte() }
        Files.write(sourceRoot.resolve(path), bytes)
        val client = HubStoreClient(hub)

        executeVaultTransfer(source, client, upload(size), onProgress = noProgress)

        assertEquals(listOf(0L, chunk.toLong(), chunk * 2L), client.uploadedOffsets)
        assertContentEquals(bytes, Files.readAllBytes(hubRoot.resolve(path)))
    }

    @Test
    fun downloadRewrittenInvisiblyAfterFirstChunkFailsAndKeepsTheLocalFile() = runTest {
        val (localRoot, local) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        val size = chunk * 3
        Files.write(localRoot.resolve(path), fill(size, 9))
        Files.write(hubRoot.resolve(path), fill(size, 1))
        Files.setLastModifiedTime(hubRoot.resolve(path), FileTime.fromMillis(1_000_000L))
        val client = HubStoreClient(hub) { if (it == 1) rewriteInvisibly(hubRoot.resolve(path), chunk, 1024) }

        val failure = assertFailsWith<IllegalStateException> {
            executeVaultTransfer(local, client, download(size), onProgress = noProgress)
        }

        assertContains(failure.message.orEmpty(), "changed")
        assertContentEquals(fill(size, 9), Files.readAllBytes(localRoot.resolve(path)))
    }

    @Test
    fun downloadRetryAfterInvisibleRewritePublishesTheNewContent() = runTest {
        val (localRoot, local) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        val size = chunk * 2 + 3
        Files.write(hubRoot.resolve(path), fill(size, 1))
        val failing = HubStoreClient(hub) { if (it == 1) rewriteInvisibly(hubRoot.resolve(path), chunk, 1024) }
        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(local, failing, download(size), onProgress = noProgress)
        }

        executeVaultTransfer(local, HubStoreClient(hub), download(size), onProgress = noProgress)

        assertContentEquals(Files.readAllBytes(hubRoot.resolve(path)), Files.readAllBytes(localRoot.resolve(path)))
        assertEquals(0x7B, Files.readAllBytes(localRoot.resolve(path))[chunk].toInt())
        assertFalse(Files.exists(localRoot.resolve("$path$VAULT_STAGING_SUFFIX")))
    }

    @Test
    fun downloadFromAnOldHubStillFailsWhenTheIndexShowsTheRewrite() = runTest {
        val (localRoot, local) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        val size = chunk * 2
        Files.write(hubRoot.resolve(path), fill(size, 1))
        Files.setLastModifiedTime(hubRoot.resolve(path), FileTime.fromMillis(1_000_000L))
        val client = HubStoreClient(hub, reportsHash = false) {
            if (it == 1) Files.write(hubRoot.resolve(path), fill(size, 2))
        }

        val failure = assertFailsWith<IllegalStateException> {
            executeVaultTransfer(local, client, download(size), onProgress = noProgress)
        }

        assertContains(failure.message.orEmpty(), "changed")
        assertFalse(Files.exists(localRoot.resolve(path)))
    }

    @Test
    fun downloadGrownDuringTransferFails() = runTest {
        val (localRoot, local) = tempVaultStore()
        val (hubRoot, hub) = tempVaultStore()
        val size = chunk * 2
        Files.write(hubRoot.resolve(path), fill(size, 1))
        val client = HubStoreClient(hub) { if (it == 1) Files.write(hubRoot.resolve(path), fill(size + 10, 1)) }

        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(local, client, download(size), onProgress = noProgress)
        }

        assertFalse(Files.exists(localRoot.resolve(path)))
        assertTrue(local.buildIndex("pc").entries.isEmpty())
    }
}
