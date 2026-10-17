package com.universalmusic.player.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopIsolationTest {
    private val home = Path.of("/home/u")

    @Test
    fun unsetVariablesKeepTheRealDataDirHubPortAndNoInstance() {
        assertEquals(
            DesktopIsolation(Path.of("/home/u/.universal-music-player"), 43822, null),
            DesktopIsolation.parse(emptyMap(), home),
        )
    }

    @Test
    fun blankVariablesFailNamingEachVariable() {
        for (name in listOf("KAINOS_DATA_DIR", "KAINOS_HUB_PORT", "KAINOS_INSTANCE")) {
            for (blank in listOf("", " ", "\t")) {
                val error = assertFailsWith<IllegalArgumentException> {
                    DesktopIsolation.parse(mapOf(name to blank), home)
                }
                assertTrue(error.message!!.contains(name), error.message)
            }
        }
    }

    @Test
    fun relativeDataDirFailsNamingTheVariable() {
        val error = assertFailsWith<IllegalArgumentException> {
            DesktopIsolation.parse(mapOf("KAINOS_DATA_DIR" to "worker/data"), home)
        }
        assertTrue(error.message!!.contains("KAINOS_DATA_DIR"), error.message)
        assertTrue(error.message!!.contains("absolute"), error.message)
    }

    @Test
    fun fullBusNameLengthIsValidatedAtParseTime() {
        val prefix = "org.mpris.MediaPlayer2.kainosplayer.instance"
        val longest = "a".repeat(255 - prefix.length)
        assertEquals(longest, DesktopIsolation.parse(mapOf("KAINOS_INSTANCE" to longest), home).instance)
        val error = assertFailsWith<IllegalArgumentException> {
            DesktopIsolation.parse(mapOf("KAINOS_INSTANCE" to longest + "a"), home)
        }
        assertTrue(error.message!!.contains("KAINOS_INSTANCE"), error.message)
        assertTrue(error.message!!.contains("255"), error.message)
    }

    @Test
    fun setVariablesReplaceEachDefault() {
        val env = mapOf("KAINOS_DATA_DIR" to "/tmp/x", "KAINOS_HUB_PORT" to "44005", "KAINOS_INSTANCE" to "2")
        assertEquals(
            DesktopIsolation(Path.of("/tmp/x"), 44005, "2", dataDirOverridden = true, hubPortOverridden = true),
            DesktopIsolation.parse(env, home),
        )
    }

    @Test
    fun nonNumericOrOutOfRangeHubPortNamesTheVariable() {
        for (bad in listOf("abc", "0", "65536", "-1")) {
            val error = assertFailsWith<IllegalArgumentException> {
                DesktopIsolation.parse(mapOf("KAINOS_HUB_PORT" to bad), home)
            }
            assertEquals("KAINOS_HUB_PORT must be a port number from 1 to 65535, got '$bad'", error.message)
        }
    }

    @Test
    fun instanceOutsideBusNameCharactersNamesTheVariable() {
        val error = assertFailsWith<IllegalArgumentException> {
            DesktopIsolation.parse(mapOf("KAINOS_INSTANCE" to "a.b"), home)
        }
        assertEquals("KAINOS_INSTANCE may contain only letters, digits, '_' and '-', got 'a.b'", error.message)
    }

    @Test
    fun loadCreatesTheDataDir() {
        val root = Files.createTempDirectory("kainos-isolation")
        val dir = root.resolve("worker-1/data")
        val loaded = DesktopIsolation.load(mapOf("KAINOS_DATA_DIR" to dir.toString()), home)
        assertEquals(dir, loaded.dataDir)
        assertTrue(Files.isDirectory(dir))
    }

    @Test
    fun loadFailsNamingTheVariableAndPathWhenTheDirCannotBeCreated() {
        val file = Files.createTempFile("kainos-isolation", ".file")
        val dir = file.resolve("data")
        val error = assertFailsWith<IllegalArgumentException> {
            DesktopIsolation.load(mapOf("KAINOS_DATA_DIR" to dir.toString()), home)
        }
        assertTrue(
            error.message!!.startsWith("KAINOS_DATA_DIR $dir cannot be created or is not writable"),
            error.message,
        )
    }
}
