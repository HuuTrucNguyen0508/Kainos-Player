package com.universalmusic.player.platform

import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.cache.MetadataArtworkCache
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.data.local.LocalLibraryScanConfig
import com.universalmusic.player.data.local.LocalLibraryScanCache
import com.universalmusic.player.data.local.LocalTrackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

expect fun currentTimeMillis(): Long

expect fun sha256Bytes(bytes: ByteArray): ByteArray

expect fun secureRandomBytes(size: Int): ByteArray

expect fun encodeUrl(value: String): String

expect fun createHttpClient(): HttpClient

expect fun createTokenStore(): TokenStore

expect fun createSettingsStore(): SettingsStore

expect fun createUserLibraryStore(): UserLibraryStore

expect fun createMetadataArtworkCache(): MetadataArtworkCache

expect fun createHeartedAudioCache(): com.universalmusic.player.data.cache.HeartedAudioCache

expect fun createYouTubeAudioDownloader(
    streams: YouTubeStreamResolver,
): YouTubeAudioDownloader

expect fun createLocalTrackSource(config: () -> LocalLibraryScanConfig): LocalTrackSource

expect fun createLocalLibraryScanCache(): LocalLibraryScanCache?

expect fun loadAppConfig(): AppConfig

/**
 * Desktop librespot path that can still read Spotify-owned algorithmic playlists
 * (Discover Weekly) after the Nov 2024 Web API restriction. Android returns null for now.
 */
expect suspend fun fetchLibrespotDiscoverWeekly(playlistId: String? = null): Playlist?

data class SpotifyPlaybackController(
    val play: suspend (trackId: String) -> Unit,
    val pause: suspend () -> Unit,
    val resume: suspend () -> Unit,
    val seekTo: suspend (positionMs: Long) -> Unit,
)

expect fun createPlaybackEngine(spotify: SpotifyPlaybackController): PlaybackEngine

expect fun createYouTubeStreamResolver(): YouTubeStreamResolver

/** Android requires a user-selected Connect device to avoid playing on another device. */
expect fun requiresExplicitSpotifyDevice(): Boolean

/** Best-effort: open Spotify on this device so it can register with Connect. */
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

/** Opens a native directory picker. Returns a path or tree URI, or null if cancelled / unsupported. */
expect suspend fun pickMusicFolder(): String?

/** Best-effort release of a previously granted folder access (Android SAF). No-op on desktop. */
expect fun releaseMusicFolderAccess(folder: String)

/** Wire platform media controls (Android MediaSession / Linux MPRIS) to [session]. */
expect fun bindPlatformMediaControls(session: PlayerSession, scope: CoroutineScope)

expect fun unbindPlatformMediaControls()

expect fun createHomeLanSyncHub(
    pairing: com.universalmusic.player.data.sync.HomeLanSyncPairing,
    onHearts: suspend (com.universalmusic.player.data.sync.HeartsSyncDocument) -> com.universalmusic.player.data.sync.HeartsSyncDocument,
    vault: com.universalmusic.player.data.sync.HomeLanVaultStore?,
    /**
     * When non-null, hub vault index only advertises these basenames (hearted-only mode).
     * Null means advertise the full vault.
     */
    heartedVaultFileNames: () -> Set<String>?,
): com.universalmusic.player.data.sync.HomeLanSyncHub

expect fun createHomeLanVaultStore(
    vaultRootProvider: () -> String?,
): com.universalmusic.player.data.sync.HomeLanVaultStore

/** Best-effort IPv4 LAN address for pairing URI (desktop); null on Android. */
expect fun detectLanHostAddress(): String?

/**
 * Runs [block] under an Android `dataSync` foreground service when needed.
 * Desktop runs [block] directly.
 */
expect suspend fun <T> withVaultSyncForeground(
    label: String,
    block: suspend () -> T,
): T

/** Install or remove a desktop login autostart entry for hub mode. No-op on Android. */
expect fun setHomeLanHubAutostart(enabled: Boolean)

/** Rematch local-file hearts whose paths went missing after vault sync. Returns count. */
expect suspend fun rematchLocalHeartsAfterVaultSync(): Int
