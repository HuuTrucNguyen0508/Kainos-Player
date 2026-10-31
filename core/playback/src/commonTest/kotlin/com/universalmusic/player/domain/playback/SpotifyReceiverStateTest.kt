package com.universalmusic.player.domain.playback

import com.universalmusic.player.platform.SpotifyReceiverEvent.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyReceiverStateTest {
    @Test fun receiverStallFreezesPlaybackAndOnlyReceiverEndCompletesIt() {
        assertEquals(EngineStatus.PAUSED, spotifyReceiverStatus(Kind.PAUSED, EngineStatus.PLAYING))
        assertEquals(EngineStatus.PAUSED, spotifyReceiverStatus(Kind.POSITION_CORRECTION, EngineStatus.PAUSED))
        assertEquals(EngineStatus.PLAYING, spotifyReceiverStatus(Kind.PLAYING, EngineStatus.PAUSED))
        assertEquals(EngineStatus.ENDED, spotifyReceiverStatus(Kind.END_OF_TRACK, EngineStatus.PLAYING))
        assertEquals(EngineStatus.FAILED, spotifyReceiverStatus(Kind.UNAVAILABLE, EngineStatus.PLAYING))
    }
}
