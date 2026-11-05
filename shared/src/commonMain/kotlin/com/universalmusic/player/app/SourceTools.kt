package com.universalmusic.player.app

import com.universalmusic.player.platform.SpotifyReceiverSetup

/** Programs a source depends on. Optimistic until checked, so nobody is told to install something they have. */
data class SourceTools(
    val audioPlayer: Boolean = true,
    val youtubeExtractor: Boolean = true,
    val spotifyReceiver: SpotifyReceiverSetup = SpotifyReceiverSetup.READY,
)
