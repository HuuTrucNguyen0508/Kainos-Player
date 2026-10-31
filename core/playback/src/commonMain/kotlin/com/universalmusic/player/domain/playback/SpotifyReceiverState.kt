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
