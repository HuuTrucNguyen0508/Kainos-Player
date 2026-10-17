package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.platform.DesktopIsolation
import com.universalmusic.player.platform.HubUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HubLifecycleRegressionTest {
    @Test
    fun legacyDesktopStartupTransfersListenerAndLockToTheEnabledUnit() = runBlocking {
        fixture { f ->
            f.enabled = false
            val legacy = f.config.resolve("autostart/kainos-home-sync-hub.desktop")
            Files.createDirectories(legacy.parent)
            Files.writeString(legacy, "[Desktop Entry]\nExec=/tmp/kainos-player --hub-only\n")
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.HUB_ONLY)
            assertTrue(owner.acquire())
            owner.startHub { f.events += "listen unmanaged" }
            f.service.configure(true)
            owner.close { f.events += "stop listener" }
            assertFalse(Files.exists(legacy))
            assertTrue(f.managedStarted, "Legacy startup must leave a supervised hub running")
            assertEquals(listOf(
                "listen unmanaged",
                "systemctl --user daemon-reload",
                "systemctl --user enable kainos-hub.service",
                "stop listener",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user start --no-block kainos-hub.service",
            ), f.events)
        }
    }

    @Test
    fun acceptedStopThatTimesOutIsRecoveredDuringCleanup() = runBlocking {
        fixture { f ->
            f.onStop = { error("systemctl timed out after accepting stop") }
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            try {
                assertFailsWith<IllegalStateException> { owner.acquire() }
            } finally {
                owner.close {}
            }
            assertTrue(f.managedStarted, "A possibly accepted stop must be recovered")
            assertEquals(listOf(
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user stop kainos-hub.service",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user start --no-block kainos-hub.service",
            ), f.events)
        }
    }

    @Test
    fun lockOpenFailureAfterStopIsRecoveredDuringCleanup() = runBlocking {
        fixture { f ->
            f.onStop = { Files.writeString(f.isolation.dataDir, "not a directory"); Unit }
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            try {
                assertFailsWith<java.nio.file.FileSystemException> { owner.acquire() }
            } finally {
                Files.deleteIfExists(f.isolation.dataDir)
                owner.close {}
            }
            assertTrue(f.managedStarted, "A stopped unit must be recovered after lock-open failure")
            assertEquals(listOf(
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user stop kainos-hub.service",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user start --no-block kainos-hub.service",
            ), f.events)
        }
    }

    @Test
    fun failedEnableReconcilesPersistedSettingAndVisibleError() = runTest {
        fixture { f -> assertSettingsFailure(f, backgroundScope, requested = true, timeout = false) }
    }

    @Test
    fun timedOutEnableReconcilesPersistedSettingAndVisibleError() = runTest {
        fixture { f -> assertSettingsFailure(f, backgroundScope, requested = true, timeout = true) }
    }

    @Test
    fun failedDisableReconcilesPersistedSettingAndVisibleError() = runTest {
        fixture { f -> assertSettingsFailure(f, backgroundScope, requested = false, timeout = false) }
    }

    @Test
    fun timedOutDisableReconcilesPersistedSettingAndVisibleError() = runTest {
        fixture { f -> assertSettingsFailure(f, backgroundScope, requested = false, timeout = true) }
    }

    private suspend fun assertSettingsFailure(f: Fixture, scope: CoroutineScope, requested: Boolean, timeout: Boolean) {
        f.enabled = !requested
        f.failureVerb = if (requested) "enable" else "disable"
        f.timeout = timeout
        var persisted = AppSettings(homeLanSyncHubAutostart = !requested)
        val service = HomeLanSyncService(
            scope = scope,
            settings = { persisted },
            updateSettings = { transform -> persisted = transform(persisted) },
            library = LibraryRepository(),
            playlists = KainosPlaylistRepository(),
            hubFactory = { error("No listener in this test") },
            clientFactory = { error("No client in this test") },
            pairingClientFactory = noPairingClient(),
            identity = FixedDeviceIdentity("aa".repeat(32)),
            vaultStore = tempVaultStore().second,
            refreshLocalLibrary = {},
            configureHubAutostart = f.service::configure,
        )
        service.hydrateFromSettings(persisted)
        val result = runCatching { service.setHubAutostartEnabled(requested) }
        assertTrue(result.isSuccess, "Configuration failure must not abort app initialization: ${result.exceptionOrNull()}")
        assertEquals(!requested, persisted.homeLanSyncHubAutostart)
        assertNotNull(service.status.value.lastError, "Settings must show configuration failure")
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("kainos-hub-regression-")
        try { block(Fixture(directory)) }
        finally { directory.toFile().deleteRecursively() }
    }

    private class Fixture(directory: Path) {
        val isolation = DesktopIsolation(directory.resolve("data"), 43822, null)
        val config: Path = directory.resolve("config")
        val events = mutableListOf<String>()
        var enabled = true
        var managedStarted = false
        var onStop: () -> Unit = {}
        var failureVerb: String? = null
        var timeout = false
        val service = HubUserService(isolation, config, { "/tmp/kainos-player" }) { args ->
            events += args.joinToString(" ")
            when {
                args.contains("is-enabled") -> if (enabled) 0 else 1
                failureVerb != null && args.contains(failureVerb) -> {
                    if (timeout) error("systemctl timed out")
                    1
                }
                args.contains("enable") -> { enabled = true; 0 }
                args.contains("disable") -> { enabled = false; 0 }
                args.contains("stop") -> { onStop(); 0 }
                args.contains("start") -> {
                    assertNotNull(HubOwnerLock.acquire(isolation.dataDir, 0), "Lock must be free before start").close()
                    managedStarted = true
                    0
                }
                else -> 0
            }
        }
    }
}
