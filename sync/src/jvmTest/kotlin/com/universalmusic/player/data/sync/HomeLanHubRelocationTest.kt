package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
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
        homeLanSyncPairing = HomeLanSyncPairing(
            deviceId = "phone",
            sharedSecretHex = "aabb",
            hubHost = "192.168.1.10",
            hubCertSha256Hex = cert,
        ),
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
            hubFactory = { _, _, _, _, _ -> NoOpHomeLanSyncHub() },
            clientFactory = { pairing ->
                clientHosts += pairing.hubHost
                val reachable = FakeVaultClient(ByteArray(0)).also { openClients += it }
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
                    discoveryGate?.await()
                    advertised.forEach { emit(it) }
                    awaitCancellation()
                }
            },
        ).also { it.hydrateFromSettings(settings) }

    @Test
    fun hubFoundByMdnsAtANewAddressIsStoredAndSynced() = runTest {
        val service = service(backgroundScope, "192.168.1.57", DiscoveredHub(cert, "pc", "192.168.1.57", 43822))

        service.syncNow().getOrThrow()

        assertEquals(listOf<String?>("192.168.1.10", "192.168.1.57"), clientHosts)
        assertEquals("192.168.1.57", settings.homeLanSyncPairing?.hubHost)
        assertEquals("192.168.1.57", service.pairing.value?.hubHost)
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
        assertEquals("192.168.1.10", settings.homeLanSyncPairing?.hubHost)
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
        service.completeClientPairing("192.168.2.20", 43999, "ccdd", "pc-b", "123456", "b".repeat(64))
        val replacement = settings.homeLanSyncPairing
        gate.complete(Unit)
        runCurrent()
        assertEquals(replacement, settings.homeLanSyncPairing, "persisted replacement pairing must be untouched")
        assertEquals(replacement, service.pairing.value, "visible replacement pairing must be untouched")
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
        settings = settings.copy(homeLanSyncPairing = settings.homeLanSyncPairing!!.copy(
            hubHost = "192.168.2.20", hubPort = 43999, hubCertSha256Hex = "b".repeat(64)))
        service.hydrateFromSettings(settings)
        val replacement = settings.homeLanSyncPairing
        gate.complete(Unit)
        sync.join()
        assertEquals(replacement, settings.homeLanSyncPairing)
        assertEquals(replacement, service.pairing.value)
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
        settings = settings.copy(homeLanSyncPairing = settings.homeLanSyncPairing!!.copy(
            hubHost = "192.168.2.20", hubCertSha256Hex = "b".repeat(64)))
        service.hydrateFromSettings(settings)
        val replacement = settings.homeLanSyncPairing
        release.complete(Unit)
        runCurrent()
        assertEquals(replacement, settings.homeLanSyncPairing)
        assertEquals(replacement, service.pairing.value)
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
        settings = settings.copy(homeLanSyncPairing = settings.homeLanSyncPairing!!.copy(
            hubHost = "192.168.2.20", hubCertSha256Hex = "b".repeat(64)))
        service.hydrateFromSettings(settings)
        val replacement = settings.homeLanSyncPairing
        release.complete(Unit)
        runCurrent()
        assertEquals(replacement, settings.homeLanSyncPairing)
        assertEquals(replacement, service.pairing.value)
    }

    @Test
    fun stopInvalidatesDiscoveryEvenIfThePairingIsUnchanged() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        val original = settings.homeLanSyncPairing
        service.onAppForeground()
        runCurrent()
        service.stopHub()
        gate.complete(Unit)
        runCurrent()
        assertEquals(original, settings.homeLanSyncPairing)
        assertEquals(original, service.pairing.value)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun disableAndReenableInvalidatesAnEarlierDiscovery() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        val original = settings.homeLanSyncPairing
        service.onAppForeground()
        runCurrent()
        service.enable(false)
        service.enable(true)
        gate.complete(Unit)
        runCurrent()
        assertEquals(original, settings.homeLanSyncPairing)
        assertEquals(original, service.pairing.value)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun unpairDuringDiscoveryKeepsSettingsAndVisiblePairingEmpty() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service(backgroundScope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        service.onAppForeground()
        runCurrent()
        service.unpair()
        gate.complete(Unit)
        runCurrent()
        assertNull(settings.homeLanSyncPairing)
        assertNull(service.pairing.value)
        assertTrue(openClients.all { it.closed })
    }

    @Test
    fun cancellingTheServiceScopeClosesDiscoveryClientsWithoutRememberingAnEndpoint() = runTest {
        val gate = CompletableDeferred<Unit>()
        val job = SupervisorJob()
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val service = service(scope, "192.168.1.57",
            DiscoveredHub(cert, "pc-a", "192.168.1.57", 43822), discoveryGate = gate)
        val original = settings.homeLanSyncPairing
        try {
            service.onAppForeground()
            runCurrent()
            scope.cancel()
            gate.complete(Unit)
            runCurrent()
            assertEquals(original, settings.homeLanSyncPairing)
            assertEquals(original, service.pairing.value)
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
        assertEquals("192.168.1.57", persistedSettings.homeLanSyncPairing?.hubHost)
        assertEquals(persistedSettings.homeLanSyncPairing, service.pairing.value,
            "a committed endpoint must remain visible after lifecycle invalidation")
        assertNull(service.status.value.lastError)
        service.hydrateFromSettings(persistedSettings)
        assertEquals(persistedSettings.homeLanSyncPairing, service.pairing.value)
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

            assertEquals("192.168.1.57", persistedSettings.homeLanSyncPairing?.hubHost)
            assertEquals(persistedSettings.homeLanSyncPairing, settings.homeLanSyncPairing,
                "cancellation cannot strand a durable endpoint before settings publication")
            assertEquals(persistedSettings.homeLanSyncPairing, service.pairing.value)
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

        assertEquals(persistedSettings.homeLanSyncPairing, service.pairing.value)
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

            assertEquals(persistedSettings.homeLanSyncPairing, service.pairing.value)
            assertNull(service.status.value.lastSyncAtMs)
            service.syncNow().getOrThrow()
            assertTrue(openClients.all { it.closed })
        } finally {
            job.cancel()
        }
    }

}
