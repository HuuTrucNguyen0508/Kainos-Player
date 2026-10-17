package com.universalmusic.player.platform

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubAutostartIsolationTest {
    @Test
    fun eachOverrideLeavesLoginAutostartUnchangedForEnableAndDisable() {
        val root = createTempDirectory("hub-autostart")
        try {
            val desktop = root.resolve(".config/autostart/kainos-home-sync-hub.desktop")
            val log = mutableListOf<String>()
            for ((name, value) in mapOf("KAINOS_DATA_DIR" to root.resolve("data").toString(),
                "KAINOS_HUB_PORT" to "43822", "KAINOS_INSTANCE" to "test")) {
                val env = mapOf("HOME" to root.toString(), "KAINOS_PLAYER_EXEC" to "/test/kainos-player", name to value)
                val isolation = DesktopIsolation.parse(env, root)
                Files.createDirectories(desktop.parent)
                Files.writeString(desktop, "original autostart")
                for (enabled in listOf(true, false)) {
                    setHomeLanHubAutostart(enabled, isolation, env, log::add)
                    assertEquals("original autostart", Files.readString(desktop), "$name enabled=$enabled")
                }
                Files.delete(desktop)
                setHomeLanHubAutostart(true, isolation, env, log::add)
                assertFalse(Files.exists(desktop), name)
            }
            assertEquals(List(9) { "Hub autostart unchanged: isolated instance" }, log)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun defaultInstanceStillWritesAndDeletesAutostart() {
        val root = createTempDirectory("hub-autostart-default")
        try {
            val env = mapOf("HOME" to root.toString(), "KAINOS_PLAYER_EXEC" to "/test/kainos-player")
            val isolation = DesktopIsolation.parse(env, root)
            val log = mutableListOf<String>()
            val desktop = root.resolve(".config/autostart/kainos-home-sync-hub.desktop")
            setHomeLanHubAutostart(true, isolation, env, log::add)
            assertTrue(Files.readString(desktop).contains("Exec=/test/kainos-player --hub-only"))
            setHomeLanHubAutostart(false, isolation, env, log::add)
            assertFalse(Files.exists(desktop))
            assertEquals(emptyList(), log)
        } finally { root.toFile().deleteRecursively() }
    }
}
