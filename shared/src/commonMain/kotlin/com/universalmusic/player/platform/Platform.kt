package com.universalmusic.player.platform

import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.data.local.LocalTrackSource
import com.universalmusic.player.domain.playback.PlaybackEngine
import io.ktor.client.HttpClient

expect fun currentTimeMillis(): Long

expect fun sha256Bytes(bytes: ByteArray): ByteArray

expect fun secureRandomBytes(size: Int): ByteArray

expect fun encodeUrl(value: String): String

expect fun createHttpClient(): HttpClient

expect fun createTokenStore(): TokenStore

expect fun createSettingsStore(): SettingsStore

expect fun createLocalTrackSource(configuredFolders: () -> List<String>): LocalTrackSource

expect fun loadAppConfig(): AppConfig

data class SpotifyPlaybackController(
    val play: suspend (trackId: String) -> Unit,
    val pause: suspend () -> Unit,
    val resume: suspend () -> Unit,
    val seekTo: suspend (positionMs: Long) -> Unit,
)

expect fun createPlaybackEngine(spotify: SpotifyPlaybackController): PlaybackEngine

expect fun createYouTubeStreamResolver(): YouTubeStreamResolver

/** Best-effort: start the desktop Spotify client when Connect has no device. */
expect suspend fun ensureSpotifyConnectClientAvailable(): Boolean

expect fun openUrl(url: String)

expect fun platformLabel(): String

expect fun listenForOAuthRedirect(port: Int, path: String = "/callback"): String

/** Opens authorization after a desktop callback listener is ready. Android returns null and uses its app link. */
expect suspend fun authenticateSpotify(authorizationUrl: String, redirectUri: String): String?

expect fun usesLocalOAuthListener(): Boolean

/** Absolute path of the platform default music folder, or blank when not applicable. */
expect fun defaultLocalMusicFolder(): String

expect fun supportsMusicFolderPicker(): Boolean

/** Opens a native directory picker. Returns an absolute path, or null if cancelled / unsupported. */
expect fun pickMusicFolder(): String?
