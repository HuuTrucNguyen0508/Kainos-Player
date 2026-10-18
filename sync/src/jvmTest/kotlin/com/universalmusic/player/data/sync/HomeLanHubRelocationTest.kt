package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.ListeningPairingIdentity
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeLanHubRelocationTest {
    private val cert = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private var settings = AppSettings(
        homeLanSyncEnabled = true,
        homeLanSyncRole = HomeLanSyncRole.CLIENT,
        homeLanSyncDeviceId = "phone",
        homeLanSyncHub = PairedPeer("pc", "Kainos on pc", CertFingerprint.parse(cert)!!, "192.168.1.10", pairedAtMs = 1L),
    )
    private var persistedSettings = settings
    private val settingsMutex = Mutex()
    private val clientHosts = mutableListOf<String?>()
    private val openClients = mutableListOf<FakeVaultClient>()

    private fun service(
        scope: CoroutineScope, liveHost: String, vararg advertised: DiscoveredHub,
        discoveryGate: CompletableDeferred<Unit>? = null,
        beforeSettingsUpdate: suspend () -> Unit = {},
        afterSettingsUpdate: suspend () -> Unit = {},
        afterSettingsPersist: suspend () -> Unit = {},
        listening: ListeningExchangeStore? = null,
        onProbe: () -> Unit = {},
    ) =
        HomeLanSyncService(
            scope = scope,
            settings = { settings },
            updateSettings = { transform ->
                beforeSettingsUpdate()
                settingsMutex.withLock {
                    val next = transform(settings)
                    persistedSettings = next
                    afterSettingsPersist()
                    settings = next
                    afterSettingsUpdate()
                }
            },
            library = LibraryRepository(),
            playlists = KainosPlaylistRepository(),
            hubFactory = { NoOpHomeLanSyncHub() },
            pairingClientFactory = { _, _ ->
                object : HomeLanPairingClient {
                    override suspend fun start(request: PairStartRequest) =
                        PairStartResponse("replacement", "11".repeat(PAIRING_NONCE_BYTES))
                    override suspend fun reveal(request: PairRevealRequest) = Unit
                    override suspend fun confirm(attemptId: String) = Unit
                    override suspend fun cancel(attemptId: String) = Unit
                    override suspend fun status(attemptId: String) =
                        PairStatusResponse(PairState.PAIRED, hubDeviceId = "pc-b")
                }
            },
            identity = FixedDeviceIdentity("cc".repeat(32)),
            clientFactory = { hub ->
                clientHosts += hub.lastKnownHost
                val reachable = FakeVaultClient(ByteArray(0)).also { openClients += it }
                if (hub.lastKnownHost == liveHost) {
                    object : HomeLanSyncClient by reachable {
                        override suspend fun probeHub(): Result<HomeLanHealthResponse> {
                            onProbe()
                            return reachable.probeHub()
                        }
                    }
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
            listening = listening,
            hubBrowser = {
                flow {
                    discoveryGate?.await()
                    advertised.forEach { emit(it) }
                    awaitCancellation()
                }
            },
        ).also { it.hydrateFromSettings(settings) }

    @Test
    fun successfulHealthProbeStillSyncsWhenClearingListeningRefusalFails() = runTest {
        val base = PendingStore(items("a"))
        base.setRefusedPairing(settings.listeningDeliveryTarget(false, FixedDeviceIdentity("cc".repeat(32)).fingerprint())!!.pairingIdentity)
        val store = object : ListeningExchangeStore by base {
            override suspend fun setRefusedPairing(pairing: ListeningPairingIdentity?) {
                throw IOException("listening database unavailable")
            }
        }
        val service = service(backgroundScope, "192.168.1.10", listening = store)

        service.syncNow().getOrThrow()

        assertNull(service.status.value.lastError)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun addressChangeDuringProbeStillClearsRefusalForTheSamePairing() = runTest {
        val store = PendingStore(items("a"))
        store.setRefusedPairing(settings.listeningDeliveryTarget(false, FixedDeviceIdentity("cc".repeat(32)).fingerprint())!!.pairingIdentity)
        val service = service(backgroundScope, "192.168.1.10", listening = store, onProbe = {
            settings = settings.copy(homeLanSyncHub = settings.homeLanSyncHub!!.copy(lastKnownHost = "192.168.1.57"))
        })

        service.syncNow()

        assertNull(store.refusedPairing(), "Address changes must not prevent trust recovery")
    }

    @Test
    fun hubFoundByMdnsAtANewAddressIsStoredAndSynced() = runTest {
        val service = service(backgroundScope, "192.168.1.57", DiscoveredHub(cert, "pc", "192.168.1.57", 43822))

        service.syncNow().getOrThrow()

        assertEquals(listOf<String?>("192.168.1.10", "192.168.1.57"), clientHosts)
        assertEquals("192.168.1.57", settings.homeLanSyncHub?.lastKnownHost)
        assertEquals("192.168.1.57", service.trust.value.hub?.lastKnownHost)
        assertNull(service.status.value.lastError)
        assertTrue(openClients.all { it.closed }, "every client session must be closed")
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
        assertEquals("192.168.1.10", settings.homeLanSyncHub?.lastKnownHost)
        assertEquals(listOf<String?>("192.168.1.10"), clientHosts)
        assertTrue(openClients.all { it.closed }, "every client session must be closed")
    }
    @Test
    fun foregroundDiscoveryCannotOverwriteAReplacementPairing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        service.onAppForeground()
        runCurrent()
        service.pairWith(DiscoveredHub("b".repeat(64), "pc-b", "192.168.2.20", 43999, "PC B")).confirm()
        val replacement = settings.homeLanSyncHub
        gate.complete(Unit)
        runCurrent()
        assertEquals(replacement, settings.homeLanSyncHub, "persisted replacement pairing must be untouched")
        assertEquals(replacement, service.trust.value.hub, "visible replacement pairing must be untouched")
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun syncDiscoveryCannotOverwriteAReplacementPairing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        val sync = launch { service.syncNow() }
        runCurrent()
        // Settings hydration can replace pairing while syncNow holds its transport mutex.
        settings = settings.copy(homeLanSyncHub = settings.homeLanSyncHub!!.copy(
            lastKnownHost = "192.168.2.20", lastKnownPort = 43999, fingerprint = CertFingerprint.parse("b".repeat(64))!!))
        service.hydrateFromSettings(settings)
        val replacement = settings.homeLanSyncHub
        gate.complete(Unit)
        sync.join()
        assertEquals(replacement, settings.homeLanSyncHub)
        assertEquals(replacement, service.trust.value.hub)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun replacementWhileWaitingToPersistDoesNotReceiveTheOldEndpoint() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822),
            beforeSettingsUpdate = { entered.complete(Unit); release.await() })
        service.onAppForeground()
        entered.await()
        settings = settings.copy(homeLanSyncHub = settings.homeLanSyncHub!!.copy(
            lastKnownHost = "192.168.2.20", fingerprint = CertFingerprint.parse("b".repeat(64))!!))
        service.hydrateFromSettings(settings)
        val replacement = settings.homeLanSyncHub
        release.complete(Unit)
        runCurrent()
        assertEquals(replacement, settings.homeLanSyncHub)
        assertEquals(replacement, service.trust.value.hub)
    }

    @Test
    fun replacementWhilePersistenceFinishesDoesNotReceiveTheOldVisibleEndpoint() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822),
            afterSettingsUpdate = { entered.complete(Unit); release.await() })
        service.onAppForeground()
        entered.await()
        settings = settings.copy(homeLanSyncHub = settings.homeLanSyncHub!!.copy(
            lastKnownHost = "192.168.2.20", fingerprint = CertFingerprint.parse("b".repeat(64))!!))
        service.hydrateFromSettings(settings)
        val replacement = settings.homeLanSyncHub
        release.complete(Unit)
        runCurrent()
        assertEquals(replacement, settings.homeLanSyncHub)
        assertEquals(replacement, service.trust.value.hub)
    }

    @Test
    fun stopInvalidatesDiscoveryEvenIfThePairingIsUnchanged() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        val original = settings.homeLanSyncHub
        service.onAppForeground()
        runCurrent()
        service.stopHub()
        gate.complete(Unit)
        runCurrent()
        assertEquals(original, settings.homeLanSyncHub)
        assertEquals(original, service.trust.value.hub)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun disableAndReenableInvalidatesAnEarlierDiscovery() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        val original = settings.homeLanSyncHub
        service.onAppForeground()
        runCurrent()
        service.enable(false)
        service.enable(true)
        gate.complete(Unit)
        runCurrent()
        assertEquals(original, settings.homeLanSyncHub)
        assertEquals(original, service.trust.value.hub)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun unpairDuringDiscoveryKeepsSettingsAndVisiblePairingEmpty() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        service.onAppForeground()
        runCurrent()
        service.removeDevice("pc")
        gate.complete(Unit)
        runCurrent()
        assertNull(settings.homeLanSyncHub)
        assertNull(service.trust.value.hub)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun cancellingTheServiceScopeClosesDiscoveryClientsWithoutRememberingAnEndpoint() = runTest {
        val gate = CompletableDeferred<Unit>()
        val job = SupervisorJob()
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val service = service(scope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        val original = settings.homeLanSyncHub
        try {
            service.onAppForeground()
            runCurrent()
            scope.cancel()
            gate.complete(Unit)
            runCurrent()
            assertEquals(original, settings.homeLanSyncHub)
            assertEquals(original, service.trust.value.hub)
            assertTrue(openClients.all { it.closed })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun disableAfterEndpointPersistenceKeepsPairingCoherentAndRetrySucceeds() = runTest {
        val persisted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = true
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822),
            afterSettingsUpdate = {
                if (pause) {
                    pause = false
                    persisted.complete(Unit)
                    release.await()
                }
            })
        service.onAppForeground()
        persisted.await()
        val disable = launch { service.enable(false) }
        runCurrent()
        release.complete(Unit)
        disable.join()
        service.enable(true)

        val retry = service.syncNow()
        assertTrue(retry.isSuccess, "retry must use the committed endpoint: ${retry.exceptionOrNull()}")
        assertEquals("192.168.1.57", persistedSettings.homeLanSyncHub?.lastKnownHost)
        assertEquals(persistedSettings.homeLanSyncHub, service.trust.value.hub,
            "a committed endpoint must remain visible after lifecycle invalidation")
        assertNull(service.status.value.lastError)
        service.hydrateFromSettings(persistedSettings)
        assertEquals(persistedSettings.homeLanSyncHub, service.trust.value.hub)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun cancellationAfterEndpointPersistencePublishesTheCommittedPairing() = runTest {
        val persisted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = true
        val job = SupervisorJob()
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val service = service(scope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822),
            afterSettingsPersist = {
                if (pause) {
                    pause = false
                    persisted.complete(Unit)
                    release.await()
                }
            })
        try {
            service.onAppForeground()
            persisted.await()
            scope.cancel()
            release.complete(Unit)
            job.join()

            assertEquals("192.168.1.57", persistedSettings.homeLanSyncHub?.lastKnownHost)
            assertEquals(persistedSettings.homeLanSyncHub, settings.homeLanSyncHub,
                "cancellation cannot strand a durable endpoint before settings publication")
            assertEquals(persistedSettings.homeLanSyncHub, service.trust.value.hub)
            assertNull(service.status.value.lastSyncAtMs, "cancelled foreground work must not sync")
            service.syncNow().getOrThrow()
            assertNull(service.status.value.lastError)
            assertTrue(openClients.all { it.closed })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun stopAfterEndpointPersistenceReconcilesBeforeHydrationAndRetry() = runTest {
        val persisted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = true
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822),
            afterSettingsUpdate = {
                if (pause) {
                    pause = false
                    persisted.complete(Unit)
                    release.await()
                }
            })
        service.onAppForeground()
        persisted.await()
        service.stopHub()
        release.complete(Unit)
        runCurrent()

        assertEquals(persistedSettings.homeLanSyncHub, service.trust.value.hub)
        assertNull(service.status.value.lastSyncAtMs, "stopped foreground work must not sync")
        service.hydrateFromSettings(persistedSettings)
        service.syncNow().getOrThrow()
        assertNull(service.status.value.lastError)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun cancellationAfterSettingsPublicationReconcilesWithoutStartingSync() = runTest {
        val published = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = true
        val job = SupervisorJob()
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val service = service(scope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822),
            afterSettingsUpdate = {
                if (pause) {
                    pause = false
                    published.complete(Unit)
                    release.await()
                }
            })
        try {
            service.onAppForeground()
            published.await()
            scope.cancel()
            release.complete(Unit)
            job.join()

            assertEquals(persistedSettings.homeLanSyncHub, service.trust.value.hub)
            assertNull(service.status.value.lastSyncAtMs)
            service.syncNow().getOrThrow()
            assertTrue(openClients.all { it.closed })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun cancelledResolutionWaitingForSettingsCannotCommitAnEndpoint() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = SupervisorJob()
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val original = settings.homeLanSyncHub
        val service = service(scope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822),
            beforeSettingsUpdate = { entered.complete(Unit); release.await() })
        try {
            service.onAppForeground()
            entered.await()
            scope.cancel()
            release.complete(Unit)
            job.join()

            assertEquals(original, persistedSettings.homeLanSyncHub)
            assertEquals(original, settings.homeLanSyncHub)
            assertEquals(original, service.trust.value.hub)
            assertTrue(openClients.all { it.closed })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun replacementQueuedBehindEndpointPersistenceRemainsAuthoritative() = runTest {
        val persisted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = true
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822),
            afterSettingsPersist = {
                if (pause) {
                    pause = false
                    persisted.complete(Unit)
                    release.await()
                }
            })
        service.onAppForeground()
        persisted.await()
        val replacement = launch {
            service.pairWith(DiscoveredHub("b".repeat(64), "pc-b", "192.168.2.20", 43999, "PC B")).confirm()
        }
        runCurrent()
        release.complete(Unit)
        replacement.join()
        runCurrent()

        assertEquals("192.168.2.20", persistedSettings.homeLanSyncHub?.lastKnownHost)
        assertEquals("b".repeat(64), persistedSettings.homeLanSyncHub?.fingerprint?.hex)
        assertEquals(persistedSettings.homeLanSyncHub, settings.homeLanSyncHub)
        assertEquals(persistedSettings.homeLanSyncHub, service.trust.value.hub)
        assertNull(service.status.value.lastSyncAtMs)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun cancellingSyncAfterPersistenceClosesItsClientAndKeepsRetryUsable() = runTest {
        val persisted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = true
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822),
            afterSettingsPersist = {
                if (pause) {
                    pause = false
                    persisted.complete(Unit)
                    release.await()
                }
            })
        val sync = launch { service.syncNow() }
        persisted.await()
        sync.cancel()
        release.complete(Unit)
        sync.join()

        assertEquals(persistedSettings.homeLanSyncHub, settings.homeLanSyncHub)
        assertEquals(persistedSettings.homeLanSyncHub, service.trust.value.hub)
        assertNull(service.status.value.lastSyncAtMs)
        assertTrue(openClients.all { it.closed })
        service.syncNow().getOrThrow()
        assertNull(service.status.value.lastError)
        assertTrue(openClients.all { it.closed })
    }

}
