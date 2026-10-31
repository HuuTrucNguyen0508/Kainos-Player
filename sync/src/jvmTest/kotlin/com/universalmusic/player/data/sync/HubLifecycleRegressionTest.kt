package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.platform.DesktopIsolation
import com.universalmusic.player.platform.HubUserService
import com.universalmusic.player.platform.HubSystemctlResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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
            assertTrue(owner.handoffUnmanagedHub { f.events += "stop listener" })
            owner.close { error("Handoff cleanup must run once") }
            val unit = Files.readString(f.config.resolve("systemd/user/kainos-hub.service"))
            val marker = unit.lineSequence().single { it.startsWith("Environment=\"KAINOS_HUB_UNIT=") }
                .substringAfter("KAINOS_HUB_UNIT=").removeSuffix("\"")
            val supervised = HubOwnership(f.isolation, f.service, f.service.processMode(true, marker))
            assertTrue(supervised.acquire())
            assertFalse(supervised.handoffUnmanagedHub { error("Must not relaunch supervised startup") })
            supervised.startHub { f.events += "listen managed" }
            supervised.close { f.events += "stop managed" }
            assertFalse(Files.exists(legacy))
            assertTrue(f.managedStarted, "Legacy startup must leave a supervised hub running")
            assertEquals(listOf(
                "listen unmanaged",
                "systemctl --user daemon-reload",
                "systemctl --user enable kainos-hub.service",
                "systemctl --user is-enabled kainos-hub.service",
                "stop listener",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user start --no-block kainos-hub.service",
                "listen managed", "stop managed",
            ), f.events)
        }
    }

    @Test
    fun acceptedStopRetainsRecoveryWhileTheServiceStillHoldsTheLock() = runTest {
        fixture { f ->
            val serviceLock = assertNotNull(HubOwnerLock.acquire(f.isolation.dataDir, 0))
            var stopping = true
            var successorLock: HubOwnerLock? = null
            f.onStop = { error("systemctl timed out after accepting stop") }
            f.onShow = {
                if (stopping) "ActiveState=deactivating\nJob=41\n" else "ActiveState=inactive\nJob=\n"
            }
            f.onStart = {
                successorLock = assertNotNull(HubOwnerLock.acquire(f.isolation.dataDir, 0))
                f.events += "listen supervised"
            }
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertFailsWith<IllegalStateException> { owner.acquire() }
            val closing = async { owner.close { error("GUI never owned a listener") } }
            try {
                runCurrent()
                assertFalse(closing.isCompleted, "Cleanup must retain recovery until the accepted stop completes")
                assertNull(HubOwnerLock.acquire(f.isolation.dataDir, 0))
                assertFalse(f.managedStarted)
                serviceLock.close()
                f.events += "service released lock"
                stopping = false
                advanceUntilIdle()
                closing.await()
                owner.close { error("Recovery must be idempotent") }
                assertTrue(f.managedStarted, "Delayed stop must leave a supervised listener")
                assertNull(HubOwnerLock.acquire(f.isolation.dataDir, 0))
                assertTrue(f.events.indexOf("service released lock") < f.events.indexOf("listen supervised"))
                assertEquals(1, f.events.count { it.contains("start --no-block") })
            } finally {
                serviceLock.close()
                stopping = false
                advanceUntilIdle()
                closing.await()
                successorLock?.close()
            }
        }
    }

    @Test
    fun recoveryWaitsForAQueuedStopEvenBeforeTheUnitStartsDeactivating() = runTest {
        fixture { f ->
            var pending = true
            f.onStop = { error("systemctl timed out after accepting stop") }
            f.onShow = {
                if (pending) "ActiveState=active\nJob=42\n" else "ActiveState=inactive\nJob=\n"
            }
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertFailsWith<IllegalStateException> { owner.acquire() }
            val closing = async { owner.close {} }
            runCurrent()
            try {
                assertFalse(closing.isCompleted, "A queued stop must settle before recovery")
                assertFalse(f.managedStarted)
            } finally {
                pending = false
                advanceUntilIdle()
                closing.await()
            }
            assertTrue(f.managedStarted)
        }
    }

    @Test
    fun disabledAutostartDuringPendingStopDoesNotStartASuccessor() = runTest {
        fixture { f ->
            var pending = true
            f.onStop = { error("systemctl timed out after accepting stop") }
            f.onShow = {
                if (pending) "ActiveState=deactivating\nJob=43\n" else "ActiveState=inactive\nJob=\n"
            }
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertFailsWith<IllegalStateException> { owner.acquire() }
            val closing = async { owner.close {} }
            runCurrent()
            try {
                assertFalse(closing.isCompleted)
                f.enabled = false
            } finally {
                pending = false
                advanceUntilIdle()
                closing.await()
            }
            assertFalse(f.managedStarted)
        }
    }

    @Test
    fun invalidManagerSnapshotPreservesRecoveryUntilItCanBeObserved() = runBlocking {
        fixture { f ->
            f.onStop = { error("systemctl timed out after accepting stop") }
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertFailsWith<IllegalStateException> { owner.acquire() }
            f.failureVerb = "show"
            assertFailsWith<IllegalStateException> { owner.close {} }
            f.failureVerb = null
            for (output in listOf("", "ActiveState=inactive\n", "ActiveState=inactive\nJob=invalid\n",
                "ActiveState=unrecognized\nJob=\n")) {
                f.onShow = { output }
                assertFailsWith<IllegalStateException> { owner.close {} }
                assertFalse(f.managedStarted)
            }
            f.onShow = { "ActiveState=failed\nJob=\n" }
            owner.close {}
            assertTrue(f.managedStarted)
        }
    }

    @Test
    fun failedRecoveryQueryCanBeRetriedWithoutLosingTheStopRequest() = runBlocking {
        fixture { f ->
            f.onStop = { error("systemctl timed out after accepting stop") }
            f.onShow = { error("systemctl show timed out") }
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertFailsWith<IllegalStateException> { owner.acquire() }
            assertFailsWith<IllegalStateException> { owner.close {} }
            assertFalse(f.managedStarted)
            f.onShow = { "ActiveState=inactive\nJob=\n" }
            owner.close {}
            owner.close { error("Recovery must run once") }
            assertTrue(f.managedStarted)
            assertEquals(1, f.events.count { it.contains("start --no-block") })
        }
    }

    @Test
    fun failedRestartCanBeRetriedWithoutStoppingTheGuiListenerTwice() = runBlocking {
        fixture { f ->
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertTrue(owner.acquire())
            var stops = 0
            f.failureVerb = "start"
            assertFailsWith<IllegalStateException> { owner.close { stops++ } }
            f.failureVerb = null
            owner.close { error("Listener was already stopped") }
            owner.close { error("Cleanup must be idempotent") }
            assertEquals(1, stops)
            assertTrue(f.managedStarted)
        }
    }

    @Test
    fun restartRetryYieldsToAGuiThatAcquiredTheReleasedLock() = runBlocking {
        fixture { f ->
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertTrue(owner.acquire())
            f.failureVerb = "start"
            assertFailsWith<IllegalStateException> { owner.close {} }
            f.failureVerb = null
            assertNotNull(HubOwnerLock.acquire(f.isolation.dataDir, 0)).use {
                owner.close { error("The previous listener already stopped") }
                assertFalse(f.managedStarted, "Retry must respect the new GUI owner")
            }
        }
    }

    @Test
    fun anUnacceptedStopDoesNotRestartAnAlreadyRunningService() = runBlocking {
        fixture { f ->
            f.onStop = { error("systemctl failed before accepting stop") }
            f.onShow = { "ActiveState=active\nJob=\n" }
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertFailsWith<IllegalStateException> { owner.acquire() }
            owner.close {}
            assertFalse(f.managedStarted, "The original service is still running")
        }
    }

    @Test
    fun javaSigtermExitDuringGuiTakeoverIsAnExplicitUnitSuccess() = runBlocking {
        fixture { f ->
            f.service.configure(true)
            val ready = f.config.resolve("child-ready")
            val childOutput = f.config.resolve("child-output.log")
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), HubLockChild::class.java.name,
                f.isolation.dataDir.toString(), "0", ready.toString(),
            ).redirectErrorStream(true).redirectOutput(childOutput.toFile()).start()
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (!Files.exists(ready) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(Files.exists(ready),
                    "Child service must own the real hub lock; alive=${process.isAlive}; " +
                        "output=${Files.readString(childOutput)}")
                f.onStop = {
                    // Process.destroy() also closes stdin, letting HubLockChild exit 0 on EOF
                    // before Java handles SIGTERM. systemctl sends a signal without closing pipes.
                    assertTrue(process.toHandle().destroy(), "Child must accept SIGTERM")
                    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Child must exit after SIGTERM")
                }
                assertTrue(owner.acquire(), "GUI must acquire the stopped child's hub lock")
                assertEquals(143, process.exitValue(), "Java exits with 128 plus SIGTERM")
                val unit = Files.readString(f.config.resolve("systemd/user/kainos-hub.service"))
                val success = unit.lineSequence().filter { it.startsWith("SuccessExitStatus=") }.toList()
                assertEquals(listOf("SuccessExitStatus=143"), success,
                    "Only Java SIGTERM exit is added to the unit success policy")
                assertTrue(unit.lineSequence().any { it == "Restart=on-failure" })
                assertFalse(unit.contains("RestartPreventExitStatus="))
                assertFalse(unit.contains("RestartForceExitStatus="))
            } finally {
                owner.close {}
                if (process.isAlive) process.destroyForcibly()
                assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            }
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
                "systemctl --user show --all --property=ActiveState --property=Job kainos-hub.service",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user start --no-block kainos-hub.service",
            ), f.events)
        }
    }

    @Test
    fun lockOpenFailureAfterStopIsRecoveredDuringCleanup() = runBlocking {
        fixture { f ->
            f.onStop = { Files.writeString(f.isolation.dataDir, "not a directory") }
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
                "systemctl --user show --all --property=ActiveState --property=Job kainos-hub.service",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user start --no-block kainos-hub.service",
            ), f.events)
        }
    }

    @Test
    fun lockOpenErrorDuringRecoveryStillRequestsASupervisedSuccessor() = runBlocking {
        fixture { f ->
            var started = false
            val service = HubUserService(f.isolation, f.config, { "/tmp/kainos-player" }) { args ->
                when {
                    args.contains("is-enabled") -> HubSystemctlResult(0, "enabled")
                    args.contains("stop") -> {
                        Files.writeString(f.isolation.dataDir, "not a directory")
                        HubSystemctlResult(0)
                    }
                    args.contains("show") -> HubSystemctlResult(0, "ActiveState=inactive\nJob=\n")
                    args.contains("start") -> {
                        Files.delete(f.isolation.dataDir)
                        assertNotNull(HubOwnerLock.acquire(f.isolation.dataDir, 0)).close()
                        started = true
                        HubSystemctlResult(0)
                    }
                    else -> error("Unexpected command $args")
                }
            }
            val owner = HubOwnership(f.isolation, service, HubProcessMode.GUI)
            assertFailsWith<java.nio.file.FileSystemException> { owner.acquire() }
            owner.close {}
            owner.close { error("Cleanup must be idempotent") }
            assertTrue(started)
        }
    }

    @Test
    fun failedAcquisitionDoesNotRestartServiceIntoAnotherGuiOwner() = runBlocking {
        fixture { f ->
            val other = assertNotNull(HubOwnerLock.acquire(f.isolation.dataDir, 0))
            try {
                f.onStop = { error("systemctl timed out") }
                val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
                try { assertFailsWith<IllegalStateException> { owner.acquire() } }
                finally { owner.close {} }
                assertFalse(f.managedStarted)
                assertEquals(listOf(
                    "systemctl --user is-enabled kainos-hub.service",
                    "systemctl --user stop kainos-hub.service",
                    "systemctl --user show --all --property=ActiveState --property=Job kainos-hub.service",
                    "systemctl --user is-enabled kainos-hub.service",
                ), f.events)
            } finally { other.close() }
        }
    }

    @Test
    fun concurrentShutdownAndFinallyWaitForOneCleanup() = runTest {
        fixture { f ->
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertTrue(owner.acquire())
            val stopping = CompletableDeferred<Unit>()
            val mayStop = CompletableDeferred<Unit>()
            val shutdown = async { owner.close { stopping.complete(Unit); mayStop.await(); f.events += "stopped" } }
            stopping.await()
            val finalizing = async { owner.close { error("Stop must run once") } }
            runCurrent()
            assertFalse(finalizing.isCompleted)
            mayStop.complete(Unit)
            shutdown.await()
            finalizing.await()
            assertEquals(1, f.events.count { it == "stopped" })
            assertEquals(1, f.events.count { it == "systemctl --user start --no-block kainos-hub.service" })
        }
    }

    @Test
    fun cleanupDoesNotHoldOwnershipMutexWhileWaitingForServiceStop() = runTest {
        fixture { f ->
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertTrue(owner.acquire())
            val serviceMutex = Mutex()
            val mayStart = CompletableDeferred<Unit>()
            val starting = async {
                serviceMutex.withLock {
                    mayStart.await()
                    runCatching { owner.startHub { error("Closing owner must reject late start") } }
                }
            }
            runCurrent()
            val closing = async { owner.close { serviceMutex.withLock {} } }
            runCurrent()
            mayStart.complete(Unit)
            runCurrent()
            try {
                assertTrue(closing.isCompleted, "Stop must not invert the service/start lock order")
                assertTrue(starting.await().isFailure)
            } finally {
                starting.cancel()
                closing.await()
            }
        }
    }

    @Test
    fun supervisedStartupAndWrongInstanceMarkersNeverLoopHandoff() = runBlocking {
        fixture { f ->
            for (instance in listOf(null, "3", "4")) {
                val isolation = f.isolation.copy(instance = instance)
                val service = HubUserService(isolation, f.config, { "/tmp/kainos-player" }) { HubSystemctlResult(0) }
                val unit = instance?.let { "kainos-hub-$it.service" } ?: "kainos-hub.service"
                service.configure(true)
                assertTrue(Files.readString(f.config.resolve("systemd/user/$unit"))
                    .contains("Environment=\"KAINOS_HUB_UNIT=$unit\""))
                assertEquals(HubProcessMode.HUB_ONLY, service.processMode(true, null))
                assertEquals(HubProcessMode.HUB_ONLY, service.processMode(true, "kainos-hub-unrelated.service"))
                assertEquals(HubProcessMode.GUI, service.processMode(false, unit))
                val owner = HubOwnership(isolation, service, service.processMode(true, unit))
                assertTrue(owner.acquire())
                assertFalse(owner.handoffUnmanagedHub { error("Supervised process must stay") })
                owner.close {}
            }
        }
    }

    @Test
    fun unavailableManagerIsNotReportedAsDisabledAutostart() = runBlocking {
        fixture { f ->
            val service = HubUserService(f.isolation, f.config, { "/tmp/kainos-player" }) { HubSystemctlResult(1) }
            assertFailsWith<IllegalStateException> { service.isEnabled() }
        }
    }

    @Test
    fun guiStillAcquiresItsLockWhenSystemdIsUnavailable() = runBlocking {
        fixture { f ->
            val service = HubUserService(f.isolation, f.config, { "/tmp/kainos-player" }) { HubSystemctlResult(-1) }
            val owner = HubOwnership(f.isolation, service, HubProcessMode.GUI)
            assertTrue(owner.acquire())
            owner.startHub { f.events += "listen without systemd" }
            owner.close { f.events += "stop listener" }
            assertEquals(listOf("listen without systemd", "stop listener"), f.events)
            assertNotNull(HubOwnerLock.acquire(f.isolation.dataDir, 0)).close()
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

    @Test
    fun acceptedEnableTimeoutPersistsTheEnabledStateAndReportsFailure() = runTest {
        fixture { f -> assertSettingsFailure(f, backgroundScope, requested = true, timeout = true, accepted = true) }
    }

    @Test
    fun acceptedDisableTimeoutPersistsTheDisabledStateAndReportsFailure() = runTest {
        fixture { f -> assertSettingsFailure(f, backgroundScope, requested = false, timeout = true, accepted = true) }
    }

    @Test
    fun successfulCommandThatDidNotEnableAutostartStillReportsFailure() = runTest {
        fixture { f -> assertSettingsFailure(f, backgroundScope, requested = true, timeout = false, noChange = true) }
    }

    @Test
    fun generatedDefaultUnitChildPreservesEnabledAutostartAtBootstrap() = runTest {
        fixture { f ->
            f.service.configure(true)
            val unit = f.config.resolve("systemd/user/kainos-hub.service")
            val body = Files.readString(unit)
            val environment = body.lineSequence().filter { it.startsWith("Environment=") }
                .map { it.removePrefix("Environment=").removeSurrounding("\"") }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals(setOf("KAINOS_DATA_DIR", "KAINOS_HUB_PORT", "KAINOS_HUB_UNIT"), environment.keys)
            val childIsolation = DesktopIsolation.parse(environment, f.config.resolve("child-home"))
            assertEquals(f.isolation.dataDir, childIsolation.dataDir)
            assertEquals(f.isolation.hubPort, childIsolation.hubPort)
            f.events.clear()
            val childService = HubUserService(childIsolation, f.config, { error("No launcher lookup") }) {
                f.events += it.joinToString(" ")
                error("No protected unit operations")
            }
            assertEquals(HubProcessMode.SUPERVISED_HUB_ONLY,
                childService.processMode(true, environment["KAINOS_HUB_UNIT"]))
            assertUnavailableAutostartPreservesSettings(f, backgroundScope, childService, saved = true, requested = true)
            assertEquals(body, Files.readString(unit))
        }
    }

    @Test
    fun portOnlyDefaultProfilePreservesEnabledAutostartAtBootstrap() = runTest {
        fixture { f ->
            val isolation = DesktopIsolation.parse(mapOf("KAINOS_HUB_PORT" to "44003"), f.config.resolve("private-home"))
            assertFalse(isolation.dataDirOverridden)
            assertUnavailableProfile(f, backgroundScope, isolation, saved = true, requested = true)
        }
    }

    @Test
    fun unavailableAutostartDoesNotInventAChangeOrRetryError() = runTest {
        fixture { f ->
            for (saved in listOf(false, true)) {
                for (requested in listOf(false, true)) {
                    val isolation = DesktopIsolation.parse(mapOf("KAINOS_HUB_PORT" to "44003"), f.config.resolve("private-home"))
                    assertUnavailableProfile(f, backgroundScope, isolation, saved, requested)
                }
            }
        }
    }

    private suspend fun assertUnavailableProfile(
        f: Fixture,
        scope: CoroutineScope,
        isolation: DesktopIsolation,
        saved: Boolean,
        requested: Boolean,
    ) {
        val unit = f.config.resolve("systemd/user/kainos-hub.service")
        val legacy = f.config.resolve("autostart/kainos-home-sync-hub.desktop")
        Files.createDirectories(unit.parent)
        Files.createDirectories(legacy.parent)
        Files.writeString(unit, "protected unit")
        Files.writeString(legacy, "protected legacy entry")
        val service = HubUserService(isolation, f.config, { error("No launcher lookup") }) {
            f.events += it.joinToString(" ")
            error("No protected unit operations")
        }
        assertUnavailableAutostartPreservesSettings(f, scope, service, saved, requested)
        assertEquals("protected unit", Files.readString(unit))
        assertEquals("protected legacy entry", Files.readString(legacy))
    }

    private suspend fun assertUnavailableAutostartPreservesSettings(
        f: Fixture,
        scope: CoroutineScope,
        unitService: HubUserService,
        saved: Boolean,
        requested: Boolean,
    ) {
        var persisted = AppSettings(homeLanSyncHubAutostart = saved)
        val settingsFile = f.config.resolve("settings.json")
        Files.createDirectories(settingsFile.parent)
        Files.writeString(settingsFile, Json.encodeToString(AppSettings.serializer(), persisted))
        val service = HomeLanSyncService(
            scope = scope,
            settings = { persisted },
            updateSettings = { transform ->
                persisted = transform(persisted)
                Files.writeString(settingsFile, Json.encodeToString(AppSettings.serializer(), persisted))
            },
            library = LibraryRepository(),
            playlists = KainosPlaylistRepository(),
            hubFactory = { error("No listener in this test") },
            clientFactory = { error("No client in this test") },
            pairingClientFactory = noPairingClient(),
            identity = FixedDeviceIdentity("aa".repeat(32)),
            vaultStore = JvmHomeLanVaultStore({ null }, f.config.resolve("vault-state.json")),
            refreshLocalLibrary = {},
            configureHubAutostart = unitService::configure,
            hubAutostartEnabled = unitService::isEnabled,
        )
        service.hydrateFromSettings(persisted)
        val result = service.setHubAutostartEnabled(requested)
        assertEquals(saved, persisted.homeLanSyncHubAutostart, "Unsupported management must preserve the preference")
        assertEquals(saved, Json.decodeFromString<AppSettings>(Files.readString(settingsFile)).homeLanSyncHubAutostart)
        assertNull(service.status.value.hubAutostartError, "Unsupported management is not a failed retry")
        assertTrue(result.isSuccess)
        assertTrue(f.events.isEmpty(), "No protected unit operations")
    }

    private suspend fun assertSettingsFailure(
        f: Fixture,
        scope: CoroutineScope,
        requested: Boolean,
        timeout: Boolean,
        accepted: Boolean = false,
        noChange: Boolean = false,
    ) {
        f.enabled = !requested
        f.failureVerb = if (noChange) null else if (requested) "enable" else "disable"
        f.ignoreEnable = noChange
        f.timeout = timeout
        f.acceptBeforeTimeout = accepted
        var persisted = AppSettings(homeLanSyncHubAutostart = !requested)
        val settingsFile = f.config.resolve("settings.json")
        Files.createDirectories(settingsFile.parent)
        Files.writeString(settingsFile, Json.encodeToString(AppSettings.serializer(), persisted))
        val service = HomeLanSyncService(
            scope = scope,
            settings = { persisted },
            updateSettings = { transform ->
                val next = transform(persisted)
                Files.writeString(settingsFile, Json.encodeToString(AppSettings.serializer(), next))
                persisted = next
            },
            library = LibraryRepository(),
            playlists = KainosPlaylistRepository(),
            hubFactory = { error("No listener in this test") },
            clientFactory = { error("No client in this test") },
            pairingClientFactory = noPairingClient(),
            identity = FixedDeviceIdentity("aa".repeat(32)),
            vaultStore = JvmHomeLanVaultStore({ null }, f.config.resolve("vault-state.json")),
            refreshLocalLibrary = {},
            configureHubAutostart = f.service::configure,
            hubAutostartEnabled = f.service::isEnabled,
        )
        service.hydrateFromSettings(persisted)
        val result = runCatching { service.setHubAutostartEnabled(requested) }
        assertTrue(result.isSuccess, "Configuration failure must not abort app initialization: ${result.exceptionOrNull()}")
        assertTrue(result.getOrThrow().isFailure)
        val expected = if (accepted) requested else !requested
        assertEquals(expected, persisted.homeLanSyncHubAutostart)
        assertEquals(expected, Json.decodeFromString<AppSettings>(Files.readString(settingsFile)).homeLanSyncHubAutostart)
        assertNotNull(service.status.value.hubAutostartError, "Settings must show configuration failure")
        f.failureVerb = null
        f.ignoreEnable = false
        f.timeout = false
        assertTrue(service.setHubAutostartEnabled(requested).isSuccess)
        assertEquals(requested, Json.decodeFromString<AppSettings>(Files.readString(settingsFile)).homeLanSyncHubAutostart)
        assertNull(service.status.value.hubAutostartError)
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
        var onShow: () -> String = { "ActiveState=inactive\nJob=\n" }
        var onStart: () -> Unit = {}
        var failureVerb: String? = null
        var timeout = false
        var acceptBeforeTimeout = false
        var ignoreEnable = false
        val service = HubUserService(isolation, config, { "/tmp/kainos-player" }) { args ->
            events += args.joinToString(" ")
            val status = when {
                args.contains("is-enabled") -> if (enabled) 0 else 1
                failureVerb != null && args.contains(failureVerb) -> {
                    if (timeout) {
                        if (acceptBeforeTimeout) enabled = args.contains("enable")
                        error("systemctl timed out")
                    }
                    1
                }
                args.contains("show") -> return@HubUserService HubSystemctlResult(0, onShow())
                args.contains("enable") -> { if (!ignoreEnable) enabled = true; 0 }
                args.contains("disable") -> { enabled = false; 0 }
                args.contains("stop") -> { onStop(); 0 }
                args.contains("start") -> {
                    assertNotNull(HubOwnerLock.acquire(isolation.dataDir, 0), "Lock must be free before start").close()
                    onStart()
                    managedStarted = true
                    0
                }
                else -> 0
            }
            HubSystemctlResult(status, if (args.contains("is-enabled")) {
                if (enabled) "enabled" else "disabled"
            } else "")
        }
    }
}
