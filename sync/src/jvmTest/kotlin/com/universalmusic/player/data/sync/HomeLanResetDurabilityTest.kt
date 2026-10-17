package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.platform.DeviceIdentity
import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLanResetDurabilityTest {
    @Test
    fun failedSettingsWriteCannotLeaveNewIdentityAuthorizingOldPeerAfterReload() = runTest {
        Fixture(backgroundScope).use { f ->
            f.initialize()
            f.service.startHubIfNeeded()
            f.makeSettingsUnwritable()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            f.makeSettingsWritable()
            assertEquals(f.original, JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint(),
                "No identity mutation is allowed before durable reset intent")
            val rotated = JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint() != f.original
            f.service.startHubIfNeeded()
            val liveAuthorized = f.authorizesPhone()
            f.reload()
            f.service.startHubIfNeeded()
            val reloadAuthorized = f.authorizesPhone()
            assertTrue(liveAuthorized && reloadAuthorized, "A failure before reset intent must retain old-key trust")
            assertFalse(rotated && (liveAuthorized || reloadAuthorized),
                "New persisted identity must not authorize old trust: rotated=$rotated live=$liveAuthorized reload=$reloadAuthorized")
        }
    }

    @Test
    fun failedTrustCleanupBlocksOldPeerBeforeAndAfterReloadAndRetryConverges() = runTest {
        Fixture(backgroundScope).use { f ->
            f.initialize()
            f.service.startHubIfNeeded()
            val oldListener = f.config!!
            f.failTrustCleanup()
            val failure = assertFailsWith<IllegalStateException> { f.service.resetIdentity() }
            assertContains(failure.message.orEmpty(), "A new identity was created")
            assertFalse(failure.message.orEmpty().contains("Existing pairings remain"))
            val rotated = JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint()
            assertNotEquals(f.original, rotated)
            assertTrue(oldListener.peers().isEmpty())
            f.assertBlocked()
            f.makeSettingsWritable()
            f.beforeWrite = {}
            f.reload()
            assertEquals(rotated, f.identity.fingerprint())
            f.assertBlocked()
            f.service.resetIdentity()
            assertFalse(f.settings.homeLanSyncIdentityResetPending)
            assertTrue(f.settings.homeLanSyncPeers.isEmpty())
            assertNull(f.settings.homeLanSyncHub)
            assertFalse(f.authorizesPhone())
            f.reload()
            f.service.startHubIfNeeded()
            assertTrue(f.service.status.value.hubListening)
            assertFalse(f.authorizesPhone())
        }
    }

    @Test
    fun cancellationAfterIntentSurvivesReloadAndRetryWithoutTrustUse() = runTest {
        Fixture(backgroundScope).use { f ->
            f.initialize()
            f.service.startHubIfNeeded()
            val committed = CompletableDeferred<Unit>()
            f.afterWrite = { next ->
                if (next.homeLanSyncIdentityResetPending) {
                    committed.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            }
            val resetting = async { f.service.resetIdentity() }
            committed.await()
            resetting.cancelAndJoin()
            f.assertBlocked()
            assertEquals(f.original, JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint())
            f.afterWrite = {}
            f.reload()
            f.assertBlocked()
            f.service.resetIdentity()
            assertNotEquals(f.original, f.identity.fingerprint())
            assertFalse(f.settings.homeLanSyncIdentityResetPending)
            assertFalse(f.authorizesPhone())
        }
    }

    @Test
    fun cancellationAfterRotationLeavesDurableTrustBlockUntilRetry() = runTest {
        Fixture(backgroundScope).use { f ->
            f.initialize()
            val rotated = CompletableDeferred<Unit>()
            f.beforeWrite = { next ->
                if (!next.homeLanSyncIdentityResetPending) {
                    rotated.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            }
            val resetting = async { f.service.resetIdentity() }
            rotated.await()
            resetting.cancelAndJoin()
            assertNotEquals(f.original, JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint())
            f.beforeWrite = {}
            f.reload()
            f.assertBlocked()
            f.service.resetIdentity()
            assertFalse(f.authorizesPhone())
            assertFalse(f.settings.homeLanSyncIdentityResetPending)
        }
    }

    @Test
    fun concurrentResetsFinishWithoutReauthorizingOldTrust() = runTest {
        Fixture(backgroundScope).use { f ->
            f.initialize()
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.afterWrite = { next ->
                if (next.homeLanSyncIdentityResetPending && committed.complete(Unit)) release.await()
            }
            val first = async { f.service.resetIdentity() }
            committed.await()
            val second = async { f.service.resetIdentity() }
            runCurrent()
            assertEquals(f.original, JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint())
            release.complete(Unit)
            first.await()
            second.await()
            f.reload()
            f.service.startHubIfNeeded()
            assertNotEquals(f.original, f.identity.fingerprint())
            assertFalse(f.authorizesPhone())
            assertTrue(f.settings.homeLanSyncPeers.isEmpty())
            assertFalse(f.settings.homeLanSyncIdentityResetPending)
        }
    }

    @Test
    fun retryCannotRestoreStaleTrustWhenTheAlreadyRotatedKeySurvivesAnotherFailure() = runTest {
        Fixture(backgroundScope).use { f ->
            f.initialize()
            f.failTrustCleanup()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            f.makeSettingsWritable()
            f.beforeWrite = {}
            f.reload()
            Files.setPosixFilePermissions(f.identityDirectory, PosixFilePermissions.fromString("r-x------"))
            try {
                val failure = assertFailsWith<Exception> { f.service.resetIdentity() }
                assertFalse(failure.message.orEmpty().contains("Existing pairings remain"))
                f.assertBlocked()
                f.reload()
                f.assertBlocked()
            } finally {
                Files.setPosixFilePermissions(f.identityDirectory, PosixFilePermissions.fromString("rwx------"))
            }
            f.service.resetIdentity()
            assertFalse(f.authorizesPhone())
        }
    }

    @Test
    fun failedIntentClearAfterVerifiedKeyFailureStaysBlockedAcrossReload() = runTest {
        Fixture(backgroundScope).use { f ->
            f.initialize()
            f.service.startHubIfNeeded()
            f.beforeReset = {
                f.makeSettingsUnwritable()
                Files.setPosixFilePermissions(f.identityDirectory, PosixFilePermissions.fromString("r-x------"))
            }
            val failure = assertFailsWith<Exception> { f.service.resetIdentity() }
            assertContains(failure.message.orEmpty(), "The original identity remains")
            assertEquals(f.original, JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint())
            f.assertBlocked()
            f.makeSettingsWritable()
            Files.setPosixFilePermissions(f.identityDirectory, PosixFilePermissions.fromString("rwx------"))
            f.reload()
            f.assertBlocked()
        }
    }

    @Test
    fun stopAndDisableDuringSuccessfulResetWinOverRestart() = runTest {
        for (disable in listOf(false, true)) {
            Fixture(backgroundScope).use { f ->
                f.initialize()
                f.service.startHubIfNeeded()
                f.beforeReset = {
                    runBlocking { if (disable) f.service.enable(false) else f.service.stopHub() }
                }
                f.service.resetIdentity()
                assertFalse(f.service.status.value.hubListening)
                assertEquals(!disable, f.service.status.value.enabled)
                assertTrue(f.settings.homeLanSyncPeers.isEmpty())
            }
        }
    }

    @Test
    fun pendingClientResetBlocksForegroundSyncAndPairingAfterReload() = runTest {
        Fixture(backgroundScope, desktop = false).use { f ->
            f.initialize()
            f.settings = f.settings.copy(homeLanSyncRole = HomeLanSyncRole.CLIENT,
                homeLanSyncHub = PairedPeer("hub", "Hub", f.original, "192.0.2.1", pairedAtMs = 1L))
            f.failTrustCleanup()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            f.makeSettingsWritable()
            f.beforeWrite = {}
            f.reload()
            f.assertBlocked()
            assertTrue(f.service.syncNow().isFailure)
            assertEquals(0, f.clientCalls)
            assertFailsWith<IllegalStateException> {
                f.service.pairWith(DiscoveredHub(f.original.hex, "hub", "192.0.2.1", 43822, "Hub"))
            }
        }
    }

    @Test
    fun outgoingConfirmationFromBeforeResetCannotTrustTheOldHubOrReenableSync() = runTest {
        Fixture(backgroundScope, desktop = false).use { f ->
            val confirmed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.pairingClient = object : HomeLanPairingClient {
                override suspend fun start(request: PairStartRequest) = PairStartResponse("aa".repeat(16), "cc".repeat(32))
                override suspend fun reveal(request: PairRevealRequest) = Unit
                override suspend fun confirm(attemptId: String) { confirmed.complete(Unit); release.await() }
                override suspend fun cancel(attemptId: String) = Unit
                override suspend fun status(attemptId: String) = PairStatusResponse(PairState.PAIRED, hubDeviceId = "hub")
                override fun close() = Unit
            }
            f.initialize()
            val session = f.service.pairWith(DiscoveredHub("dd".repeat(32), "hub", "192.0.2.1", 43822, "Hub"))
            val confirmation = async { runCatching { session.confirm() } }
            confirmed.await()
            f.service.resetIdentity()
            f.service.enable(false)
            release.complete(Unit)
            assertTrue(confirmation.await().isFailure)
            assertNull(f.settings.homeLanSyncHub)
            assertFalse(f.settings.homeLanSyncEnabled)
            f.reload()
            assertNull(f.settings.homeLanSyncHub)
            assertFalse(f.settings.homeLanSyncEnabled)
        }
    }

    @Test
    fun incomingAttemptAndConcurrentForegroundCannotBypassDurableReset() = runTest {
        Fixture(backgroundScope).use { f ->
            f.initialize()
            f.service.startHubIfNeeded()
            val host = f.config!!.pairing
            val phonePin = CertFingerprint.parse("bb".repeat(32))!!
            val nonce = ByteArray(32) { 1 }
            val start = host.start(phonePin, PairStartRequest("new-phone", "New phone", pairingCommit(nonce).toHex()))
            val attempt = (start as PairingReply.Ok).value.attemptId
            host.reveal(phonePin, PairRevealRequest(attempt, nonce.toHex()))
            host.confirmFromPhone(phonePin, attempt)
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.afterWrite = { next ->
                if (next.homeLanSyncIdentityResetPending) { committed.complete(Unit); release.await() }
            }
            val resetting = async { f.service.resetIdentity() }
            committed.await()
            f.service.onAppForeground()
            val foreground = async { f.service.startHubIfNeeded() }
            val confirming = async { runCatching { f.service.confirmIncomingPairing(attempt).getOrThrow() } }
            runCurrent()
            assertTrue(f.config!!.peers().isEmpty())
            release.complete(Unit)
            resetting.await()
            foreground.await()
            assertTrue(confirming.await().isFailure)
            assertTrue(f.settings.homeLanSyncPeers.isEmpty())
            val late = host.start(phonePin, PairStartRequest("late-phone", "Late phone", pairingCommit(nonce).toHex()))
            val lateId = (late as PairingReply.Ok).value.attemptId
            host.reveal(phonePin, PairRevealRequest(lateId, nonce.toHex()))
            host.confirmFromPhone(phonePin, lateId)
            assertTrue(f.service.confirmIncomingPairing(lateId).isSuccess)
        }
    }

    private class Fixture(private val scope: CoroutineScope, private val desktop: Boolean = true) : AutoCloseable {
        private val directory = Files.createTempDirectory("kainos-reset-durability")
        val identityDirectory = directory.resolve("home-lan")
        var identity = JvmDeviceIdentity(identityDirectory.toFile())
        val original = identity.fingerprint()
        private val phone = PairedPeer("phone", "Phone", CertFingerprint.parse("bb".repeat(32))!!, pairedAtMs = 1L)
        private fun store(): SettingsStore = Class.forName("com.universalmusic.player.platform.FileSettingsStore")
            .getDeclaredConstructor(java.nio.file.Path::class.java).apply { isAccessible = true }
            .newInstance(directory.resolve("settings.json")) as SettingsStore
        private var store = store()
        var settings = AppSettings(homeLanSyncEnabled = true, homeLanSyncRole = HomeLanSyncRole.HUB,
            homeLanSyncDeviceId = "pc", homeLanSyncPeers = listOf(phone))
        var config: HomeLanHubConfig? = null
        var beforeWrite: suspend (AppSettings) -> Unit = {}
        var afterWrite: suspend (AppSettings) -> Unit = {}
        var beforeReset: () -> Unit = {}
        var pairingClient: HomeLanPairingClient? = null
        var clientCalls = 0
        lateinit var service: HomeLanSyncService

        suspend fun initialize() {
            store.write(settings)
            service = createService()
        }

        private fun createService() = HomeLanSyncService(scope, { settings }, { transform ->
            val next = transform(settings)
            beforeWrite(next)
            store.write(next)
            settings = next
            afterWrite(next)
        }, LibraryRepository(), KainosPlaylistRepository(),
            hubFactory = { next ->
                config = next
                object : HomeLanSyncHub {
                    override var isListening = false
                    override suspend fun start() { isListening = true }
                    override suspend fun stop() { isListening = false }
                    override fun touchActivity() = Unit
                    override fun lastActivityAtMs(): Long? = null
                }
            }, clientFactory = { clientCalls++; error("No sync client expected") }, pairingClientFactory = { _, _ -> pairingClient ?: error("No pairing client expected") },
            identity = object : DeviceIdentity by identity {
                override fun reset(): CertFingerprint { beforeReset(); return identity.reset() }
            },
            vaultStore = JvmHomeLanVaultStore({ directory.toString() }, directory.resolve("vault.json")),
            refreshLocalLibrary = {}, isDesktop = desktop,
        ).also { it.hydrateFromSettings(settings) }

        suspend fun reload() {
            service.stopHub()
            store = store()
            settings = store.read()
            identity = JvmDeviceIdentity(identityDirectory.toFile())
            config = null
            service = createService()
        }

        fun failTrustCleanup() {
            beforeWrite = { next -> if (!next.homeLanSyncIdentityResetPending) makeSettingsUnwritable() }
        }

        suspend fun assertBlocked() {
            assertTrue(settings.homeLanSyncIdentityResetPending)
            assertEquals(HomeLanTrust(), service.trust.value)
            service.startHubIfNeeded()
            service.onAppForeground()
            yield()
            assertEquals(0, clientCalls)
            assertFalse(service.status.value.hubListening)
            assertFalse(authorizesPhone())
        }

        fun authorizesPhone(): Boolean {
            if (!service.status.value.hubListening) return false
            val self = PairedPeer("pc", "PC", identity.fingerprint(), pairedAtMs = 1L)
            return identifyHubCaller(phone.fingerprint, self, config!!.peers()) is HubCaller.Paired
        }

        fun makeSettingsUnwritable() {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
            assertTrue(Files.isWritable(identityDirectory), "Key directory must remain writable")
        }

        fun makeSettingsWritable() {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }

        override fun close() {
            runBlocking { if (::service.isInitialized) service.stopHub() }
            makeSettingsWritable()
            Files.setPosixFilePermissions(identityDirectory, PosixFilePermissions.fromString("rwx------"))
            directory.toFile().deleteRecursively()
        }
    }
}
