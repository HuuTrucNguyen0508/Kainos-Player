package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.platform.DeviceIdentity
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.pinnedHomeLanHttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeLanIdentityResetTest {
    @Test
    fun failedResetRestoresTheListeningTlsIdentityAndAuthorizesThePairedPhone() = runTest {
        Fixture(backgroundScope, realHub = true).use { f ->
            f.service.startHubIfNeeded()
            assertTrue(f.service.status.value.hubListening)
            f.probePhone()
            f.makeIdentityUndeletable()
            val failure = assertFailsWith<Exception> { f.service.resetIdentity() }
            assertTrue(f.service.status.value.hubListening, "The previously listening hub must recover")
            assertEquals(listOf(f.original, f.original), f.startedPins)
            f.assertTrustPreserved()
            assertContains(failure.message.orEmpty(), "Could not reset this device's identity")
            assertContains(failure.message.orEmpty(), "Existing pairings remain")
            assertEquals(f.original, JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint())
            f.probePhone()
        }
    }

    @Test
    fun failedResetDoesNotStartAnEnabledButStoppedHub() = runTest {
        Fixture(backgroundScope).use { f ->
            f.makeIdentityUndeletable()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            assertTrue(f.startedPins.isEmpty())
            assertFalse(f.service.status.value.hubListening)
            f.assertTrustPreserved()
        }
    }

    @Test
    fun failedResetDoesNotStartADisabledHub() = runTest {
        Fixture(backgroundScope).use { f ->
            f.service.enable(false)
            f.makeIdentityUndeletable()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            assertTrue(f.startedPins.isEmpty())
            assertFalse(f.service.status.value.hubListening)
            assertFalse(f.service.status.value.enabled)
            f.assertTrustPreserved()
        }
    }

    @Test
    fun aStopDuringResetPreventsRecovery() = runTest {
        Fixture(backgroundScope).use { f ->
            f.service.startHubIfNeeded()
            f.beforeReset = { runBlocking { f.service.stopHub() } }
            f.makeIdentityUndeletable()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            assertEquals(listOf(f.original), f.startedPins)
            assertFalse(f.service.status.value.hubListening)
            f.assertTrustPreserved()
        }
    }

    @Test
    fun scopeShutdownDuringResetPreventsRecovery() = runTest {
        Fixture(backgroundScope).use { f ->
            f.service.startHubIfNeeded()
            f.beforeReset = { backgroundScope.cancel() }
            f.makeIdentityUndeletable()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            assertEquals(listOf(f.original), f.startedPins)
            assertFalse(f.service.status.value.hubListening)
            f.assertTrustPreserved()
        }
    }

    @Test
    fun disablingDuringResetPreventsRecovery() = runTest {
        Fixture(backgroundScope).use { f ->
            f.service.startHubIfNeeded()
            f.beforeReset = { runBlocking { f.service.enable(false) } }
            f.makeIdentityUndeletable()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            assertEquals(listOf(f.original), f.startedPins)
            assertFalse(f.service.status.value.hubListening)
            assertFalse(f.service.status.value.enabled)
            f.assertTrustPreserved()
        }
    }

    @Test
    fun changingToClientRoleDuringResetPreventsRecovery() = runTest {
        Fixture(backgroundScope).use { f ->
            f.service.startHubIfNeeded()
            f.beforeReset = {
                f.settings = f.settings.copy(homeLanSyncRole = HomeLanSyncRole.CLIENT)
                f.service.hydrateFromSettings(f.settings)
            }
            f.makeIdentityUndeletable()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            assertEquals(listOf(f.original), f.startedPins)
            assertFalse(f.service.status.value.hubListening)
            f.assertTrustPreserved()
        }
    }

    @Test
    fun partialDeletionDoesNotRestartTheCachedIdentityOrClaimRotation() = runTest {
        Fixture(backgroundScope).use { f ->
            f.service.startHubIfNeeded()
            val password = f.identityDirectory.resolve("device.p12.password")
            Files.delete(password)
            Files.createDirectory(password)
            Files.writeString(password.resolve("block-delete"), "test")
            val failure = assertFailsWith<Exception> { f.service.resetIdentity() }
            assertFalse(Files.exists(f.identityDirectory.resolve("device.p12")))
            assertEquals(listOf(f.original), f.startedPins)
            assertFalse(f.service.status.value.hubListening)
            f.assertTrustBlocked()
            assertContains(failure.message.orEmpty(), "Could not reset this device's identity")
            assertContains(failure.message.orEmpty(), "could not be verified")
            assertFalse(failure.message.orEmpty().contains("Existing pairings remain"))
        }
    }

    @Test
    fun aCachedFingerprintDoesNotProveTheStoredPrivateIdentitySurvived() = runTest {
        Fixture(backgroundScope).use { f ->
            f.service.startHubIfNeeded()
            Files.writeString(f.identityDirectory.resolve("device.p12.password"), "wrong password")
            f.makeIdentityUndeletable()
            val failure = assertFailsWith<Exception> { f.service.resetIdentity() }
            assertEquals(listOf(f.original), f.startedPins)
            assertFalse(f.service.status.value.hubListening)
            f.assertTrustBlocked()
            assertContains(failure.message.orEmpty(), "could not be verified")
            assertFalse(failure.message.orEmpty().contains("Existing pairings remain"))
        }
    }

    @Test
    fun anUnclassifiedResetFailureDoesNotTriggerRecovery() = runTest {
        Fixture(backgroundScope).use { f ->
            f.service.startHubIfNeeded()
            f.beforeReset = { error("No proof of persisted identity") }
            val failure = assertFailsWith<Exception> { f.service.resetIdentity() }
            assertEquals(listOf(f.original), f.startedPins)
            assertFalse(f.service.status.value.hubListening)
            f.assertTrustBlocked()
            assertContains(failure.message.orEmpty(), "could not be verified")
        }
    }

    @Test
    fun successfulResetRotatesTheListeningTlsIdentityAndRevokesThePhone() = runTest {
        Fixture(backgroundScope, realHub = true).use { f ->
            f.service.startHubIfNeeded()
            f.probePhone()
            f.service.resetIdentity()
            val rotated = f.identity.fingerprint()
            assertNotEquals(f.original, rotated)
            assertEquals(rotated, JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint())
            assertEquals(listOf(f.original, rotated), f.startedPins)
            assertTrue(f.service.status.value.hubListening)
            assertEquals(emptyList(), f.settings.homeLanSyncPeers)
            assertNull(f.settings.homeLanSyncHub)
            assertEquals(HomeLanTrust(), f.service.trust.value)
            assertNull(f.service.status.value.lastError)
            f.probePhone(pin = rotated, expectedStatus = 401)
        }
    }

    private class Fixture(scope: CoroutineScope, realHub: Boolean = false) : AutoCloseable {
        private val directory = Files.createTempDirectory("kainos-reset-recovery").toFile()
        val identityDirectory = directory.resolve("pc").toPath()
        val identity = JvmDeviceIdentity(identityDirectory.toFile())
        val original = identity.fingerprint()
        private val originalMode = Files.getPosixFilePermissions(identityDirectory)
        private val phoneIdentity = JvmDeviceIdentity(directory.resolve("phone")).material()
        private val phone = PairedPeer("phone", "Phone", phoneIdentity.fingerprint, pairedAtMs = 123L)
        private val pairedHub = PairedPeer("other-pc", "Other PC", original, pairedAtMs = 123L)
        private val port = ServerSocket(0).use { it.localPort }
        var settings = AppSettings(homeLanSyncEnabled = true, homeLanSyncRole = HomeLanSyncRole.HUB,
            homeLanSyncDeviceId = "pc", homeLanSyncPeers = listOf(phone), homeLanSyncHub = pairedHub)
        var beforeReset: () -> Unit = {}
        val startedPins = mutableListOf<CertFingerprint>()
        val service = HomeLanSyncService(scope, { settings }, { settings = it(settings) },
            LibraryRepository(), KainosPlaylistRepository(),
            hubFactory = { config ->
                val material = identity.material()
                val delegate = if (realHub) DesktopHomeLanSyncHub(config, material, bindHost = "127.0.0.1") else null
                object : HomeLanSyncHub {
                    override var isListening = false
                    override suspend fun start() {
                        delegate?.start()
                        startedPins += material.fingerprint
                        isListening = true
                    }
                    override suspend fun stop() { delegate?.stop(); isListening = false }
                    override fun touchActivity() = Unit
                    override fun lastActivityAtMs(): Long? = null
                }
            },
            clientFactory = { error("No sync client needed") }, pairingClientFactory = noPairingClient(),
            identity = object : DeviceIdentity by identity {
                override fun reset(): CertFingerprint { beforeReset(); return identity.reset() }
            },
            vaultStore = JvmHomeLanVaultStore({ directory.toString() }, directory.resolve("vault-state.json").toPath()),
            refreshLocalLibrary = {}, isDesktop = true, hubPort = port,
        ).also { it.hydrateFromSettings(settings) }

        fun makeIdentityUndeletable() {
            Files.setPosixFilePermissions(identityDirectory, PosixFilePermissions.fromString("r-x------"))
        }

        fun assertTrustPreserved() {
            assertEquals(listOf(phone), settings.homeLanSyncPeers)
            assertEquals(pairedHub, settings.homeLanSyncHub)
            assertEquals(listOf(phone), service.trust.value.peers)
            assertFalse(service.status.value.lastDetail.orEmpty().contains("Created a new identity"))
        }

        suspend fun assertTrustBlocked() {
            service.startHubIfNeeded()
            assertFalse(service.status.value.hubListening)
            assertEquals(listOf(phone), settings.homeLanSyncPeers)
            assertTrue(settings.homeLanSyncIdentityResetPending)
            assertEquals(HomeLanTrust(), service.trust.value)
            assertFalse(service.status.value.lastDetail.orEmpty().contains("Created a new identity"))
        }

        suspend fun probePhone(pin: CertFingerprint = original, expectedStatus: Int = 200) {
            pinnedHomeLanHttpClient(pin, phoneIdentity.keyManagers()).use { http ->
                val response = http.get("https://127.0.0.1:$port$HOME_LAN_SYNC_PATH_HEALTH") { expectSuccess = false }
                assertEquals(expectedStatus, response.status.value)
            }
        }

        override fun close() {
            runBlocking { service.stopHub() }
            Files.setPosixFilePermissions(identityDirectory, originalMode)
            directory.deleteRecursively()
        }
    }
}
