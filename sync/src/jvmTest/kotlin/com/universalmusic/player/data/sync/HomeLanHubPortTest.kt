package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.universalmusic.player.platform.currentTimeMillis
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLanHubPortTest {
    private val storedHub = PairedPeer(
        deviceId = "pc",
        displayName = "Kainos on pc",
        fingerprint = CertFingerprint.parse("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")!!,
        lastKnownHost = "192.168.1.10",
        lastKnownPort = 43822,
        pairedAtMs = 1L,
    )

    private class ListeningHub : HomeLanSyncHub {
        override var isListening = false
        override suspend fun start() { isListening = true }
        override suspend fun stop() { isListening = false }
        override fun touchActivity() = Unit
        override fun lastActivityAtMs(): Long = 0
    }

    private fun service(
        scope: CoroutineScope,
        role: HomeLanSyncRole,
        hubPorts: MutableList<Int>,
        clock: () -> Long = ::currentTimeMillis,
    ): HomeLanSyncService {
        val settings = AppSettings(
            homeLanSyncEnabled = true,
            homeLanSyncRole = role,
            homeLanSyncDeviceId = "pc",
            homeLanSyncHub = storedHub,
        )
        return HomeLanSyncService(
            scope = scope,
            settings = { settings },
            updateSettings = {},
            library = LibraryRepository(),
            playlists = KainosPlaylistRepository(),
            hubFactory = { config -> hubPorts += config.port; ListeningHub() },
            clientFactory = { error("no client in this test") },
            pairingClientFactory = noPairingClient(),
            identity = FixedDeviceIdentity("aa".repeat(32)),
            vaultStore = tempVaultStore().second,
            refreshLocalLibrary = {},
            isDesktop = true,
            hubPort = 44005,
            clock = clock,
        ).also { it.hydrateFromSettings(settings) }
    }

    @Test
    fun hubListensOnTheConfiguredPortNotTheStoredOne() = runTest {
        val hubPorts = mutableListOf<Int>()
        val service = service(backgroundScope, HomeLanSyncRole.HUB, hubPorts)

        service.startHubIfNeeded()

        assertEquals(listOf(44005), hubPorts)
        assertEquals("Hub listening on 44005", service.status.value.lastDetail)
    }

    @Test
    fun idleHubStaysListeningUntilExplicitlyStopped() = runTest {
        val hubPorts = mutableListOf<Int>()
        val service = service(backgroundScope, HomeLanSyncRole.HUB, hubPorts) { testScheduler.currentTime }
        service.startHubIfNeeded()
        advanceTimeBy(40L * 60L * 1_000L)
        runCurrent()
        assertTrue(service.status.value.hubListening)
        assertEquals(listOf(44005), hubPorts)
        service.stopHub()
        assertFalse(service.status.value.hubListening)
    }

    @Test
    fun clientKeepsTheRemoteHubPort() = runTest {
        val hubPorts = mutableListOf<Int>()
        val service = service(backgroundScope, HomeLanSyncRole.CLIENT, hubPorts)

        service.startHubIfNeeded()

        assertEquals(emptyList(), hubPorts)
        assertEquals(43822, service.trust.value.hub?.lastKnownPort)
    }
}
