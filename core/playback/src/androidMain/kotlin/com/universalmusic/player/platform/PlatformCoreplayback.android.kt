package com.universalmusic.player.platform

import com.universalmusic.player.domain.playback.LoudnessLookup
import com.universalmusic.player.domain.playback.PlaybackEngine

actual fun createPlaybackEngine(spotify: SpotifyPlaybackController, loudness: LoudnessLookup): PlaybackEngine =
    AndroidPlaybackEngine(androidContext, spotify, loudness)

actual fun audioPlayerInstalled(): Boolean = true

actual fun bindPlatformMediaControls(
    session: com.universalmusic.player.domain.playback.PlayerSession,
    scope: kotlinx.coroutines.CoroutineScope,
    toggleFavorite: (com.universalmusic.player.domain.model.Track) -> Boolean,
) {
    AndroidMediaControls.bind(session, scope, toggleFavorite)
}

actual fun unbindPlatformMediaControls() {
    AndroidMediaControls.unbind()
}
