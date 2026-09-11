package com.universalmusic.player.platform

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.net.Uri
import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.cache.AndroidHeartedAudioCacheDisk
import com.universalmusic.player.data.cache.AndroidMetadataCacheDisk
import com.universalmusic.player.data.cache.DefaultHeartedAudioCache
import com.universalmusic.player.data.cache.DefaultMetadataArtworkCache
import com.universalmusic.player.data.cache.HeartedAudioCache
import com.universalmusic.player.data.cache.MetadataArtworkCache
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.library.AndroidUserLibraryStore
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.local.AndroidLocalLibraryScanCache
import com.universalmusic.player.data.local.LocalLibraryRootMode
import com.universalmusic.player.data.local.LocalLibraryScanCache
import com.universalmusic.player.data.local.LocalLibraryScanConfig
import com.universalmusic.player.data.local.LocalTrack
import com.universalmusic.player.data.local.LocalTrackSource
import com.universalmusic.player.data.local.MediaStoreLocalTrackSource
import com.universalmusic.player.data.local.SafLocalTrackSource
import com.universalmusic.player.data.local.dedupeKey
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.PlaybackEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal lateinit var androidContext: Context

/** Activity registers OpenDocumentTree and assigns this launcher. */
var launchMusicFolderPicker: (() -> Unit)? = null

object MusicFolderPickerRelay {
    @Volatile
    var pending: CompletableDeferred<String?>? = null

    fun complete(uri: String?) {
        pending?.complete(uri)
        pending = null
    }
}

