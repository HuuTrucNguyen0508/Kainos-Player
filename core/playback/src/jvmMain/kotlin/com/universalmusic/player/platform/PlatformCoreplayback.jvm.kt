package com.universalmusic.player.platform

import com.universalmusic.player.domain.playback.LoudnessLookup
import com.universalmusic.player.domain.playback.PlaybackEngine

actual fun createPlaybackEngine(spotify: SpotifyPlaybackController, loudness: LoudnessLookup): PlaybackEngine =
    DesktopPlaybackEngine(spotify, loudness = loudness)

actual fun audioPlayerInstalled(): Boolean = SystemDesktopPlaybackRuntime.findOnPath("mpv") != null

private var mprisController: MprisController? = null

actual fun bindPlatformMediaControls(
    session: com.universalmusic.player.domain.playback.PlayerSession,
    scope: kotlinx.coroutines.CoroutineScope,
    @Suppress("UNUSED_PARAMETER") toggleFavorite: (com.universalmusic.player.domain.model.Track) -> Boolean,
) {
    mprisController?.stop()
    mprisController = MprisController(session, scope).also { it.start() }
}

actual fun unbindPlatformMediaControls() {
    mprisController?.stop()
    mprisController = null
}
