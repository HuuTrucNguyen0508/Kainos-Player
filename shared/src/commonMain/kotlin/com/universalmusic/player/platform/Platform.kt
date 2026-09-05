package com.universalmusic.player.platform

import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.data.local.LocalTrackSource
import com.universalmusic.player.domain.playback.PlaybackEngine
import io.ktor.client.HttpClient

expect fun currentTimeMillis(): Long

expect fun sha256Bytes(bytes: ByteArray): ByteArray

expect fun encodeUrl(value: String): String

expect fun createHttpClient(): HttpClient

expect fun createTokenStore(): TokenStore

expect fun createSettingsStore(): SettingsStore

expect fun createLocalTrackSource(configuredFolders: () -> List<String>): LocalTrackSource

expect fun loadAppConfig(): AppConfig

expect fun createPlaybackEngine(spotifyStarter: suspend (String) -> Unit): PlaybackEngine

expect fun openUrl(url: String)

expect fun platformLabel(): String

expect fun listenForOAuthRedirect(port: Int, path: String = "/callback"): String

expect fun usesLocalOAuthListener(): Boolean

/** Absolute path of the platform default music folder, or blank when not applicable. */
expect fun defaultLocalMusicFolder(): String

expect fun supportsMusicFolderPicker(): Boolean

/** Opens a native directory picker. Returns an absolute path, or null if cancelled / unsupported. */
expect fun pickMusicFolder(): String?
