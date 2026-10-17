package com.universalmusic.player.platform

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class PlatformCoredataJvmTest {
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
