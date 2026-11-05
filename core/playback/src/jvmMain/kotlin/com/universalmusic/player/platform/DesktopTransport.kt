package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.Levelling
import com.universalmusic.player.domain.playback.ReplayGain
import com.universalmusic.player.domain.playback.VolumeLevelling
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
    /** ReplayGain ffmpeg applies to [handle]'s file; null for untagged files and streams. */
    var replayGain: ReplayGain? = null
    var levellingEnabled = false

    /** What levelling does to [handle] now. Only local files get mpv's levelling filter. */
    fun levellingLocked(): Levelling? {
        val url = (handle as? PlaybackHandle.Url)?.url ?: return null
        return when {
            !levellingEnabled -> Levelling.Off
            !isLocalMediaUrl(url) -> Levelling.Untagged
            else -> replayGain?.let { Levelling.Applied(VolumeLevelling.appliedGainDb(it)) } ?: Levelling.Untagged
        }
    }

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
