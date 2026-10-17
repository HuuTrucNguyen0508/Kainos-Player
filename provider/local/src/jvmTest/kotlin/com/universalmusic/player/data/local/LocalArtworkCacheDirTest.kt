package com.universalmusic.player.data.local

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalArtworkCacheDirTest {
    @Test
    fun artworkConsumerCreatesItsCacheUnderTheInjectedDataDir() {
        val root = createTempDirectory("local-art-paths")
        try {
            val dir = root.resolve("isolated/data")
            assertEquals(dir.resolve("art-cache"), artCacheDir(dir))
            assertTrue(Files.isDirectory(dir.resolve("art-cache")))
        } finally { root.toFile().deleteRecursively() }
    }
}
