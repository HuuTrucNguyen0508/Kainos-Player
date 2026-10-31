package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomeLanBatchTransferTest {
    private val remoteBytes = Random(20).nextBytes(VAULT_BLOB_CHUNK_BYTES + 300)

    private fun remoteIndex(names: List<String>) = VaultIndexDocument(
        deviceId = "hub",
        entries = names.map { VaultFileEntry(it, remoteBytes.size.toLong(), mtimeMs = 5L) },
    )

    private suspend fun serviceWithPending(
        scope: CoroutineScope,
        names: List<String>,
        clientFor: () -> FakeVaultClient,
    ): Triple<HomeLanSyncService, Path, MutableList<FakeVaultClient>> {
        val (root, store) = tempVaultStore()
        var settings = AppSettings(
            homeLanSyncEnabled = true,
            homeLanSyncRole = HomeLanSyncRole.CLIENT,
            homeLanSyncDeviceId = "phone",
            homeLanSyncVaultHeartsOnly = false,
            homeLanSyncHub = PairedPeer(
                "hub",
                "Kainos on pc",
                CertFingerprint.parse("0123456789abcdef".repeat(4))!!,
                "192.168.1.10",
                pairedAtMs = 1L,
            ),
        )
        val created = mutableListOf<FakeVaultClient>()
        val service = HomeLanSyncService(
            scope = scope,
            settings = { settings },
            updateSettings = { transform -> settings = transform(settings) },
            library = LibraryRepository(),
            playlists = KainosPlaylistRepository(),
            hubFactory = { NoOpHomeLanSyncHub() },
            pairingClientFactory = noPairingClient(),
            identity = FixedDeviceIdentity("bb".repeat(32)),
            clientFactory = { clientFor().also { created += it } },
            vaultStore = store,
            refreshLocalLibrary = {},
            isDesktop = false,
        )
        service.hydrateFromSettings(settings)
        service.syncNow().getOrThrow()
        assertEquals(names.sorted(), service.status.value.pendingTransfers.map { it.relPath }.sorted())
        created.clear()
        return Triple(service, root, created)
    }

    @Test
    fun transferAllCopiesEveryPendingFileOverOneClient() = runTest {
        val names = listOf("a.flac", "b.flac", "c.flac")
        val (service, root, created) = serviceWithPending(this, names) {
            FakeVaultClient(remoteBytes, remoteIndex(names))
        }

        val result = service.confirmAllVaultTransfers()

        assertEquals("Transferred 3 of 3", result.getOrThrow())
        assertTrue(service.status.value.pendingTransfers.isEmpty())
        names.forEach { assertContentEquals(remoteBytes, Files.readAllBytes(root.resolve(it))) }
        assertEquals(1, created.size)
        assertTrue(created.single().closed)
    }

    @Test
    fun transferAllStopsAfterRepeatedFailuresAndKeepsThemPending() = runTest {
        val names = listOf("a.flac", "b.flac", "c.flac", "d.flac", "e.flac")
        val (service, _, _) = serviceWithPending(this, names) {
            FakeVaultClient(remoteBytes, remoteIndex(names), emptyChunks = true)
        }

        val result = service.confirmAllVaultTransfers()

        assertEquals("Transferred 0 of 5, 3 failed, 2 not tried", result.getOrThrow())
        assertEquals(names.sorted(), service.status.value.pendingTransfers.map { it.relPath }.sorted())
        assertNotNull(service.status.value.lastError)
    }

    @Test
    fun skipAllClearsPendingTransfers() = runTest {
        val names = listOf("a.flac", "b.flac")
        val (service, root, _) = serviceWithPending(this, names) {
            FakeVaultClient(remoteBytes, remoteIndex(names))
        }

        assertEquals("Skipped 2 transfers", service.dismissAllVaultTransfers().getOrThrow())
        assertTrue(service.status.value.pendingTransfers.isEmpty())
        names.forEach { assertTrue(Files.notExists(root.resolve(it))) }
    }
}
