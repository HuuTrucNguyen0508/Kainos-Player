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
