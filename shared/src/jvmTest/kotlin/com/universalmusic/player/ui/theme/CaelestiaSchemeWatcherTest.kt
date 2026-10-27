package com.universalmusic.player.ui.theme

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

class CaelestiaSchemeWatcherTest {
    @Test fun initialReadAtomicReplaceMalformedAndDelete() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("kainos-scheme")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = dir.resolve("scheme.json")
        val fixture = schemeFixture()
        file.writeText(fixture)
        val watcher = StateFileWatcher(file, scope, parse = { it?.let(::parseCaelestiaScheme) })
        try {
            val initial = withTimeout(3_000) { watcher.state.first { it != null } }
            assertTrue(assertNotNull(initial).isDark)
            val light = fixture.replace("\"dark\"", "\"light\"")
            val replacement = dir.resolve("replacement")
            replacement.writeText(light)
            Files.move(replacement, file, ATOMIC_MOVE, REPLACE_EXISTING)
            val changed = withTimeout(3_000) { watcher.state.first { it?.isDark == false } }
            file.writeText("broken update")
            delay(350)
            assertEquals(changed, watcher.state.value)
            Files.delete(file)
            withTimeout(3_000) { watcher.state.first { it == null } }
            assertNull(watcher.state.value)
            file.writeText(fixture)
            withTimeout(3_000) { watcher.state.first { it?.isDark == true } }
        } finally {
            scope.cancel()
            withTimeout(3_000) { while (!watcher.isClosed) delay(10) }
            dir.toFile().deleteRecursively()
        }
        assertTrue(watcher.isClosed)
    }

    @Test fun unchangedFingerprintDoesNotRepublishOnBackstop() = runBlocking<Unit> {
        val root = Files.createTempDirectory("kainos-stamp")
        val file = root.resolve("scheme.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        file.writeText(schemeFixture())
        val watcher = StateFileWatcher(file, scope, parse = { it?.let(::parseCaelestiaScheme) }, backstopMs = 50)
        try {
            val initial = withTimeout(3_000) { watcher.state.first { it != null } }
            delay(200)
            assertSame(initial, watcher.state.value)
        } finally {
            scope.cancel()
            withTimeout(3_000) { while (!watcher.isClosed) delay(10) }
            root.toFile().deleteRecursively()
        }
    }

    @Test fun unavailableWatchServiceStillFollowsFileChanges() = runBlocking<Unit> {
        val root = Files.createTempDirectory("kainos-poll")
        val file = root.resolve("scheme.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        file.writeText(schemeFixture())
        val watcher = StateFileWatcher(file, scope, parse = { it?.let(::parseCaelestiaScheme) }, backstopMs = 50,
            watchServiceFactory = { throw java.io.IOException("inotify unavailable") })
        try {
            withTimeout(3_000) { watcher.state.first { it != null } }
            file.writeText(schemeFixture().replace("\"dark\"", "\"light\""))
            withTimeout(3_000) { watcher.state.first { it?.isDark == false } }
            Files.delete(file)
            withTimeout(3_000) { watcher.state.first { it == null } }
        } finally {
            scope.cancel()
            withTimeout(3_000) { while (!watcher.isClosed) delay(10) }
            root.toFile().deleteRecursively()
        }
    }

    @Test fun discoversMissingDirectoryAndRecoversAfterItsRecreation() = runBlocking<Unit> {
        val root = Files.createTempDirectory("kainos-parent")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = root.resolve("state/caelestia")
        val file = dir.resolve("scheme.json")
        val watcher = StateFileWatcher(file, scope, parse = { it?.let(::parseCaelestiaScheme) }, backstopMs = 100)
        try {
            delay(100)
            assertNull(watcher.state.value)
            Files.createDirectories(dir)
            file.writeText(schemeFixture())
            withTimeout(3_000) { watcher.state.first { it != null } }
            dir.toFile().deleteRecursively()
            withTimeout(3_000) { watcher.state.first { it == null } }
            Files.createDirectories(dir)
            file.writeText(schemeFixture().replace("\"dark\"", "\"light\""))
            withTimeout(3_000) { watcher.state.first { it != null } }
            assertFalse(assertNotNull(watcher.state.value).isDark)
        } finally {
            scope.cancel()
            withTimeout(3_000) { while (!watcher.isClosed) delay(10) }
            root.toFile().deleteRecursively()
        }
    }
}
