package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Track
import kotlin.coroutines.CoroutineContext

/**
 * The current playback attempt. Session thread only. Asynchronous work captures [generation]
 * before it leaves the session thread and re-checks it before touching playback.
 */
internal class PlaybackTurn {
    /** Bumped on every play/stop transition so late ENDED/FAILED from a prior track are ignored. */
    var generation: Long = 0L
        private set

    /** True only after the current generation has successfully started engine playback. */
    var completionArmed: Boolean = false
        private set

    fun begin() {
        generation++
        completionArmed = false
    }

    /** The engine has already crossed into [generation] on its own. */
    fun adoptSeamless(generation: Long) {
        this.generation = generation
    }

    fun arm() {
        completionArmed = true
    }

    fun disarm() {
        completionArmed = false
    }
}

/** How a queued track becomes playable audio. Every call belongs in [workContext]. */
internal class TrackSources(
    private val resolver: SourceResolver,
    private val enrichSource: suspend (Track, PlaybackSource) -> PlaybackSource,
    val prepareTrack: (Track) -> Track,
    val workContext: CoroutineContext,
) {
    suspend fun resolve(track: Track, preferences: PlaybackPreferences) = resolver.resolve(track, preferences)

    suspend fun enrich(track: Track, source: PlaybackSource): PlaybackSource = enrichSource(track, source)
}
