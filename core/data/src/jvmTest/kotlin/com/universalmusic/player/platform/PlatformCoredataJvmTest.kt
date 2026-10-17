package com.universalmusic.player.platform

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class PlatformCoredataJvmTest {
    @Test
    fun unsetDataDirUsesTheLiteralHomeDefault() {
        val isolation = DesktopIsolation.parse(emptyMap(), Path.of("/home/test"))
        assertEquals(Path.of("/home/test/.universal-music-player"), isolation.dataDir)
    }

    @Test
    fun overriddenDataDirUsesTheLiteralAbsolutePath() {
        val isolation = DesktopIsolation.parse(mapOf("KAINOS_DATA_DIR" to "/tmp/x"), Path.of("/home/test"))
        assertEquals(Path.of("/tmp/x"), isolation.dataDir)
    }

    @Test
    fun configAndHomeLanConsumersUseTheInjectedDataDir() {
        val root = createTempDirectory("coredata-paths")
        try {
            val dir = root.resolve("isolated/data")
            assertEquals(dir, configDir(dir))
            assertEquals(dir, homeLanConfigDir(dir))
            assertTrue(Files.isDirectory(dir))
            assertTrue(Files.list(root).use { it.allMatch { child -> child.fileName.toString() == "isolated" } })
        } finally { root.toFile().deleteRecursively() }
    }
}
