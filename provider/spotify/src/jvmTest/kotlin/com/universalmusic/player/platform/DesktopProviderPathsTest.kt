package com.universalmusic.player.platform

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopProviderPathsTest {
    @Test
    fun overriddenDataDirOwnsTheWebPlayerProfile() {
        val isolation = DesktopIsolation.parse(mapOf("KAINOS_DATA_DIR" to "/tmp/worker/data"), Path.of("/home/u"))
        assertEquals(Path.of("/tmp/worker/data/spotify-web-player"),
            kainosWebPlayerProfileDir(isolation, Path.of("/tmp/xdg-cache")))
    }

    @Test
    fun defaultDataDirKeepsTheXdgWebPlayerProfile() {
        val isolation = DesktopIsolation.parse(emptyMap(), Path.of("/home/u"))
        assertEquals(Path.of("/tmp/xdg-cache/kainos/spotify-web-player"),
            kainosWebPlayerProfileDir(isolation, Path.of("/tmp/xdg-cache")))
    }

    @Test
    fun librespotPathsAndCredentialsResolveUnderTheInjectedDataDir() {
        val dir = createTempDirectory("librespot-paths")
        try {
            val paths = LibrespotPaths.defaults(dir)
            assertEquals(dir.resolve("librespot/system"), paths.systemCache)
            assertEquals(dir.resolve("librespot/audio"), paths.audioCache)
            assertEquals(dir.resolve("logs/librespot.log"), paths.logFile)
            assertEquals(dir.resolve("librespot/system/credentials.json"), paths.credentialsFile)
        } finally { dir.toFile().deleteRecursively() }
    }
}
