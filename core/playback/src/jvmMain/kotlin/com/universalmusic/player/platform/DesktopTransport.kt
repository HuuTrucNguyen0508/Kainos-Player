package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.EngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the mpv and Spotify paths of the desktop engine share: the published state and the facts
 * about what is playing. Mutable members are guarded by [lock], which is also the engine's lock
 * for every transport decision.
 */
internal class DesktopTransport {
    val lock = Any()
    private val published = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = published.asStateFlow()
    val current: EngineState get() = published.value

    var activeGeneration: Long = 0L
    var userPaused = false
    var handle: PlaybackHandle? = null
    var quality: AudioQuality? = null

    /** Identifies the newest start request; a start holding an older value must abandon itself. */
    var requestToken = 0L
        private set

    fun publish(state: EngineState) {
        published.value = state.copy(playGeneration = activeGeneration)
    }

    fun invalidatePendingStartLocked() {
        requestToken++
    }

    fun spotifyIsActive(): Boolean = synchronized(lock) {
        (handle as? PlaybackHandle.ProviderPlayback)?.provider == ProviderId.SPOTIFY
    }
}
