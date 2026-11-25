package com.universalmusic.player.ui.theme

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopEnvTest {
    @Test fun parsingMatchesShellFallback() {
        for (text in listOf("folio", "folio\n", "  folio ")) assertEquals(Skin.FOLIO, parseDesktopEnv(text))
        for (text in listOf("caelestia", "garbage", "FOLIO", "", null)) assertEquals(Skin.CAELESTIA, parseDesktopEnv(text))
    }

    @Test fun frostWinsWhenItsManifestClaimsTheWallpaper() {
        val dir = Files.createTempDirectory("kainos-frost-style")
        try {
            val manifest = dir.resolve("style.json")
            manifest.writeText("""{"id":"frost","wallpapers":["/walls/mist.jpg","/walls/mist-clean.jpg"]}""")
            assertEquals(DesktopLook(Skin.FROST, "/walls/mist-clean.jpg"), desktopLook(Skin.FOLIO, "/walls/mist-clean.jpg", manifest))
            assertEquals(DesktopLook(Skin.FOLIO, "/walls/other.jpg"), desktopLook(Skin.FOLIO, "/walls/other.jpg", manifest))
            assertEquals(DesktopLook(Skin.CAELESTIA, null), desktopLook(Skin.CAELESTIA, null, manifest))
            // No Frost style installed, or a half-written manifest: the shell is not showing Frost.
            assertEquals(Skin.FOLIO, desktopLook(Skin.FOLIO, "/walls/mist.jpg", dir.resolve("missing.json")).skin)
            manifest.writeText("""{"wallpapers":[""")
            assertEquals(Skin.FOLIO, desktopLook(Skin.FOLIO, "/walls/mist.jpg", manifest).skin)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun nyxWinsWhenItsManifestClaimsTheWallpaper() {
        val dir = Files.createTempDirectory("kainos-nyx-style")
        try {
            val frost = dir.resolve("frost.json")
            val nyx = dir.resolve("nyx.json")
            frost.writeText("""{"id":"frost","wallpapers":["/walls/mist.jpg"]}""")
            nyx.writeText("""{"id":"nyx","wallpapers":["/walls/nyx-nebula-3440x1440.png"]}""")
            assertEquals(
                DesktopLook(Skin.NYX, "/walls/nyx-nebula-3440x1440.png"),
                desktopLook(Skin.FOLIO, "/walls/nyx-nebula-3440x1440.png", frost, nyx),
            )
            assertEquals(
                DesktopLook(Skin.FROST, "/walls/mist.jpg"),
                desktopLook(Skin.FOLIO, "/walls/mist.jpg", frost, nyx),
            )
            assertEquals(
                DesktopLook(Skin.FOLIO, "/walls/other.jpg"),
                desktopLook(Skin.FOLIO, "/walls/other.jpg", frost, nyx),
            )
            assertEquals(
                DesktopLook(Skin.CAELESTIA, null),
                desktopLook(Skin.CAELESTIA, null, frost, nyx),
            )
            // Missing or malformed Nyx manifest: Nyx is not active.
            assertEquals(
                Skin.FOLIO,
                desktopLook(Skin.FOLIO, "/walls/nyx-nebula-3440x1440.png", frost, dir.resolve("missing.json")).skin,
            )
            nyx.writeText("""{"wallpapers":[""")
            assertEquals(
                Skin.FOLIO,
                desktopLook(Skin.FOLIO, "/walls/nyx-nebula-3440x1440.png", frost, nyx).skin,
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun stylePathsRespectOverridesAndXdg() {
        for ((variable, styleId, resolver) in listOf(
            Triple("KAINOS_FROST_STYLE_FILE", "frost", ::frostStylePath),
            Triple("KAINOS_NYX_STYLE_FILE", "nyx", ::nyxStylePath),
        )) {
            fun resolve(env: Map<String, String>) = resolver(env::get, "/test-home")
            assertEquals(Path.of("/custom/$styleId.json"), resolve(mapOf(variable to "/custom/$styleId.json", "XDG_CONFIG_HOME" to "/xdg")))
            assertEquals(Path.of("/xdg/quickshell/caelestia/modules/styles/$styleId/style.json"), resolve(mapOf("XDG_CONFIG_HOME" to "/xdg")))
            assertEquals(Path.of("/xdg/quickshell/caelestia/modules/styles/$styleId/style.json"), resolve(mapOf(variable to " ", "XDG_CONFIG_HOME" to "/xdg")))
            assertEquals(Path.of("/test-home/.config/quickshell/caelestia/modules/styles/$styleId/style.json"), resolve(emptyMap()))
            assertEquals(Path.of("/test-home/.config/quickshell/caelestia/modules/styles/$styleId/style.json"), resolve(mapOf("XDG_CONFIG_HOME" to "")))
        }
    }

    @Test fun statePathsRespectOverridesAndXdg() {
        for ((variable, filename) in listOf("KAINOS_DESKTOP_ENV_FILE" to "desktop-env", "KAINOS_CAELESTIA_SCHEME" to "scheme.json")) {
            fun resolve(env: Map<String, String>) = caelestiaStatePath(variable, filename, env::get, "/test-home")
            assertEquals(Path.of("/custom/file"), resolve(mapOf(variable to "/custom/file", "XDG_STATE_HOME" to "/xdg")))
            assertEquals(Path.of("/xdg/caelestia", filename), resolve(mapOf("XDG_STATE_HOME" to "/xdg")))
            assertEquals(Path.of("/xdg/caelestia", filename), resolve(mapOf(variable to " ", "XDG_STATE_HOME" to "/xdg")))
            assertEquals(Path.of("/test-home/.local/state/caelestia", filename), resolve(emptyMap()))
            assertEquals(Path.of("/test-home/.local/state/caelestia", filename), resolve(mapOf("XDG_STATE_HOME" to "")))
        }
    }

    @Test fun watchesAtomicDesktopSwitchAndFallsBackAfterDeletion() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("kainos-desktop-env")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = dir.resolve("desktop-env")
        val watcher = StateFileWatcher(file, scope, backstopMs = 100, parse = ::parseDesktopEnv)
        fun replace(text: String) {
            val replacement = dir.resolve("replacement")
            replacement.writeText(text)
            Files.move(replacement, file, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        suspend fun await(skin: Skin) { withTimeout(3_000) { watcher.state.first { it == skin } } }
        try {
            assertEquals(Skin.CAELESTIA, watcher.state.value)
            replace("folio\n")
            await(Skin.FOLIO)
            replace("caelestia\n")
            await(Skin.CAELESTIA)
            replace("  folio ")
            await(Skin.FOLIO)
            replace("garbage")
            await(Skin.CAELESTIA)
            replace("folio")
            await(Skin.FOLIO)
            Files.delete(file)
            await(Skin.CAELESTIA)
        } finally {
            scope.cancel()
            withTimeout(3_000) { while (!watcher.isClosed) delay(10) }
            dir.toFile().deleteRecursively()
        }
    }
}
