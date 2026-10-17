package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeLanHubPortTest {
    private val storedPairing = HomeLanSyncPairing(
        deviceId = "pc",
        sharedSecretHex = "aabb",
        hubHost = "192.168.1.10",
        hubPort = 43822,
        hubCertSha256Hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )

    private class ListeningHub : HomeLanSyncHub {
        override var isListening = false
        override suspend fun start() { isListening = true }
        override suspend fun stop() { isListening = false }
        override fun touchActivity() = Unit
        override fun lastActivityAtMs(): Long? = null
    }

    private fun service(scope: CoroutineScope, role: HomeLanSyncRole, hubPorts: MutableList<Int>): HomeLanSyncService {
        val settings = AppSettings(
            homeLanSyncEnabled = true,
            homeLanSyncRole = role,
            homeLanSyncPairing = storedPairing,
        )
        return HomeLanSyncService(
            scope = scope,
            settings = { settings },
            updateSettings = {},
            library = LibraryRepository(),
            playlists = KainosPlaylistRepository(),
            hubFactory = { pairing, _, _, _, _ -> hubPorts += pairing.hubPort; ListeningHub() },
            clientFactory = { error("no client in this test") },
            vaultStore = tempVaultStore().second,
            refreshLocalLibrary = {},
            isDesktop = true,
            hubPort = 44005,
        ).also { it.hydrateFromSettings(settings) }
    }

    @Test
    fun hubListensOnTheConfiguredPortNotTheStoredOne() = runTest {
        val hubPorts = mutableListOf<Int>()
        val service = service(backgroundScope, HomeLanSyncRole.HUB, hubPorts)

        service.startHubIfNeeded()

        assertEquals(listOf(44005), hubPorts)
        assertEquals(44005, service.pairing.value?.hubPort)
        assertEquals("Hub listening on 44005", service.status.value.lastDetail)
        assertEquals(
            "kainos-homesync:2?host=192.168.1.10&port=44005",
            service.pairingUri()?.substringBefore("&secret="),
        )
    }

    @Test
    fun clientKeepsTheRemoteHubPort() = runTest {
        val hubPorts = mutableListOf<Int>()
        val service = service(backgroundScope, HomeLanSyncRole.CLIENT, hubPorts)

        service.startHubIfNeeded()

        assertEquals(emptyList(), hubPorts)
        assertEquals(43822, service.pairing.value?.hubPort)
    }
}
