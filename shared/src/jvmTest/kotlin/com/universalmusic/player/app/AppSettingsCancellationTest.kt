package com.universalmusic.player.app

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.data.sync.CertFingerprint
import com.universalmusic.player.data.sync.HomeLanSyncHub
import com.universalmusic.player.data.sync.HomeLanSyncRole
import com.universalmusic.player.data.sync.HomeLanSyncService
import com.universalmusic.player.data.sync.JvmHomeLanVaultStore
import com.universalmusic.player.platform.DeviceIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsCancellationTest {
    @Test
    fun desktopReadyStartsTheHomeSyncHub() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler), initiallyEnabled = true).use { f ->
            f.initialize()
            val foreground = AppForegroundState()
            foreground.onReady(f.service::onAppForeground)
            runCurrent()
            assertTrue(f.hub.isListening, "Desktop readiness must start the enabled hub")
            assertTrue(f.service.status.value.hubListening)
            assertTrue(foreground.isVisible)
        }
    }

    @Test
    fun androidReadyWaitsForTheActivityForegroundEvent() {
        val foreground = AppForegroundState(platform = "Android")
        var foregroundEvents = 0
        foreground.onReady { foregroundEvents++ }
        assertEquals(0, foregroundEvents)
        assertFalse(foreground.isVisible)
        foreground.isVisible = true
        foreground.onReady { foregroundEvents++ }
        assertEquals(1, foregroundEvents)
    }

    @Test
    fun committedPreferenceSurvivesCallerCancellationAndAnUnrelatedWrite() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { f ->
            f.initialize()
            lateinit var caller: Job
            f.store.afterCommit = { caller.cancel() }
            caller = launch { f.updater.update { it.copy(normalizeVolume = true) } }
            runCurrent()
            caller.join()
            assertTrue(f.saved().normalizeVolume)
            val publishedAfterCommit = f.state.value.normalizeVolume
            val playbackAfterCommit = f.playbackSettings.normalizeVolume
            f.store.afterCommit = {}
            f.updater.update { it.copy(compactMode = true) }
            assertEquals(listOf(true, true, true), listOf(publishedAfterCommit, playbackAfterCommit, f.saved().normalizeVolume),
                "Committed preference must be published and survive an unrelated write")
            assertTrue(f.saved().compactMode)
        }
    }

    @Test
    fun committedEnableFinishesRuntimeTransitionDespiteCallerCancellation() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { f ->
            f.initialize()
            lateinit var caller: Job
            f.store.afterCommit = { caller.cancel() }
            caller = launch { f.service.enable(true) }
            runCurrent()
            caller.join()
            f.assertEnabled(true)
            f.store.afterCommit = {}
            f.updater.update { it.copy(compactMode = true) }
            f.assertEnabled(true)
            assertTrue(f.saved().compactMode)
        }
    }

    @Test
    fun committedDisableStopsHubAndForegroundCannotReviveItAfterCancellation() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler), initiallyEnabled = true).use { f ->
            f.initialize()
            f.service.startHubIfNeeded()
            assertTrue(f.hub.isListening)
            lateinit var caller: Job
            f.store.afterCommit = { caller.cancel() }
            caller = launch { f.service.enable(false) }
            runCurrent()
            caller.join()
            f.assertEnabled(false)
            f.service.onAppForeground()
            runCurrent()
            f.assertEnabled(false)
            f.store.afterCommit = {}
            f.updater.update { it.copy(compactMode = true) }
            f.assertEnabled(false)
            assertTrue(f.saved().compactMode)
        }
    }

    @Test
    fun normalEnableAndDisableKeepDiskSettingsAndListenerTogether() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { f ->
            f.initialize()
            f.service.enable(true)
            f.assertEnabled(true)
            f.service.enable(false)
            f.assertEnabled(false)
        }
    }

    @Test
    fun failedWriteDoesNotPublishOrDisableTheListener() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler), initiallyEnabled = true).use { f ->
            f.initialize()
            f.service.startHubIfNeeded()
            f.makeSettingsUnwritable()
            assertFailsWith<IOException> { f.service.enable(false) }
            f.assertEnabled(true)
        }
    }

    @Test
    fun cancellationWhileWaitingForSettingsLockRejectsUnacceptedMutation() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { f ->
            f.initialize()
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.store.beforeCommit = { writing.complete(Unit); release.await() }
            val accepted = async { f.updater.update { it.copy(compactMode = true) } }
            writing.await()
            var transformed = false
            val cancelled = launch { f.updater.update { transformed = true; it.copy(normalizeVolume = true) } }
            runCurrent()
            cancelled.cancel()
            release.complete(Unit)
            accepted.await()
            runCurrent()
            cancelled.join()
            assertFalse(transformed)
            assertFalse(f.saved().normalizeVolume)
            assertFalse(f.state.value.normalizeVolume)
            assertTrue(f.saved().compactMode)
        }
    }

    @Test
    fun cancellationWhileWaitingForLifecycleLockDoesNotApplyAnObsoleteDisable() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { f ->
            f.initialize()
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.store.beforeCommit = { writing.complete(Unit); release.await() }
            val enable = async { f.service.enable(true) }
            writing.await()
            val cancelledDisable = launch { f.service.enable(false) }
            runCurrent()
            cancelledDisable.cancel()
            release.complete(Unit)
            enable.await()
            runCurrent()
            cancelledDisable.join()
            f.assertEnabled(true)
        }
    }

    @Test
    fun committedSettingsAndPlaybackPublishBeforeProviderNetworkFinishes() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { f ->
            f.initialize()
            val providerStarted = CompletableDeferred<Unit>()
            val providerFinished = CompletableDeferred<Unit>()
            f.applyProvider = { providerStarted.complete(Unit); providerFinished.await() }
            val update = launch { f.updater.update { it.copy(youtubeDataApiKey = "new-key", normalizeVolume = true) } }
            providerStarted.await()
            assertEquals("new-key", f.saved().youtubeDataApiKey)
            assertEquals("new-key", f.state.value.youtubeDataApiKey)
            assertTrue(f.playbackSettings.normalizeVolume)
            update.cancel()
            providerFinished.complete(Unit)
            runCurrent()
            update.join()
        }
    }

    @Test
    fun cancellingAfterResetIntentCommitPublishesPendingBeforeResetReturns() = runTest {
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler), initiallyEnabled = true).use { f ->
            f.initialize()
            f.service.startHubIfNeeded()
            lateinit var caller: Job
            f.store.afterCommit = { caller.cancel() }
            caller = launch { f.service.resetIdentity() }
            runCurrent()
            caller.join()
            assertTrue(f.saved().homeLanSyncIdentityResetPending)
            assertTrue(f.state.value.homeLanSyncIdentityResetPending)
            assertEquals(0, f.identityResets)
            assertFalse(f.service.status.value.hubListening)
            f.service.startHubIfNeeded()
            assertFalse(f.hub.isListening)
        }
    }

    private class Fixture(scope: CoroutineScope, io: CoroutineDispatcher, initiallyEnabled: Boolean = false) : AutoCloseable {
        private val directory = Files.createTempDirectory("kainos-settings-cancellation")
        private val disk = Class.forName("com.universalmusic.player.platform.FileSettingsStore")
            .getDeclaredConstructor(Path::class.java).apply { isAccessible = true }
            .newInstance(directory.resolve("settings.json")) as SettingsStore
        val store = DispatchingStore(disk, io)
        val state = MutableStateFlow(AppSettings(homeLanSyncEnabled = initiallyEnabled,
            homeLanSyncRole = HomeLanSyncRole.HUB, homeLanSyncDeviceId = "pc"))
        var playbackSettings = state.value.toPlaybackPreferences()
        var applyProvider: suspend (AppSettings) -> Unit = {}
        val updater = AppSettingsUpdater(scope, store, state, { applyProvider(it) }, { playbackSettings = it.toPlaybackPreferences() })
        var identityResets = 0
        val hub = object : HomeLanSyncHub {
            override var isListening = false
            override suspend fun start() { yield(); isListening = true }
            override suspend fun stop() { yield(); isListening = false }
            override fun touchActivity() = Unit
            override fun lastActivityAtMs(): Long? = null
        }
        val service = HomeLanSyncService(scope, { state.value }, updater::update,
            LibraryRepository(), KainosPlaylistRepository(), hubFactory = { hub },
            clientFactory = { error("No sync client expected") }, pairingClientFactory = { _, _ -> error("No pairing client expected") },
            identity = object : DeviceIdentity {
                override fun fingerprint() = CertFingerprint.parse("aa".repeat(32))!!
                override fun reset(): CertFingerprint { identityResets++; return fingerprint() }
            },
            vaultStore = JvmHomeLanVaultStore({ directory.toString() }, directory.resolve("vault.json")),
            refreshLocalLibrary = {}, isDesktop = true)

        suspend fun initialize() { disk.write(state.value); service.hydrateFromSettings(state.value) }
        suspend fun saved() = disk.read()

        suspend fun assertEnabled(expected: Boolean) {
            assertEquals(expected, saved().homeLanSyncEnabled, "Saved enabled state")
            assertEquals(expected, state.value.homeLanSyncEnabled, "Published enabled state")
            assertEquals(expected, service.status.value.enabled, "Service enabled state")
            assertEquals(expected, service.status.value.hubListening, "Service listening state")
            assertEquals(expected, hub.isListening, "Actual listener state")
        }

        fun makeSettingsUnwritable() {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
        }

        override fun close() {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            directory.toFile().deleteRecursively()
        }
    }

    private class DispatchingStore(private val disk: SettingsStore, private val io: CoroutineDispatcher) : SettingsStore {
        var beforeCommit: suspend () -> Unit = {}
        var afterCommit: () -> Unit = {}
        override suspend fun read() = disk.read()
        override suspend fun write(settings: AppSettings) = withContext(io) {
            beforeCommit()
            disk.write(settings)
            afterCommit()
        }
    }
}
