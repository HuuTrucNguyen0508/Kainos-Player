package com.universalmusic.player.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.PlaybackEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.security.MessageDigest

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal lateinit var androidContext: Context

fun initAndroidPlatform(context: Context) {
    androidContext = context.applicationContext
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun sha256Bytes(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun encodeUrl(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(json) }
}

actual fun createTokenStore(): TokenStore = PrefsStore(androidContext)

actual fun createSettingsStore(): SettingsStore = PrefsSettingsStore(androidContext)

actual fun loadAppConfig(): AppConfig {
    val prefs = androidContext.getSharedPreferences("ump_config", Context.MODE_PRIVATE)
    return AppConfig(
        spotifyClientId = System.getenv("SPOTIFY_CLIENT_ID") ?: prefs.getString("spotifyClientId", null),
        spotifyRedirectUri = System.getenv("SPOTIFY_REDIRECT_URI")
            ?: "universalmusic://spotify-callback",
        youtubeDataApiKey = System.getenv("YOUTUBE_DATA_API_KEY") ?: prefs.getString("youtubeDataApiKey", null),
        soundCloudClientId = System.getenv("SOUNDCLOUD_CLIENT_ID") ?: prefs.getString("soundCloudClientId", null),
        soundCloudClientSecret = System.getenv("SOUNDCLOUD_CLIENT_SECRET") ?: prefs.getString("soundCloudClientSecret", null),
        soundCloudRedirectUri = System.getenv("SOUNDCLOUD_REDIRECT_URI")
            ?: "universalmusic://soundcloud-callback",
    )
}

actual fun createPlaybackEngine(spotifyStarter: suspend (String) -> Unit): PlaybackEngine =
    AndroidPlaybackEngine(androidContext, spotifyStarter)

actual fun openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    androidContext.startActivity(intent)
}

actual fun platformLabel(): String = "Android"

actual fun listenForOAuthRedirect(port: Int, path: String): String {
    error("Android completes OAuth through the universalmusic:// callback, not a localhost server.")
}

actual fun usesLocalOAuthListener(): Boolean = false

private class PrefsStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("ump_tokens", Context.MODE_PRIVATE)

    override suspend fun read(provider: ProviderId): AuthTokens? {
        val raw = prefs.getString(provider.name, null) ?: return null
        return json.decodeFromString(raw)
    }

    override suspend fun write(provider: ProviderId, tokens: AuthTokens) {
        prefs.edit().putString(provider.name, json.encodeToString(tokens)).apply()
    }

    override suspend fun clear(provider: ProviderId) {
        prefs.edit().remove(provider.name).apply()
    }
}

private class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("ump_settings", Context.MODE_PRIVATE)

    override suspend fun read(): AppSettings {
        val raw = prefs.getString("settings", null) ?: return AppSettings()
        return json.decodeFromString(raw)
    }

    override suspend fun write(settings: AppSettings) {
        prefs.edit().putString("settings", json.encodeToString(settings)).apply()
    }
}
