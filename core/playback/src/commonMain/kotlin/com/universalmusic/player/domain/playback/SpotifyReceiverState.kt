package com.universalmusic.player.domain.playback

import com.universalmusic.player.platform.SpotifyReceiverEvent

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
