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

interface SpotifyWebPlaybackHost {
    val state: StateFlow<SpotifyWebPlaybackState>
    val requiresStreamingScope: Boolean get() = true
    suspend fun ensureDeviceReady(): SpotifyWebPlaybackDevice?
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
