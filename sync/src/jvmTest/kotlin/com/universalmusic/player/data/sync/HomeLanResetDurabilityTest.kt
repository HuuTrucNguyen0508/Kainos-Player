package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeLanResetDurabilityTest {
    @Test
    fun failedSettingsWriteCannotLeaveNewIdentityAuthorizingOldPeerAfterReload() = runTest {
        Fixture(backgroundScope).use { f ->
            f.initialize()
            f.service.startHubIfNeeded()
            f.makeSettingsUnwritable()
            assertFailsWith<Exception> { f.service.resetIdentity() }
            f.makeSettingsWritable()
            val rotated = JvmDeviceIdentity(f.identityDirectory.toFile()).fingerprint() != f.original
            f.service.startHubIfNeeded()
            val liveAuthorized = f.authorizesPhone()
            f.reload()
            f.service.startHubIfNeeded()
            val reloadAuthorized = f.authorizesPhone()
            assertFalse(rotated && (liveAuthorized || reloadAuthorized),
                "New persisted identity must not authorize old trust: rotated=$rotated live=$liveAuthorized reload=$reloadAuthorized")
        }
    }

    private class Fixture(private val scope: CoroutineScope) : AutoCloseable {
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
        private var config: HomeLanHubConfig? = null
        lateinit var service: HomeLanSyncService

        suspend fun initialize() {
            store.write(settings)
            service = createService()
        }

        private fun createService() = HomeLanSyncService(scope, { settings }, { transform ->
            val next = transform(settings)
            store.write(next)
            settings = next
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
            }, clientFactory = { error("No sync client expected") }, pairingClientFactory = noPairingClient(),
            identity = identity,
            vaultStore = JvmHomeLanVaultStore({ directory.toString() }, directory.resolve("vault.json")),
            refreshLocalLibrary = {}, isDesktop = true,
        ).also { it.hydrateFromSettings(settings) }

        suspend fun reload() {
            service.stopHub()
            store = store()
            settings = store.read()
            identity = JvmDeviceIdentity(identityDirectory.toFile())
            config = null
            service = createService()
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
            directory.toFile().deleteRecursively()
        }
    }
}
