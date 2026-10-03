package com.universalmusic.player.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

data class ChromiumInstallation(
    val executable: Path,
    val label: String,
    val family: BrowserFamily,
    val likelyHasWidevine: Boolean,
)

enum class BrowserFamily {
    CHROMIUM,
    FIREFOX,
}

interface ChromiumLocator {
    fun findCandidates(): List<ChromiumInstallation>
}

/**
 * Prefer browsers that ship Widevine/EME. Arch Chromium without Widevine CDM
 * causes Spotify Web Playback `initialization_error: Failed to initialize player`.
 */
object SystemChromiumLocator : ChromiumLocator {
    override fun findCandidates(): List<ChromiumInstallation> {
        val ordered = listOf(
            candidate("google-chrome-stable", "Google Chrome Stable", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("google-chrome", "Google Chrome", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("/usr/bin/google-chrome-stable", "Google Chrome Stable", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("/usr/bin/google-chrome", "Google Chrome", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("/opt/google/chrome/google-chrome", "Google Chrome", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("brave-origin", "Brave Origin", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("/usr/bin/brave-origin", "Brave Origin", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("/opt/brave-origin-bin/brave-origin", "Brave Origin", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("/opt/brave-origin-bin/brave", "Brave Origin", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("brave-browser", "Brave", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("/usr/bin/brave-browser", "Brave", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("brave", "Brave", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            candidate("/usr/bin/brave", "Brave", BrowserFamily.CHROMIUM, likelyHasWidevine = true),
            // Firefox/Zen download Widevine from Mozilla; often works when stock Chromium does not.
            candidate("zen-browser", "Zen Browser", BrowserFamily.FIREFOX, likelyHasWidevine = true),
            candidate("/usr/bin/zen-browser", "Zen Browser", BrowserFamily.FIREFOX, likelyHasWidevine = true),
            candidate("firefox", "Firefox", BrowserFamily.FIREFOX, likelyHasWidevine = true),
            candidate("/usr/bin/firefox", "Firefox", BrowserFamily.FIREFOX, likelyHasWidevine = true),
            candidate("chromium", "Chromium", BrowserFamily.CHROMIUM, likelyHasWidevine = chromiumHasSystemWidevine()),
            candidate("chromium-browser", "Chromium", BrowserFamily.CHROMIUM, likelyHasWidevine = chromiumHasSystemWidevine()),
            candidate("/usr/bin/chromium", "Chromium", BrowserFamily.CHROMIUM, likelyHasWidevine = chromiumHasSystemWidevine()),
            candidate("/usr/bin/chromium-browser", "Chromium", BrowserFamily.CHROMIUM, likelyHasWidevine = chromiumHasSystemWidevine()),
        )
        return ordered.filterNotNull()
            .distinctBy { it.executable.toAbsolutePath().normalize().toString() }
            // Prefer Widevine-capable runtimes; keep others only as last-ditch attempts.
            .sortedByDescending { it.likelyHasWidevine }
    }

    private fun candidate(
        command: String,
        label: String,
        family: BrowserFamily,
        likelyHasWidevine: Boolean,
    ): ChromiumInstallation? {
        val path = resolveExecutable(command) ?: return null
        return ChromiumInstallation(path, label, family, likelyHasWidevine)
    }

    private fun resolveExecutable(command: String): Path? {
        if (command.contains('/')) {
            val direct = Paths.get(command)
            return direct.takeIf { it.exists() && it.isExecutable() }
        }
        val which = runCatching {
            val process = ProcessBuilder("which", command).redirectErrorStream(true).start()
            val line = process.inputStream.bufferedReader().readLine()?.trim().orEmpty()
            val ok = process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
            if (ok && line.isNotEmpty()) Paths.get(line) else null
        }.getOrNull()
        return which?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}

internal fun chromiumHasSystemWidevine(): Boolean {
    val markers = listOf(
        Paths.get("/usr/lib/chromium/WidevineCdm"),
        Paths.get("/usr/lib/chromium-browser/WidevineCdm"),
        Paths.get("/usr/lib/chromium/libwidevinecdm.so"),
        Paths.get("/opt/google/chrome/WidevineCdm"),
    )
    return markers.any { Files.exists(it) }
}

fun kainosWebPlayerProfileDir(): Path {
    val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home", "."), ".cache").toString()
    return Paths.get(cache, "kainos", "spotify-web-player")
}
