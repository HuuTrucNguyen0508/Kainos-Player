package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomeLanConflictResolutionTest {
    private val cert = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val localBytes = Random(10).nextBytes(VAULT_BLOB_CHUNK_BYTES + 100)
    private val remoteBytes = Random(11).nextBytes(VAULT_BLOB_CHUNK_BYTES + 500)

    private fun remoteIndex() = VaultIndexDocument(
        deviceId = "hub",
        entries = listOf(VaultFileEntry("x.flac", remoteBytes.size.toLong(), mtimeMs = 5L)),
    )

    private suspend fun serviceWithConflict(
        scope: CoroutineScope,
        clientFor: () -> FakeVaultClient,
    ): Triple<HomeLanSyncService, java.nio.file.Path, MutableList<FakeVaultClient>> {
        val (root, store) = tempVaultStore()
        Files.write(root.resolve("x.flac"), localBytes)
        var settings = AppSettings(
            homeLanSyncEnabled = true,
            homeLanSyncRole = HomeLanSyncRole.CLIENT,
            homeLanSyncDeviceId = "phone",
            homeLanSyncVaultHeartsOnly = false,
            homeLanSyncPairing = HomeLanSyncPairing(
                deviceId = "phone",
                sharedSecretHex = "aabb",
                hubHost = "192.168.1.10",
                hubCertSha256Hex = cert,
            ),
        )
        val created = mutableListOf<FakeVaultClient>()
        val service = HomeLanSyncService(
            scope = scope,
            settings = { settings },
            updateSettings = { transform -> settings = transform(settings) },
            library = LibraryRepository(),
            playlists = KainosPlaylistRepository(),
            hubFactory = { _, _, _, _, _ -> NoOpHomeLanSyncHub() },
            clientFactory = { clientFor().also { created += it } },
            vaultStore = store,
            refreshLocalLibrary = {},
            isDesktop = false,
        )
        service.hydrateFromSettings(settings)
        service.syncNow().getOrThrow()
        assertEquals(listOf("x.flac"), service.status.value.pendingConflicts.map { it.relPath })
        return Triple(service, root, created)
    }

    @Test
    fun keepRemoteWithEmptyChunkFailsAndKeepsConflictPending() = runTest {
        val (service, root, created) = serviceWithConflict(this) {
            FakeVaultClient(remoteBytes, remoteIndex(), emptyChunks = true)
        }
        val result = service.resolveConflictKeepRemote("x.flac")

        assertTrue(result.isFailure)
        assertEquals(listOf("x.flac"), service.status.value.pendingConflicts.map { it.relPath })
        assertNotNull(service.status.value.lastError)
        assertContentEquals(localBytes, Files.readAllBytes(root.resolve("x.flac")))
        assertTrue(created.all { it.closed }, "every client session must be closed")
    }

    @Test
    fun keepRemoteWithShortChunkFailsAndKeepsConflictPending() = runTest {
        val (service, root, _) = serviceWithConflict(this) {
            FakeVaultClient(remoteBytes, remoteIndex(), shortChunks = true)
        }
        assertTrue(service.resolveConflictKeepRemote("x.flac").isFailure)
        assertEquals(listOf("x.flac"), service.status.value.pendingConflicts.map { it.relPath })
        assertContentEquals(localBytes, Files.readAllBytes(root.resolve("x.flac")))
        assertTrue(Files.notExists(root.resolve("x.flac.kainos-part")), "failed replace drops the partial")
    }

    @Test
    fun keepRemoteReplacesFileAndClearsConflict() = runTest {
        val (service, root, created) = serviceWithConflict(this) {
            FakeVaultClient(remoteBytes, remoteIndex())
        }
        assertTrue(service.resolveConflictKeepRemote("x.flac").isSuccess)
        assertTrue(service.status.value.pendingConflicts.isEmpty())
        assertContentEquals(remoteBytes, Files.readAllBytes(root.resolve("x.flac")))
        assertTrue(created.all { it.closed })
    }

    @Test
    fun keepLocalUploadsEveryChunkWithFullTotal() = runTest {
        val (service, _, created) = serviceWithConflict(this) {
            FakeVaultClient(remoteBytes, remoteIndex())
        }
        assertTrue(service.resolveConflictKeepLocal("x.flac").isSuccess)
        val uploads = created.last().uploads
        assertEquals(
            listOf(
                Triple(0L, localBytes.size.toLong(), VAULT_BLOB_CHUNK_BYTES),
                Triple(VAULT_BLOB_CHUNK_BYTES.toLong(), localBytes.size.toLong(), 100),
            ),
            uploads,
        )
        assertTrue(service.status.value.pendingConflicts.isEmpty())
    }
}
