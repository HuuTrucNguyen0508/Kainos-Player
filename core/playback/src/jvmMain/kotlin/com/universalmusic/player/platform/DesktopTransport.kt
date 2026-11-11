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
import java.util.Locale
import kotlin.math.pow

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
    /** Gain the level filter applies to [handle]'s file, from tags or analysis; null when it has neither, and for streams. */
    var replayGain: ReplayGain? = null
    var levellingEnabled = false

    /** What levelling does to [handle] now. Only local files get mpv's levelling filter. */
    fun levellingLocked(): Levelling? {
        val url = (handle as? PlaybackHandle.Url)?.url ?: return null
        return when {
            !levellingEnabled -> Levelling.Off
            !isLocalMediaUrl(url) -> Levelling.Untagged
            else -> replayGain?.let { Levelling.Applied(VolumeLevelling.appliedGainDb(it), it.source) } ?: Levelling.Untagged
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

/**
 * mpv's level filter for one file: its gain as an explicit volume, or unity when levelling is off
 * or the file has no gain. Off is the same filter at unity: both output float, so a toggle
 * replaces the filter in place. Removing it changes the output format, and mpv reopens the output
 * and drops its buffer. A gapless successor carries its own copy as a per-file `af-add`, which
 * replaces this labelled filter from the successor's first sample and leaves other filters alone.
 */
internal fun levellingFilter(enabled: Boolean, gain: ReplayGain?): String {
    val factor = gain?.takeIf { enabled }?.let { 10.0.pow(VolumeLevelling.appliedGainDb(it) / 20) } ?: 1.0
    return "@kainos-level:lavfi=[volume=${String.format(Locale.ROOT, "%.6f", factor)}]"
}
