package com.universalmusic.player.domain.playback

import com.universalmusic.player.platform.SpotifyReceiverEvent

internal fun spotifyReceiverStatus(kind: SpotifyReceiverEvent.Kind, current: EngineStatus): EngineStatus = when (kind) {
    SpotifyReceiverEvent.Kind.PLAYING -> EngineStatus.PLAYING
    SpotifyReceiverEvent.Kind.BUFFERING -> EngineStatus.BUFFERING
    SpotifyReceiverEvent.Kind.PAUSED, SpotifyReceiverEvent.Kind.STOPPED -> EngineStatus.PAUSED
    SpotifyReceiverEvent.Kind.END_OF_TRACK -> EngineStatus.ENDED
    SpotifyReceiverEvent.Kind.UNAVAILABLE -> EngineStatus.FAILED
    else -> current
}
