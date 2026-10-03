package com.universalmusic.player.domain.playback

data class SpotifyObservedPlayback(
    val isPlaying: Boolean,
    val progressMs: Long?,
    val trackId: String?,
)
