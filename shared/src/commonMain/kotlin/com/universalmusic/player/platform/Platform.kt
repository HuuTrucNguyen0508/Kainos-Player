package com.universalmusic.player.platform

import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.domain.playback.PlaybackEngine
import io.ktor.client.HttpClient

expect fun currentTimeMillis(): Long

expect fun sha256Bytes(bytes: ByteArray): ByteArray

expect fun encodeUrl(value: String): String

expect fun createHttpClient(): HttpClient

expect fun createTokenStore(): TokenStore

expect fun createSettingsStore(): SettingsStore

expect fun loadAppConfig(): AppConfig

expect fun createPlaybackEngine(spotifyStarter: suspend (String) -> Unit): PlaybackEngine

expect fun openUrl(url: String)

expect fun platformLabel(): String

expect fun listenForOAuthRedirect(port: Int, path: String = "/callback"): String

expect fun usesLocalOAuthListener(): Boolean
