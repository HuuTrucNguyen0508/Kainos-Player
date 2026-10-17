package com.universalmusic.player.platform

import com.universalmusic.player.data.sync.HubOwnership
import com.universalmusic.player.data.sync.HubProcessMode
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubUserServiceIsolationTest {
    private val partialOverrides = listOf(
        setOf("KAINOS_DATA_DIR"),
        setOf("KAINOS_HUB_PORT"),
        setOf("KAINOS_DATA_DIR", "KAINOS_HUB_PORT"),
    )

    @Test
    fun partialOverridesLeaveRealAutostartUntouchedOnEnable() = runBlocking {
        for (overrides in partialOverrides) fixture(overrides) { f ->
            f.service.configure(true)
            f.assertRealAutostartUnchanged()
            assertTrue(f.commands.isEmpty(), overrides.toString())
        }
    }

    @Test
    fun partialOverridesLeaveRealAutostartUntouchedOnDisable() = runBlocking {
        for (overrides in partialOverrides) fixture(overrides) { f ->
            f.service.configure(false)
            f.assertRealAutostartUnchanged()
            assertTrue(f.commands.isEmpty(), overrides.toString())
        }
    }

    @Test
    fun partialOverridesLeaveRealAutostartUntouchedOnGuiStartAndQuit() = runBlocking {
        for (overrides in partialOverrides) fixture(overrides) { f ->
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertTrue(owner.acquire())
            try {
                f.assertRealAutostartUnchanged()
                assertTrue(f.commands.isEmpty(), "GUI start with $overrides")
            } finally {
                owner.close {}
            }
            f.assertRealAutostartUnchanged()
            assertTrue(f.commands.isEmpty(), "GUI quit with $overrides")
        }
    }

    @Test
    fun partialOverridesCannotOperateOnTheDefaultUnit() = runBlocking {
        for (overrides in partialOverrides) fixture(overrides) { f ->
            assertFalse(f.service.isEnabled())
            f.service.stop()
            assertEquals(HubStopOutcome.STOPPED, f.service.reconcileStop())
            f.service.start()
            f.assertRealAutostartUnchanged()
            assertTrue(f.commands.isEmpty(), overrides.toString())
        }
    }

    @Test
    fun instanceThreeUsesOnlyItsOwnUnitAndEnvironment() = runBlocking {
        fixture(setOf("KAINOS_DATA_DIR", "KAINOS_HUB_PORT", "KAINOS_INSTANCE")) { f ->
            f.service.configure(true)
            val unit = f.config.resolve("systemd/user/kainos-hub-3.service")
            val body = Files.readString(unit)
            assertTrue(body.contains("Environment=\"KAINOS_DATA_DIR=${f.isolation.dataDir}\""))
            assertTrue(body.contains("Environment=\"KAINOS_HUB_PORT=44003\""))
            assertTrue(body.contains("Environment=\"KAINOS_INSTANCE=3\""))
            assertTrue(body.contains("Environment=\"KAINOS_HUB_UNIT=kainos-hub-3.service\""))
            assertEquals(HubProcessMode.SUPERVISED_HUB_ONLY, f.service.processMode(true, "kainos-hub-3.service"))
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertTrue(owner.acquire())
            owner.close {}
            f.service.configure(false)
            assertFalse(Files.exists(unit))
            f.assertRealAutostartUnchanged()
            assertEquals(listOf(
                "systemctl --user daemon-reload",
                "systemctl --user enable kainos-hub-3.service",
                "systemctl --user is-enabled kainos-hub-3.service",
                "systemctl --user stop kainos-hub-3.service",
                "systemctl --user is-enabled kainos-hub-3.service",
                "systemctl --user start --no-block kainos-hub-3.service",
                "systemctl --user disable --now kainos-hub-3.service",
                "systemctl --user daemon-reload",
            ), f.commands)
        }
    }

    @Test
    fun defaultInstanceUsesDefaultUnitAndRemovesLegacyAutostart() = runBlocking {
        fixture(emptySet()) { f ->
            assertEquals(HubProcessMode.SUPERVISED_HUB_ONLY, f.service.processMode(true, "kainos-hub.service"))
            f.service.configure(true)
            val body = Files.readString(f.realUnit)
            assertTrue(body.contains("Environment=\"KAINOS_DATA_DIR=${f.isolation.dataDir}\""))
            assertTrue(body.contains("Environment=\"KAINOS_HUB_PORT=43822\""))
            assertTrue(body.contains("Environment=\"KAINOS_HUB_UNIT=kainos-hub.service\""))
            assertFalse(body.contains("KAINOS_INSTANCE="))
            assertFalse(Files.exists(f.legacyAutostart))
            val owner = HubOwnership(f.isolation, f.service, HubProcessMode.GUI)
            assertTrue(owner.acquire())
            owner.close {}
            Files.writeString(f.legacyAutostart, "legacy autostart")
            f.service.configure(false)
            assertFalse(Files.exists(f.realUnit))
            assertFalse(Files.exists(f.legacyAutostart))
            assertEquals(listOf(
                "systemctl --user daemon-reload",
                "systemctl --user enable kainos-hub.service",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user stop kainos-hub.service",
                "systemctl --user is-enabled kainos-hub.service",
                "systemctl --user start --no-block kainos-hub.service",
                "systemctl --user disable --now kainos-hub.service",
                "systemctl --user daemon-reload",
            ), f.commands)
        }
    }

    private suspend fun fixture(overrides: Set<String>, test: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("kainos-hub-service-isolation-")
        try {
            test(Fixture(directory, overrides))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class Fixture(directory: Path, overrides: Set<String>) {
        val isolation = DesktopIsolation.parse(mapOf(
            "KAINOS_DATA_DIR" to directory.resolve("isolated-data").toString(),
            "KAINOS_HUB_PORT" to "44003",
            "KAINOS_INSTANCE" to "3",
        ).filterKeys { it in overrides }, directory.resolve("home"))
        val config: Path = directory.resolve("config")
        val realUnit: Path = config.resolve("systemd/user/kainos-hub.service")
        val legacyAutostart: Path = config.resolve("autostart/kainos-home-sync-hub.desktop")
        val commands = mutableListOf<String>()
        val service = HubUserService(isolation, config, { "/tmp/Kainos Player" }) { arguments ->
            commands += arguments.joinToString(" ")
            when {
                "is-enabled" in arguments -> HubSystemctlResult(0, "enabled")
                "show" in arguments -> HubSystemctlResult(0, "ActiveState=inactive\nJob=0\n")
                else -> HubSystemctlResult(0)
            }
        }

        init {
            Files.createDirectories(realUnit.parent)
            Files.writeString(realUnit, "real unit")
            Files.createDirectories(legacyAutostart.parent)
            Files.writeString(legacyAutostart, "legacy autostart")
        }

        fun assertRealAutostartUnchanged() {
            assertEquals("real unit", Files.readString(realUnit))
            assertEquals("legacy autostart", Files.readString(legacyAutostart))
        }
    }
}
