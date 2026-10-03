package com.universalmusic.player.platform

import com.universalmusic.player.domain.playback.PlaybackEngine

actual fun createPlaybackEngine(spotify: SpotifyPlaybackController): PlaybackEngine =
    AndroidPlaybackEngine(androidContext, spotify)

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
