package com.universalmusic.player.platform

import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.local.JvmLocalTrackSource
import com.universalmusic.player.data.local.LocalTrackSource
import com.universalmusic.player.data.local.resolveMusicRoots
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.PlaybackEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun configDir(): Path {
    val home = System.getProperty("user.home")
    val dir = Path.of(home, ".universal-music-player")
    Files.createDirectories(dir)
    return dir
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun sha256Bytes(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(SecureRandom()::nextBytes)

actual fun encodeUrl(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
    install(ContentNegotiation) { json(json) }
}

actual fun createTokenStore(): TokenStore = FileTokenStore(configDir() / "tokens.json")

actual fun createSettingsStore(): SettingsStore = FileSettingsStore(configDir() / "settings.json")

actual fun createLocalTrackSource(configuredFolders: () -> List<String>): LocalTrackSource =
    JvmLocalTrackSource {
        resolveMusicRoots(
            homeDirectory = Paths.get(System.getProperty("user.home", ".")),
            configuredFolders = configuredFolders(),
            additionalRoots = System.getenv("KAINOS_MUSIC_DIRS"),
        )
    }

actual fun loadAppConfig(): AppConfig {
    val env = System.getenv()
    val props = Properties()
    val file = Path.of("secrets.properties")
    if (file.exists()) {
        file.toFile().inputStream().use { props.load(it) }
    }
    val homeFile = configDir() / "secrets.properties"
    if (homeFile.exists()) {
        homeFile.toFile().inputStream().use { props.load(it) }
    }
    fun value(key: String): String? = env[key] ?: props.getProperty(key)?.takeIf { it.isNotBlank() }
    return AppConfig(
        spotifyClientId = value("SPOTIFY_CLIENT_ID"),
        spotifyRedirectUri = value("SPOTIFY_REDIRECT_URI") ?: "http://127.0.0.1:43821/callback",
        youtubeDataApiKey = value("YOUTUBE_DATA_API_KEY"),
    )
}

actual fun createPlaybackEngine(spotify: SpotifyPlaybackController): PlaybackEngine =
    DesktopPlaybackEngine(spotify)

actual fun createYouTubeStreamResolver(): YouTubeStreamResolver = JvmYouTubeStreamResolver()

actual fun createSpotifyWebPlaybackHost(tokenSupplier: SpotifyTokenSupplier): SpotifyWebPlaybackHost =
    if (System.getProperty("os.name", "").contains("Linux", ignoreCase = true)) {
        JvmLibrespotPlaybackHost()
    } else {
        JvmSpotifyWebPlaybackHost(tokenSupplier)
    }

actual suspend fun ensureSpotifyConnectClientAvailable(): Boolean = ensureSpotifyDesktopClientRunning()

actual fun openUrl(url: String) {
    val openedWithDesktop = Desktop.isDesktopSupported() && runCatching {
        Desktop.getDesktop().browse(URI(url))
    }.isSuccess
    if (!openedWithDesktop) {
        runCatching { ProcessBuilder("xdg-open", url).start() }
            .getOrElse { error("Could not open the system browser: ${it.message}") }
    }
}

actual fun platformLabel(): String = "Linux"

actual fun listenForOAuthRedirect(port: Int, path: String): String = awaitOAuthRedirect(port, path)

actual suspend fun authenticateSpotify(authorizationUrl: String, redirectUri: String): String? =
    authenticateWithLoopbackServer(authorizationUrl, redirectUri)

actual fun usesLocalOAuthListener(): Boolean = true

actual fun defaultLocalMusicFolder(): String =
    Paths.get(System.getProperty("user.home", "."), "Music")
        .toAbsolutePath()
        .normalize()
        .toString()

actual fun supportsMusicFolderPicker(): Boolean = true

actual fun pickMusicFolder(): String? {
    pickWithZenity()?.let { return it }
    pickWithKdialog()?.let { return it }
    return pickWithSwing()
}

private fun pickWithZenity(): String? {
    val zenity = findExecutable("zenity") ?: return null
    return runCatching {
        val process = ProcessBuilder(
            zenity,
            "--file-selection",
            "--directory",
            "--title=Choose music folder",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val stdout = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) null else parsePickedDirectory(stdout)
    }.getOrNull()
}

private fun pickWithKdialog(): String? {
    val kdialog = findExecutable("kdialog") ?: return null
    return runCatching {
        val process = ProcessBuilder(
            kdialog,
            "--getexistingdirectory",
            System.getProperty("user.home", "."),
            "--title",
            "Choose music folder",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val stdout = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) null else parsePickedDirectory(stdout)
    }.getOrNull()
}

/** Keep the last absolute path-looking line; ignore GTK/tool chatter on stdout. */
private fun parsePickedDirectory(stdout: String): String? {
    val candidate = stdout
        .lineSequence()
        .map(String::trim)
        .filter { it.startsWith('/') }
        .lastOrNull()
        ?: return null
    val path = Paths.get(candidate).toAbsolutePath().normalize()
    return path.takeIf { Files.isDirectory(path) }?.toString()
}

private fun pickWithSwing(): String? {
    fun choose(): String? {
        val frame = javax.swing.JFrame().apply {
            title = "Kainos Player"
            isAlwaysOnTop = true
            setLocationRelativeTo(null)
            isVisible = true
            toFront()
        }
        return try {
            val chooser = javax.swing.JFileChooser().apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Choose music folder"
                isMultiSelectionEnabled = false
            }
            val result = chooser.showOpenDialog(frame)
            if (result != javax.swing.JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile?.absoluteFile?.canonicalFile?.path
        } finally {
            frame.isVisible = false
            frame.dispose()
        }
    }
    return if (javax.swing.SwingUtilities.isEventDispatchThread()) {
        choose()
    } else {
        var selected: String? = null
        javax.swing.SwingUtilities.invokeAndWait { selected = choose() }
        selected
    }
}

private fun findExecutable(name: String): String? {
    val path = System.getenv("PATH") ?: return null
    return path.split(':').firstOrNull { dir ->
        val file = java.io.File(dir, name)
        file.canExecute()
    }?.let { java.io.File(it, name).absolutePath }
}

private class FileTokenStore(private val path: Path) : TokenStore {
    override suspend fun read(provider: ProviderId): AuthTokens? {
        val all = readAll()
        return all[provider.name]
    }

    override suspend fun write(provider: ProviderId, tokens: AuthTokens) {
        val all = readAll().toMutableMap()
        all[provider.name] = tokens
        path.writeText(json.encodeToString(all))
    }

    override suspend fun clear(provider: ProviderId) {
        val all = readAll().toMutableMap()
        all.remove(provider.name)
        path.writeText(json.encodeToString(all))
    }

    private fun readAll(): Map<String, AuthTokens> {
        if (!path.exists()) return emptyMap()
        return json.decodeFromString(path.readText())
    }
}

private class FileSettingsStore(private val path: Path) : SettingsStore {
    override suspend fun read(): AppSettings {
        if (!path.exists()) return AppSettings()
        return json.decodeFromString(path.readText())
    }

    override suspend fun write(settings: AppSettings) {
        path.writeText(json.encodeToString(settings))
    }
}
