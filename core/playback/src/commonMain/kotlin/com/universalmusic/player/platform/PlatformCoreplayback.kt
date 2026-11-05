package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import kotlinx.coroutines.CoroutineScope

expect fun createPlaybackEngine(spotify: SpotifyPlaybackController): PlaybackEngine

/** False when the audio player the engine depends on is missing from this machine. */
expect fun audioPlayerInstalled(): Boolean

expect fun bindPlatformMediaControls(
    session: PlayerSession,
    scope: CoroutineScope,
    toggleFavorite: (Track) -> Boolean,
)

expect fun unbindPlatformMediaControls()
