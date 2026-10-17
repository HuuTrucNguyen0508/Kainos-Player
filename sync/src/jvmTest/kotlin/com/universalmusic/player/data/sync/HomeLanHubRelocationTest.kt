package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeLanHubRelocationTest {
    private val cert = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private var settings = AppSettings(
        homeLanSyncEnabled = true,
        homeLanSyncRole = HomeLanSyncRole.CLIENT,
        homeLanSyncDeviceId = "phone",
        homeLanSyncPairing = HomeLanSyncPairing(
            deviceId = "phone",
            sharedSecretHex = "aabb",
            hubHost = "192.168.1.10",
            hubCertSha256Hex = cert,
        ),
    )
    private val clientHosts = mutableListOf<String?>()

    private fun service(scope: CoroutineScope, liveHost: String, vararg advertised: DiscoveredHub) =
        HomeLanSyncService(
            scope = scope,
            settings = { settings },
            updateSettings = { transform -> settings = transform(settings) },
            library = LibraryRepository(),
            playlists = KainosPlaylistRepository(),
            hubFactory = { _, _, _, _, _ -> NoOpHomeLanSyncHub() },
            clientFactory = { pairing ->
                clientHosts += pairing.hubHost
                val reachable = FakeVaultClient(ByteArray(0))
                if (pairing.hubHost == liveHost) {
                    reachable
                } else {
                    object : HomeLanSyncClient by reachable {
                        override suspend fun probeHub(): Result<HomeLanHealthResponse> =
                            Result.failure(IllegalStateException("Connection refused"))
                    }
                }
            },
            vaultStore = tempVaultStore().second,
            refreshLocalLibrary = {},
            isDesktop = false,
            hubBrowser = {
                flow {
                    advertised.forEach { emit(it) }
                    awaitCancellation()
                }
            },
        ).also { it.hydrateFromSettings(settings) }

    @Test
    fun hubFoundByMdnsAtANewAddressIsStoredAndSynced() = runTest {
        val service = service(backgroundScope, "192.168.1.57", DiscoveredHub(cert, "pc", "192.168.1.57", 43822))

        service.syncNow().getOrThrow()

        assertEquals(listOf<String?>("192.168.1.10", "192.168.1.57", "192.168.1.57"), clientHosts)
        assertEquals("192.168.1.57", settings.homeLanSyncPairing?.hubHost)
        assertEquals("192.168.1.57", service.pairing.value?.hubHost)
        assertNull(service.status.value.lastError)
    }

    @Test
    fun unreachableHubFailsTheSyncAndKeepsTheStoredAddress() = runTest {
        val service = service(backgroundScope, "192.168.1.57")

        val result = service.syncNow()

        assertEquals(
            "Hub not found: stored 192.168.1.10:43822: Connection refused; " +
                "mDNS found no hub with the pinned certificate within 4 s",
            result.exceptionOrNull()?.message,
        )
        assertEquals(result.exceptionOrNull()?.message, service.status.value.lastError)
        assertEquals("192.168.1.10", settings.homeLanSyncPairing?.hubHost)
        assertEquals(listOf<String?>("192.168.1.10"), clientHosts)
    }
}
