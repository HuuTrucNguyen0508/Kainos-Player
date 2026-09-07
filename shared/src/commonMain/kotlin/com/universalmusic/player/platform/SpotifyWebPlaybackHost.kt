package com.universalmusic.player.platform

import kotlinx.coroutines.flow.StateFlow

data class SpotifyWebPlaybackDevice(
    val deviceId: String? = null,
    val deviceName: String? = null,
) {
    init {
        require(!deviceId.isNullOrBlank() || !deviceName.isNullOrBlank()) {
            "A Spotify playback device needs an ID or name"
        }
    }
}

sealed interface SpotifyWebPlaybackFailure {
    data object BrowserNotFound : SpotifyWebPlaybackFailure
    data object BrowserLaunchFailed : SpotifyWebPlaybackFailure
    data object HostStartupFailed : SpotifyWebPlaybackFailure
    data object SdkLoadFailed : SpotifyWebPlaybackFailure
    data object AuthenticationFailed : SpotifyWebPlaybackFailure
    data object AccountError : SpotifyWebPlaybackFailure
    data object InitializationError : SpotifyWebPlaybackFailure
    data object PlaybackError : SpotifyWebPlaybackFailure
    data object DeviceRegistrationTimedOut : SpotifyWebPlaybackFailure
    data object BrowserDisconnected : SpotifyWebPlaybackFailure
    data object UnsupportedEnvironment : SpotifyWebPlaybackFailure
    data object ReconnectRequired : SpotifyWebPlaybackFailure
    data object LibrespotNotFound : SpotifyWebPlaybackFailure
    data object LibrespotAuthenticationRequired : SpotifyWebPlaybackFailure
    data class LibrespotExited(val detail: String?) : SpotifyWebPlaybackFailure
    data class Message(val detail: String) : SpotifyWebPlaybackFailure
}

sealed interface SpotifyWebPlaybackState {
    data object Stopped : SpotifyWebPlaybackState
    data object StartingHost : SpotifyWebPlaybackState
    data object LaunchingBrowser : SpotifyWebPlaybackState
    data object WaitingForSdk : SpotifyWebPlaybackState
    data object ConnectingSpotify : SpotifyWebPlaybackState
    data class Ready(val deviceId: String) : SpotifyWebPlaybackState
    data object ActivationRequired : SpotifyWebPlaybackState
    data class Failed(val reason: SpotifyWebPlaybackFailure) : SpotifyWebPlaybackState
}

fun interface SpotifyTokenSupplier {
    suspend fun getValidAccessToken(): String
}

/** Connect receiver display name shared by Linux librespot and Android librespot-java. */
const val KAINOS_SPOTIFY_DEVICE_NAME = "Kainos Player"

/** User-facing hint when librespot cannot open a TCP session to a Spotify access point. */
internal fun describeLibrespotConnectionFailure(detail: String?): String? {
    if (detail.isNullOrBlank()) return null
    val lower = detail.lowercase()
    val accessPointFailure = lower.contains("4070") ||
        (lower.contains("ap-") && lower.contains("spotify.com")) ||
        lower.contains("econnrefused") ||
        lower.contains("failed to connect") ||
        lower.contains("connect failed") ||
        lower.contains("connection refused")
    if (!accessPointFailure) return null
    return buildString {
        append("Could not reach Spotify's in-app playback servers")
        if (lower.contains("4070")) append(" (TCP port 4070)")
        append(". VPNs and some mobile networks block this port. ")
        append("Try turning the VPN off, switching between Wi-Fi and cellular, then run setup again.")
        append(" Kainos retries other Spotify endpoints automatically.")
    }
}

internal fun isRetryableLibrespotConnectFailure(failure: Throwable): Boolean {
    val message = failure.message.orEmpty().lowercase()
    return message.contains("econnrefused") ||
        message.contains("failed to connect") ||
        message.contains("connect failed") ||
        message.contains("connection refused") ||
        message.contains("4070") ||
        message.contains("sockettimeout") ||
        message.contains("timed out")
}

interface SpotifyWebPlaybackHost {
    val state: StateFlow<SpotifyWebPlaybackState>
    val requiresStreamingScope: Boolean get() = true
    suspend fun ensureDeviceReady(): SpotifyWebPlaybackDevice?
    /** One-time receiver setup via librespot OAuth (browser sign-in on Linux and Android). */
    suspend fun prepareAuthentication(): SpotifyWebPlaybackDevice? = ensureDeviceReady()
    suspend fun shutdown()
}

object UnavailableSpotifyWebPlaybackHost : SpotifyWebPlaybackHost {
    private val stopped = kotlinx.coroutines.flow.MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.Stopped)
    override val state: StateFlow<SpotifyWebPlaybackState> = stopped
    override suspend fun ensureDeviceReady(): SpotifyWebPlaybackDevice? = null
    override suspend fun shutdown() = Unit
}

expect fun createSpotifyWebPlaybackHost(tokenSupplier: SpotifyTokenSupplier): SpotifyWebPlaybackHost