fun initAndroidPlatform(context: Context) {
    androidContext = context.applicationContext
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun sha256Bytes(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(SecureRandom()::nextBytes)

actual fun encodeUrl(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

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

actual fun createUserLibraryStore(): UserLibraryStore =
    AndroidUserLibraryStore(androidContext)

actual fun createMetadataArtworkCache(): MetadataArtworkCache {
    val disk = AndroidMetadataCacheDisk(androidContext)
    return DefaultMetadataArtworkCache(
        disk = disk,
        downloadArtwork = ::downloadArtworkBytes,
    )
}

actual fun createHeartedAudioCache(): HeartedAudioCache =
    DefaultHeartedAudioCache(AndroidHeartedAudioCacheDisk(androidContext))

actual fun createYouTubeAudioDownloader(streams: YouTubeStreamResolver): YouTubeAudioDownloader =
    AndroidYouTubeAudioDownloader(streams)

private fun downloadArtworkBytes(url: String): ByteArray? = runCatching {
    java.net.URI(url).toURL().openStream().use { it.readBytes() }
}.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= 2 * 1024 * 1024 }

actual fun createLocalTrackSource(config: () -> LocalLibraryScanConfig): LocalTrackSource =
    LocalTrackSource {
        val scan = config()
        val merged = linkedMapOf<String, LocalTrack>()
        fun ingest(tracks: List<LocalTrack>) {
            for (track in tracks) {
                merged.putIfAbsent(track.dedupeKey(), track)
            }
        }
        val revoked = mutableListOf<String>()
        when (scan.mode) {
            LocalLibraryRootMode.USE_DEFAULTS -> {
                if (scan.includeMediaStore) {
                    ingest(MediaStoreLocalTrackSource(androidContext).scan())
                }
            }
            LocalLibraryRootMode.EXPLICIT -> {
                if (scan.folders.isNotEmpty()) {
                    val saf = SafLocalTrackSource(androidContext) { scan.folders }
                    ingest(saf.scan())
                    revoked += saf.lastRevokedFolders
                }
                if (scan.includeMediaStore) {
                    ingest(MediaStoreLocalTrackSource(androidContext).scan())
                }
            }
        }
        if (merged.isEmpty() && revoked.isNotEmpty()) {
            throw IllegalStateException(
                "Lost access to music folder(s): ${revoked.joinToString()}. " +
                    "Remove them in Settings or re-add the folder to restore access.",
            )
        }
        merged.values.toList()
    }

actual fun createLocalLibraryScanCache(): LocalLibraryScanCache? =
    AndroidLocalLibraryScanCache(androidContext)

actual fun loadAppConfig(): AppConfig {
    val prefs = androidContext.getSharedPreferences("ump_config", Context.MODE_PRIVATE)
    return AppConfig(
        spotifyClientId = System.getenv("SPOTIFY_CLIENT_ID") ?: prefs.getString("spotifyClientId", null),
        spotifyRedirectUri = System.getenv("SPOTIFY_REDIRECT_URI")
            ?: "http://127.0.0.1:43821/callback",
        youtubeDataApiKey = System.getenv("YOUTUBE_DATA_API_KEY") ?: prefs.getString("youtubeDataApiKey", null),
    )
}

actual fun createPlaybackEngine(spotify: SpotifyPlaybackController): PlaybackEngine =
    AndroidPlaybackEngine(androidContext, spotify)

actual fun createYouTubeStreamResolver(): YouTubeStreamResolver = AndroidYouTubeStreamResolver()

actual fun createSpotifyWebPlaybackHost(tokenSupplier: SpotifyTokenSupplier): SpotifyWebPlaybackHost =
    AndroidLibrespotPlaybackHost()

actual fun requiresExplicitSpotifyDevice(): Boolean = false

actual suspend fun ensureSpotifyConnectClientAvailable(): Boolean = withContext(Dispatchers.Main) {
    val intent = androidContext.packageManager.getLaunchIntentForPackage("com.spotify.music")
        ?: return@withContext false
    runCatching {
        androidContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

actual fun openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    androidContext.startActivity(intent)
}

actual fun platformLabel(): String = "Android"

actual fun listenForOAuthRedirect(port: Int, path: String): String {
    error("Android completes OAuth through the universalmusic:// callback, not a localhost server.")
}

actual suspend fun authenticateSpotify(authorizationUrl: String, redirectUri: String): String? {
    val redirect = URI(redirectUri)
    if (redirect.scheme != "http" || redirect.host != "127.0.0.1") {
        openUrl(authorizationUrl)
        return null
    }
    require(redirect.port in 1..65535) { "Spotify redirect must include a valid port" }
    return authenticateSpotifyViaWebView(authorizationUrl, redirectUri)
}

private suspend fun authenticateSpotifyViaWebView(
    authorizationUrl: String,
    redirectUri: String,
): String? {
    val deferred = CompletableDeferred<String?>()
    SpotifyAuthRelay.pending = deferred
    withContext(Dispatchers.Main) {
        val intent = Intent(androidContext, SpotifyAuthActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SpotifyAuthActivity.EXTRA_AUTH_URL, authorizationUrl)
            putExtra(SpotifyAuthActivity.EXTRA_REDIRECT_URI, redirectUri)
        }
        androidContext.startActivity(intent)
    }
    return withTimeoutOrNull(180_000) { deferred.await() }
}

@Suppress("unused")
private suspend fun authenticateSpotifyViaBrowser(
    authorizationUrl: String,
    redirectUri: String,
): String? {
    val redirect = URI(redirectUri)
    val expectedPath = redirect.rawPath.takeUnless { it.isNullOrBlank() } ?: "/"
    return withContext(Dispatchers.IO) {
        runInterruptible {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), redirect.port))
                server.soTimeout = 1_000
                openUrl(authorizationUrl)
                val deadline = System.currentTimeMillis() + 180_000
                while (System.currentTimeMillis() < deadline) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedException("Spotify login was cancelled")
                    }
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    socket.use {
                        it.soTimeout = 5_000
                        val reader = it.getInputStream().bufferedReader()
                        val target = reader.readLine()
                            ?.split(' ')
                            ?.getOrNull(1)
                            ?: return@use
                        var header: String?
                        do {
                            header = reader.readLine()
                        } while (!header.isNullOrEmpty())
                        val request = URI(target)
                        val accepted = request.rawPath == expectedPath
                        val message = if (accepted) {
                            "Authorization received. Return to Kainos Player to finish connecting."
                        } else {
                            "Not found"
                        }
                        val status = if (accepted) "200 OK" else "404 Not Found"
                        val payload = message.encodeToByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                            write(payload)
                            flush()
                        }
                        if (accepted) return@runInterruptible "http://127.0.0.1:${redirect.port}$target"
                    }
                }
                error("Spotify login timed out after 3 minutes")
            }
        }
    }
}

actual fun usesLocalOAuthListener(): Boolean = false

actual fun defaultLocalMusicFolder(): String = ""

actual fun supportsMusicFolderPicker(): Boolean = true

actual suspend fun pickMusicFolder(): String? {
    val deferred = CompletableDeferred<String?>()
    MusicFolderPickerRelay.pending?.cancel()
    MusicFolderPickerRelay.pending = deferred
    val launcher = launchMusicFolderPicker
    if (launcher == null) {
        MusicFolderPickerRelay.pending = null
        return null
    }
    withContext(Dispatchers.Main) { launcher.invoke() }
    return withTimeoutOrNull(180_000) { deferred.await() }.also {
        if (MusicFolderPickerRelay.pending === deferred) {
            MusicFolderPickerRelay.pending = null
        }
    }
}

actual fun releaseMusicFolderAccess(folder: String) {
    val uri = runCatching { Uri.parse(folder) }.getOrNull() ?: return
    runCatching {
        androidContext.contentResolver.releasePersistableUriPermission(
            uri,
            FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

actual fun bindPlatformMediaControls(
    session: com.universalmusic.player.domain.playback.PlayerSession,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    AndroidMediaControls.bind(session, scope)
}

actual fun unbindPlatformMediaControls() {
    AndroidMediaControls.unbind()
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
