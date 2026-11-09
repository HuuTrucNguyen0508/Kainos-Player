package com.universalmusic.player.domain.playback

import com.universalmusic.player.platform.SpotifyReceiverEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** How close to the end a receiver event must land to mean the track finished. */
internal const val SPOTIFY_END_WINDOW_MS = 5_000L

/**
 * librespot reports playback ended for skips as well as real ends, then can roll into a track
 * Kainos never queued, so neither event proves completion without the position near the end.
 */
internal fun spotifyTrackFinished(
    kind: SpotifyReceiverEvent.Kind,
    sameTrack: Boolean,
    confirmed: Boolean,
    positionMs: Long,
    durationMs: Long?,
): Boolean {
    val nearEnd = durationMs != null && positionMs >= durationMs - SPOTIFY_END_WINDOW_MS
    return when {
        sameTrack -> kind == SpotifyReceiverEvent.Kind.END_OF_TRACK && (durationMs == null || nearEnd)
        else -> confirmed && kind == SpotifyReceiverEvent.Kind.PLAYING && nearEnd
    }
}

internal fun spotifyReceiverStatus(kind: SpotifyReceiverEvent.Kind, current: EngineStatus): EngineStatus = when (kind) {
    SpotifyReceiverEvent.Kind.PLAYING -> EngineStatus.PLAYING
    SpotifyReceiverEvent.Kind.BUFFERING -> EngineStatus.BUFFERING
    SpotifyReceiverEvent.Kind.PAUSED, SpotifyReceiverEvent.Kind.STOPPED -> EngineStatus.PAUSED
    SpotifyReceiverEvent.Kind.END_OF_TRACK -> EngineStatus.ENDED
    SpotifyReceiverEvent.Kind.UNAVAILABLE -> EngineStatus.FAILED
    else -> current
}

/** Only the receiver actually playing the requested track ends the start watchdog, as on desktop. */
internal fun spotifyReceiverConfirmsStart(kind: SpotifyReceiverEvent.Kind): Boolean = kind == SpotifyReceiverEvent.Kind.PLAYING

/** How long a Spotify start may wait for the receiver to confirm before it counts as failed. */
internal const val SPOTIFY_START_DEADLINE_MS = 30_000L

internal enum class SpotifyStartCheck { PLAYING, WAIT, FAIL }

/**
 * A start the receiver has not confirmed. Only an observation of [trackId] itself playing proves sound;
 * one that fails, says not playing, or shows another track waits until the deadline, then fails so
 * the session can skip it.
 */
internal fun spotifyStartCheck(observed: SpotifyObservedPlayback?, trackId: String, waitedMs: Long): SpotifyStartCheck = when {
    observed?.isPlaying == true && observed.trackId == trackId.removePrefix("spotify:track:") -> SpotifyStartCheck.PLAYING
    waitedMs >= SPOTIFY_START_DEADLINE_MS -> SpotifyStartCheck.FAIL
    else -> SpotifyStartCheck.WAIT
}

/** Poll gap while the in-app receiver may still confirm the start with its own event. */
internal const val SPOTIFY_START_POLL_MS = 10_000L

/** Poll gap with no in-app receiver: only the Web API can confirm, so ask sooner. */
internal const val SPOTIFY_START_POLL_NO_RECEIVER_MS = 3_000L

/** A stalled observation must not hold the start past the deadline by more than this. */
internal const val SPOTIFY_OBSERVE_TIMEOUT_MS = 5_000L

internal sealed interface SpotifyStartOutcome {
    data class Playing(val progressMs: Long?) : SpotifyStartOutcome
    data object Failed : SpotifyStartOutcome
    /** The start was confirmed, paused, stopped or replaced while waiting; leave the state alone. */
    data object Abandoned : SpotifyStartOutcome
}

/**
 * Watches a Spotify start the receiver has not confirmed: polls [observe] every [pollMs] until it
 * shows [trackId] playing, or fails once [elapsedMs] passes [SPOTIFY_START_DEADLINE_MS].
 */
internal suspend fun awaitSpotifyStart(
    trackId: String,
    pollMs: Long,
    observe: (suspend () -> SpotifyObservedPlayback?)?,
    stillWaiting: () -> Boolean,
    elapsedMs: () -> Long,
): SpotifyStartOutcome {
    while (true) {
        // Slow observations must not push the last poll past the deadline.
        delay(pollMs.coerceAtMost((SPOTIFY_START_DEADLINE_MS - elapsedMs()).coerceAtLeast(0)))
        if (!stillWaiting()) return SpotifyStartOutcome.Abandoned
        val observed = observe?.let { call ->
            runCatching { withTimeoutOrNull(SPOTIFY_OBSERVE_TIMEOUT_MS) { call() } }.getOrNull()
        }
        if (!stillWaiting()) return SpotifyStartOutcome.Abandoned
        when (spotifyStartCheck(observed, trackId, elapsedMs())) {
            SpotifyStartCheck.WAIT -> continue
            SpotifyStartCheck.PLAYING -> return SpotifyStartOutcome.Playing(observed?.progressMs)
            SpotifyStartCheck.FAIL -> return SpotifyStartOutcome.Failed
        }
    }
}
