package com.universalmusic.player.platform

import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.PlaybackEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

actual fun encodeUrl(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(json) }
}

actual fun createTokenStore(): TokenStore = FileTokenStore(configDir() / "tokens.json")

actual fun createSettingsStore(): SettingsStore = FileSettingsStore(configDir() / "settings.json")

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
        soundCloudClientId = value("SOUNDCLOUD_CLIENT_ID"),
        soundCloudClientSecret = value("SOUNDCLOUD_CLIENT_SECRET"),
        soundCloudRedirectUri = value("SOUNDCLOUD_REDIRECT_URI") ?: "http://127.0.0.1:43822/callback",
    )
}

actual fun createPlaybackEngine(spotifyStarter: suspend (String) -> Unit): PlaybackEngine =
    DesktopPlaybackEngine(spotifyStarter)

actual fun openUrl(url: String) {
    if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI(url))
    }
}

actual fun platformLabel(): String = "Linux"

actual fun listenForOAuthRedirect(port: Int, path: String): String = awaitOAuthRedirect(port, path)

actual fun usesLocalOAuthListener(): Boolean = true

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
