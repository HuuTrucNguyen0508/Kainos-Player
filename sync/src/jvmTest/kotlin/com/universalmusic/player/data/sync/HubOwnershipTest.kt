package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.DesktopIsolation
import com.universalmusic.player.platform.HubUserService
import com.universalmusic.player.platform.HubSystemctlResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class HubOwnershipTest {
    @Test
    fun defaultGuiStopsTheUnitThenReleasesBeforeStartingIt() = runBlocking {
        fixture(null) { f ->
            val owner = f.owner(HubProcessMode.GUI)
            assertTrue(owner.acquire())
            owner.startHub { f.events += "listen" }
            owner.close {
                assertNull(HubOwnerLock.acquire(f.isolation.dataDir, 0))
                f.events += "stop hub"
            }
            owner.close { error("Close must be idempotent") }
            assertEquals(listOf(
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user stop kainos-hub.service",
                "listen", "stop hub",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user start --no-block kainos-hub.service",
            ), f.events)
        }
    }

    @Test
    fun instanceThreeOnlyUsesItsOwnUnit() = runBlocking {
        fixture("3") { f ->
            val owner = f.owner(HubProcessMode.GUI)
            assertTrue(owner.acquire())
            owner.close {}
            assertEquals(listOf(
                "systemctl --user is-enabled kainos-hub-3.service",
                "systemctl --user stop kainos-hub-3.service",
                "systemctl --user is-enabled kainos-hub-3.service",
                "systemctl --user start --no-block kainos-hub-3.service",
            ), f.events)
        }
    }

    @Test
    fun instanceFourOnlyUsesItsOwnUnit() = runBlocking {
        fixture("4") { f ->
            val owner = f.owner(HubProcessMode.GUI)
            assertTrue(owner.acquire())
            owner.close {}
            assertEquals(listOf(
                "systemctl --user is-enabled kainos-hub-4.service",
                "systemctl --user stop kainos-hub-4.service",
                "systemctl --user is-enabled kainos-hub-4.service",
                "systemctl --user start --no-block kainos-hub-4.service",
            ), f.events)
        }
    }

    @Test
    fun disabledUnitIsNeitherStoppedNorRestarted() = runBlocking {
        fixture(null, enabled = false) { f ->
            val owner = f.owner(HubProcessMode.GUI)
            assertTrue(owner.acquire())
            owner.close {}
            assertEquals(listOf(
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user is-enabled kainos-hub.service",
            ), f.events)
        }
    }

    @Test
    fun hubOnlyDoesNotCallSystemctlAndCannotRestartAfterClose() = runBlocking {
        fixture("3") { f ->
            val owner = f.owner(HubProcessMode.SUPERVISED_HUB_ONLY)
            assertTrue(owner.acquire())
            owner.startHub { f.events += "listen" }
            owner.close { f.events += "stop hub" }
            assertFailsWith<IllegalStateException> { owner.startHub { error("Must not listen") } }
            assertEquals(listOf("listen", "stop hub"), f.events)
            assertNotNull(HubOwnerLock.acquire(f.isolation.dataDir, 0)).close()
            assertEquals(5_000, HubProcessMode.GUI.lockTimeoutMs)
            assertEquals(30_000, HubProcessMode.HUB_ONLY.lockTimeoutMs)
        }
    }

    @Test
    fun secondGuiTimesOutWithoutListeningOrStartingTheUnit() = runBlocking {
        fixture(null) { f ->
            val first = f.owner(HubProcessMode.GUI)
            assertTrue(first.acquire())
            val second = f.owner(HubProcessMode.GUI)
            try {
                assertFalse(second.acquire())
                val error = assertFailsWith<IllegalStateException> { second.startHub { error("Must not listen") } }
                assertEquals("Home sync hub owned by another instance", error.message)
                second.close {}
                assertEquals(listOf(
                    "systemctl --user is-enabled kainos-hub.service",
                    "systemctl --user stop kainos-hub.service",
                    "systemctl --user is-enabled kainos-hub.service",
                    "systemctl --user stop kainos-hub.service",
                ), f.events)
            } finally {
                first.close {}
            }
        }
    }

    @Test
    fun shutdownWaitsForInFlightStartAndRejectsLaterStarts() = runTest {
        fixture(null) { f ->
            val owner = f.owner(HubProcessMode.GUI)
            assertTrue(owner.acquire())
            val started = CompletableDeferred<Unit>()
            val starting = async { owner.startHub { started.await(); f.events += "listen" } }
            runCurrent()
            val closing = async { owner.close { f.events += "stop hub" } }
            runCurrent()
            assertFalse(closing.isCompleted)
            assertNull(HubOwnerLock.acquire(f.isolation.dataDir, 0))
            started.complete(Unit)
            starting.await()
            closing.await()
            assertFailsWith<IllegalStateException> { owner.startHub { error("Must not listen") } }
            assertEquals(listOf(
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user stop kainos-hub.service",
                "listen", "stop hub",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user start --no-block kainos-hub.service",
            ), f.events)
        }
    }

    @Test
    fun shutdownKeepsTheLockWhenListenerStopFails() = runBlocking {
        fixture(null) { f ->
            val owner = f.owner(HubProcessMode.GUI)
            assertTrue(owner.acquire())
            assertFailsWith<IllegalStateException> { owner.close { error("Server still running") } }
            assertNull(HubOwnerLock.acquire(f.isolation.dataDir, 0))
            assertEquals(listOf(
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user stop kainos-hub.service",
            ), f.events)
            owner.close {}
        }
    }

    @Test
    fun unitMigrationAndDisableAreIsolatedForDefaultThreeAndFour() = runBlocking {
        fixture(null) { f ->
            val legacy = f.config.resolve("autostart/kainos-home-sync-hub.desktop")
            Files.createDirectories(legacy.parent)
            Files.writeString(legacy, "legacy default")
            f.service.configure(true)
            assertFalse(Files.exists(legacy))
            val defaultUnit = f.config.resolve("systemd/user/kainos-hub.service")
            val defaultBody = Files.readString(defaultUnit)
            assertTrue(defaultBody.contains("ExecStart=\"/tmp/Kainos Player\" --hub-only"))
            assertTrue(defaultBody.contains("Environment=\"KAINOS_DATA_DIR=${f.isolation.dataDir}\""))
            assertTrue(defaultBody.contains("Environment=\"KAINOS_HUB_PORT=43822\""))
            assertFalse(defaultBody.contains("KAINOS_INSTANCE="))
            assertTrue(defaultBody.contains("Restart=on-failure\nRestartSec=5"))
            assertEquals(listOf("SuccessExitStatus=143"), defaultBody.lineSequence().filter { it.startsWith("SuccessExitStatus=") }.toList())
            assertTrue(defaultBody.contains("WantedBy=graphical-session.target"))
            Files.writeString(legacy, "legacy default")
            for (instance in listOf("3", "4")) {
                val isolation = DesktopIsolation(f.directory.resolve("data$instance"), 44000 + instance.toInt(), instance)
                val service = f.service(isolation)
                service.configure(true)
                val unit = f.config.resolve("systemd/user/kainos-hub-$instance.service")
                val body = Files.readString(unit)
                assertTrue(body.contains("Environment=\"KAINOS_DATA_DIR=${isolation.dataDir}\""))
                assertTrue(body.contains("Environment=\"KAINOS_HUB_PORT=4400$instance\""))
                assertTrue(body.contains("Environment=\"KAINOS_INSTANCE=$instance\""))
                assertEquals(listOf("SuccessExitStatus=143"), body.lineSequence().filter { it.startsWith("SuccessExitStatus=") }.toList())
                service.configure(false)
                assertFalse(Files.exists(unit))
                assertEquals(defaultBody, Files.readString(defaultUnit))
                assertEquals("legacy default", Files.readString(legacy))
            }
            f.service.configure(false)
            assertFalse(Files.exists(defaultUnit))
            assertFalse(Files.exists(legacy))
            assertEquals(listOf(
                "systemctl --user daemon-reload",
                "systemctl --user enable kainos-hub.service",
                "systemctl --user daemon-reload",
                "systemctl --user enable kainos-hub-3.service",
                "systemctl --user disable --now kainos-hub-3.service",
                "systemctl --user daemon-reload",
                "systemctl --user daemon-reload",
                "systemctl --user enable kainos-hub-4.service",
                "systemctl --user disable --now kainos-hub-4.service",
                "systemctl --user daemon-reload",
                "systemctl --user disable --now kainos-hub.service",
                "systemctl --user daemon-reload",
            ), f.events)
        }
    }

    @Test
    fun unchangedAutostartDoesNotRewriteUnitAndSpecialPathsAreEscaped() = runBlocking {
        fixture("3") { f ->
            val service = HubUserService(f.isolation, f.config, { "/tmp/Kainos % \"\u0024Player\\bin" }, f::command)
            service.configure(true)
            val unit = f.config.resolve("systemd/user/kainos-hub-3.service")
            val body = Files.readString(unit)
            assertTrue(body.contains("ExecStart=\"/tmp/Kainos %% \\\"\u0024\u0024Player\\\\bin\" --hub-only"))
            service.configure(true)
            assertEquals(listOf(
                "systemctl --user daemon-reload",
                "systemctl --user enable kainos-hub-3.service",
                "systemctl --user enable kainos-hub-3.service",
            ), f.events)
        }
    }

    private suspend fun fixture(instance: String?, enabled: Boolean = true, test: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("kainos-hub-ownership-")
        try {
            test(Fixture(directory, instance, enabled))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class Fixture(val directory: Path, instance: String?, val enabled: Boolean) {
        val isolation = DesktopIsolation(directory.resolve("data"), 43822, instance)
        val config: Path = directory.resolve("config")
        val events = mutableListOf<String>()
        private val fakeSystemctl = directory.resolve("systemctl").also { path ->
            Files.writeString(path, "#!/bin/sh\nexit 0\n")
            check(path.toFile().setExecutable(true))
        }
        val service = service(isolation)

        fun service(target: DesktopIsolation) = HubUserService(target, config, { "/tmp/Kainos Player" }, ::command)
        fun owner(mode: HubProcessMode) = HubOwnership(isolation, service, mode)

        fun command(arguments: List<String>): HubSystemctlResult {
            events += arguments.joinToString(" ")
            if (arguments.contains("start")) {
                assertNotNull(HubOwnerLock.acquire(isolation.dataDir, 0), "Release lock before systemctl start").close()
            }
            val process = ProcessBuilder(listOf(fakeSystemctl.toString()) + arguments.drop(1)).start()
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return if (arguments.contains("is-enabled")) {
                HubSystemctlResult(if (enabled) 0 else 1, if (enabled) "enabled" else "disabled")
            } else HubSystemctlResult(0)
        }
    }
}
