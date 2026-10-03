package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import kotlinx.coroutines.CoroutineScope

expect fun createPlaybackEngine(spotify: SpotifyPlaybackController): PlaybackEngine

expect fun bindPlatformMediaControls(
    session: PlayerSession,
    scope: CoroutineScope,
    toggleFavorite: (Track) -> Boolean,
)

expect fun unbindPlatformMediaControls()
