package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.platform.HomeLanHubConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLanPairingServiceTest {
    private val hubPin = CertFingerprint.parse("aa".repeat(32))!!
    private val phonePin = CertFingerprint.parse("bb".repeat(32))!!
    private val discovered = DiscoveredHub(hubPin.hex, "pc", "192.0.2.1", 43823, "Kainos on studio")

    @Test
    fun phoneStoresTheObservedHubOnlyAfterBothConfirmations() = runTest {
        val pcPeers = mutableListOf<PairedPeer>()
        val host = PairingHost({ hubPin }, { "pc" }, { pcPeers += it })
        val directory = Files.createTempDirectory("kainos-pair-service").toFile()
        try {
            var settings = AppSettings(homeLanSyncDeviceId = "phone", homeLanSyncRepairNeeded = true)
            var closed = false
            val service = phoneService(backgroundScope, { settings }, { settings = it(settings) }, directory,
                { endpoint, pin ->
                    assertEquals(HubEndpoint("192.0.2.1", 43823), endpoint)
                    assertEquals(hubPin, pin)
                    object : HomeLanPairingClient {
                        override suspend fun start(request: PairStartRequest) = host.start(phonePin, request).value()
                        override suspend fun reveal(request: PairRevealRequest) { host.reveal(phonePin, request).value() }
                        override suspend fun confirm(attemptId: String) { host.confirmFromPhone(phonePin, attemptId).value() }
                        override suspend fun cancel(attemptId: String) { host.cancel(phonePin, attemptId).value() }
                        override suspend fun status(attemptId: String) = host.status(phonePin, attemptId).value()
                        override fun close() { closed = true }
                    }
                })
            val session = service.pairWith(discovered)
            assertEquals(host.incoming.value?.code, session.code)
            assertNull(settings.homeLanSyncHub)
            val confirming = async { session.confirm() }
            runCurrent()
            assertTrue(pcPeers.isEmpty())
            assertNull(settings.homeLanSyncHub)
            host.confirmFromPc(session.attemptId).value()
            val peer = confirming.await()
            assertEquals(PairedPeer("pc", "Kainos on studio", hubPin, "192.0.2.1", 43823, 123L), peer)
            assertEquals(peer, settings.homeLanSyncHub)
            assertEquals(false, settings.homeLanSyncRepairNeeded)
            assertEquals(HomeLanSyncRole.CLIENT, settings.homeLanSyncRole)
            assertEquals(listOf(phonePin), pcPeers.map { it.fingerprint })
            assertTrue(closed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun resetClearsTrustAndRecreatesTheHubWithTheNewIdentity() = runTest {
        val directory = Files.createTempDirectory("kainos-reset-service").toFile()
        try {
            val identity = FixedDeviceIdentity("aa".repeat(32), "cc".repeat(32))
            val phone = PairedPeer("phone", "Phone", phonePin, pairedAtMs = 123L)
            var settings = AppSettings(homeLanSyncEnabled = true, homeLanSyncRole = HomeLanSyncRole.HUB,
                homeLanSyncDeviceId = "pc", homeLanSyncPeers = listOf(phone))
            val pins = mutableListOf<CertFingerprint>()
            val configs = mutableListOf<HomeLanHubConfig>()
            var stops = 0
            val service = HomeLanSyncService(backgroundScope, { settings }, { settings = it(settings) },
                LibraryRepository(), KainosPlaylistRepository(),
                hubFactory = { config ->
                    configs += config
                    pins += identity.fingerprint()
                    object : HomeLanSyncHub {
                        override var isListening = false
                        override suspend fun start() { isListening = true }
                        override suspend fun stop() { isListening = false; stops++ }
                        override fun touchActivity() = Unit
                        override fun lastActivityAtMs(): Long? = null
                    }
                },
                clientFactory = { error("No sync client needed") }, pairingClientFactory = noPairingClient(),
                identity = identity, vaultStore = JvmHomeLanVaultStore({ directory.toPath().toString() }, directory.resolve("vault-state.json").toPath()),
                refreshLocalLibrary = {}, isDesktop = true, hubPort = 43824)
            service.hydrateFromSettings(settings)
            service.startHubIfNeeded()
            service.removeDevice("phone")
            assertEquals(emptyList(), configs.single().peers())
            service.resetIdentity()
            assertEquals(listOf(hubPin, CertFingerprint.parse("cc".repeat(32))), pins)
            assertEquals(1, stops)
            assertEquals(listOf(43824, 43824), configs.map { it.port })
            assertEquals(emptyList(), settings.homeLanSyncPeers)
            assertNull(settings.homeLanSyncHub)
            assertEquals(HomeLanTrust(), service.trust.value)
            assertTrue(service.status.value.hubListening)
            service.stopHub()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun phoneService(
        scope: CoroutineScope,
        settings: () -> AppSettings,
        update: suspend ((AppSettings) -> AppSettings) -> Unit,
        directory: java.io.File,
        factory: (HubEndpoint, CertFingerprint) -> HomeLanPairingClient,
    ) = HomeLanSyncService(scope, settings, update, LibraryRepository(), KainosPlaylistRepository(),
        hubFactory = { error("Phone has no hub") }, clientFactory = { error("No sync client needed") },
        pairingClientFactory = factory, identity = FixedDeviceIdentity(phonePin.hex),
        vaultStore = JvmHomeLanVaultStore({ directory.toPath().toString() }, directory.resolve("vault-state.json").toPath()), refreshLocalLibrary = {},
        clock = { 123L }, isDesktop = false, deviceName = { "Phone" }, pairingPollMs = 10)

    private fun <T> PairingReply<T>.value(): T = when (this) {
        is PairingReply.Ok -> value
        is PairingReply.Refused -> error(toString())
    }
}
