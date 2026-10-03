package com.universalmusic.player.platform

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Launch the Spotify desktop client when Connect lists no devices. */
suspend fun ensureSpotifyDesktopClientRunning(
    isRunning: () -> Boolean = ::spotifyProcessRunning,
    launch: () -> Boolean = ::launchSpotifyDesktopClient,
    waitSeconds: Int = 20,
): Boolean = withContext(Dispatchers.IO) {
    if (isRunning()) return@withContext true
    if (!launch()) return@withContext false
    repeat(waitSeconds.coerceAtLeast(1)) {
        delay(1_000)
        if (isRunning()) return@withContext true
    }
    isRunning()
}

internal fun spotifyProcessRunning(): Boolean {
    val checks = listOf(
        listOf("pgrep", "-x", "spotify"),
        listOf("pgrep", "-f", "spotify"),
    )
    return checks.any { command ->
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        }.getOrDefault(false)
    }
}

internal fun launchSpotifyDesktopClient(): Boolean {
    val commands = listOf(
        listOf("spotify"),
        listOf("/usr/bin/spotify"),
        listOf("flatpak", "run", "com.spotify.Client"),
    )
    for (command in commands) {
        val started = runCatching {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrDefault(false)
        if (started) return true
    }
    return false
}
