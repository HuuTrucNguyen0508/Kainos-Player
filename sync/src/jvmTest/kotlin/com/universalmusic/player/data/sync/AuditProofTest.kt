package com.universalmusic.player.data.sync

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AuditProofTest {
    @Test fun resumeMixesDifferentRevisions() = runTest {
        val (root, store) = tempVaultStore()
        val size = VAULT_BLOB_CHUNK_BYTES + 20
        val transfer = PendingVaultTransfer("song.flac", VaultCopyDirection.TO_LOCAL, size.toLong())
        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(store, FakeVaultClient(ByteArray(size) { 1 }, failAfterChunks = 1), transfer) { _, _, _ -> }
        }
        executeVaultTransfer(store, FakeVaultClient(ByteArray(size) { 2 }, VaultIndexDocument("hub", listOf(VaultFileEntry("song.flac", size.toLong(), 999)))), transfer.copy(mtimeMs = 999)) { _, _, _ -> }
        assertContentEquals(ByteArray(size) { 2 }, Files.readAllBytes(root.resolve("song.flac")))
    }

    @Test fun symlinkEscapesVault() = runTest {
        val (root, store) = tempVaultStore()
        val outside = Files.createTempDirectory("audit-outside")
        try {
            Files.write(outside.resolve("private.flac"), "private".toByteArray())
            Files.createSymbolicLink(root.resolve("link"), outside)
            assertTrue(store.readRange("link/private.flac", 0, 6).isEmpty())
            assertFails { store.writeRange("link/overwritten.flac", 0, "changed".toByteArray(), 7) }
            assertFalse(Files.exists(outside.resolve("overwritten.flac")))
            assertTrue(store.buildIndex("test").entries.isEmpty())
        } finally {
            outside.toFile().deleteRecursively()
            root.parent.toFile().deleteRecursively()
        }
    }
    @Test fun hashMismatchNeverPublishes() = runTest {
        val (root, store) = tempVaultStore()
        Files.write(root.resolve("song.flac"), byteArrayOf(9))
        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(store, FakeVaultClient(byteArrayOf(1, 2)),
                PendingVaultTransfer("song.flac", VaultCopyDirection.TO_LOCAL, 2, contentHash = "bad-hash")) { _, _, _ -> }
        }
        assertContentEquals(byteArrayOf(9), Files.readAllBytes(root.resolve("song.flac")))
    }

    @Test fun sourceChangingDuringDownloadNeverPublishes() = runTest {
        val (root, store) = tempVaultStore()
        val entry = VaultFileEntry("song.flac", 2, 123)
        var checks = 0
        val client = object : HomeLanSyncClient by FakeVaultClient(byteArrayOf(1, 2)) {
            override suspend fun fetchRemoteVaultIndex(local: VaultIndexDocument): Result<VaultIndexDocument> =
                Result.success(VaultIndexDocument("hub", listOf(entry.copy(mtimeMs = if (++checks > 2) 124 else 123))))
        }
        assertFailsWith<IllegalStateException> {
            executeVaultTransfer(store, client,
                PendingVaultTransfer("song.flac", VaultCopyDirection.TO_LOCAL, 2, mtimeMs = 123)) { _, _, _ -> }
        }
        assertFalse(Files.exists(root.resolve("song.flac")))
    }

    @Test fun rejectsSymlinkStagingAndNonAudioFiles() = runTest {
        val (root, store) = tempVaultStore()
        val outside = Files.createTempFile("audit-outside", ".flac")
        try {
            Files.write(outside, byteArrayOf(9))
            Files.createSymbolicLink(root.resolve("song.flac.kainos-part"), outside)
            assertFails { store.writeRange("song.flac", 0, byteArrayOf(1), 1) }
            assertContentEquals(byteArrayOf(9), Files.readAllBytes(outside))
            Files.write(root.resolve("private.txt"), byteArrayOf(9))
            assertTrue(store.readRange("private.txt", 0, 0).isEmpty())
            assertFails { store.writeRange("private.txt", 0, byteArrayOf(1), 1) }
            assertTrue(store.writeRange("Album/valid.flac", 0, byteArrayOf(1), 1))
            assertEquals(listOf("Album/valid.flac"), store.buildIndex("test").entries.map { it.relPath })
        } finally { Files.deleteIfExists(outside) }
    }
    @Test fun legacyPeerWithoutHashStillTransfersAndChecksSuppliedHash() = runTest {
        val (root, store) = tempVaultStore()
        val bytes = byteArrayOf(1, 2, 3)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        executeVaultTransfer(store, FakeVaultClient(bytes),
            PendingVaultTransfer("song.flac", VaultCopyDirection.TO_LOCAL, 3, contentHash = hash)) { _, _, _ -> }
        assertContentEquals(bytes, Files.readAllBytes(root.resolve("song.flac")))
    }
}
