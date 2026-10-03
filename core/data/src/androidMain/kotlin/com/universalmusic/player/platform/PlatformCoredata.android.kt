package com.universalmusic.player.platform

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.cache.AndroidHeartedAudioCacheDisk
import com.universalmusic.player.data.cache.AndroidMetadataCacheDisk
import com.universalmusic.player.data.cache.DefaultHeartedAudioCache
import com.universalmusic.player.data.cache.DefaultMetadataArtworkCache
import com.universalmusic.player.data.cache.HeartedAudioCache
import com.universalmusic.player.data.cache.MetadataArtworkCache
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.db.AndroidLegacyJsonSource
import com.universalmusic.player.data.db.DbKainosPlaylistStore
import com.universalmusic.player.data.db.DbLocalLibraryScanCache
import com.universalmusic.player.data.db.DbSessionSnapshotStore
import com.universalmusic.player.data.db.DbUserLibraryStore
import com.universalmusic.player.data.db.KainosDatabase
import com.universalmusic.player.data.db.KainosStorage
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.playlist.KainosPlaylistStore
import com.universalmusic.player.data.session.SessionSnapshotStore
import com.universalmusic.player.data.local.LocalLibraryScanCache
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.domain.model.ProviderId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val kainosStorage by lazy {
    KainosStorage(
        openDriver = {
            AndroidSqliteDriver(
                KainosDatabase.Schema,
                androidContext,
                "kainos.db",
                callback = object : AndroidSqliteDriver.Callback(KainosDatabase.Schema) {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
        },
        legacy = AndroidLegacyJsonSource(androidContext),
        clock = ::currentTimeMillis,
    )
}

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
    install(ContentNegotiation) { json(json) }
}

actual fun createTokenStore(): TokenStore = PrefsStore(androidContext)

actual fun createSettingsStore(): SettingsStore = PrefsSettingsStore(androidContext)

actual fun createUserLibraryStore(): UserLibraryStore = DbUserLibraryStore(kainosStorage)

actual fun createKainosPlaylistStore(): KainosPlaylistStore = DbKainosPlaylistStore(kainosStorage)

actual fun createSessionSnapshotStore(): SessionSnapshotStore = DbSessionSnapshotStore(kainosStorage)

actual fun createMetadataArtworkCache(): MetadataArtworkCache {
    val disk = AndroidMetadataCacheDisk(androidContext)
    return DefaultMetadataArtworkCache(
        disk = disk,
        downloadArtwork = ::downloadArtworkBytes,
    )
}

actual fun createHeartedAudioCache(): HeartedAudioCache =
    DefaultHeartedAudioCache(AndroidHeartedAudioCacheDisk(androidContext))

private fun downloadArtworkBytes(url: String): ByteArray? = runCatching {
    java.net.URI(url).toURL().openStream().use { it.readBytes() }
}.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= 2 * 1024 * 1024 }

actual fun createLocalLibraryScanCache(): LocalLibraryScanCache? =
    DbLocalLibraryScanCache(kainosStorage)

actual fun loadAppConfig(): AppConfig {
    val prefs = androidContext.getSharedPreferences("ump_config", Context.MODE_PRIVATE)
    return AppConfig(
        spotifyClientId = System.getenv("SPOTIFY_CLIENT_ID") ?: prefs.getString("spotifyClientId", null),
        spotifyRedirectUri = System.getenv("SPOTIFY_REDIRECT_URI")
            ?: "http://127.0.0.1:43821/callback",
        youtubeDataApiKey = System.getenv("YOUTUBE_DATA_API_KEY") ?: prefs.getString("youtubeDataApiKey", null),
    )
}

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
