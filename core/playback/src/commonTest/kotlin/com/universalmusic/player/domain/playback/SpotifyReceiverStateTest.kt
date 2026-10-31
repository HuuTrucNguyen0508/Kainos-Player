package com.universalmusic.player.domain.playback

import com.universalmusic.player.platform.SpotifyReceiverEvent.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyReceiverStateTest {
    @Test fun receiverStallFreezesPlaybackAndOnlyReceiverEndCompletesIt() {
        assertEquals(EngineStatus.BUFFERING, spotifyReceiverStatus(Kind.BUFFERING, EngineStatus.PLAYING))
        assertEquals(EngineStatus.PAUSED, spotifyReceiverStatus(Kind.PAUSED, EngineStatus.PLAYING))
        assertEquals(EngineStatus.PAUSED, spotifyReceiverStatus(Kind.POSITION_CORRECTION, EngineStatus.PAUSED))
        assertEquals(EngineStatus.PLAYING, spotifyReceiverStatus(Kind.PLAYING, EngineStatus.PAUSED))
        assertEquals(EngineStatus.ENDED, spotifyReceiverStatus(Kind.END_OF_TRACK, EngineStatus.PLAYING))
        assertEquals(EngineStatus.FAILED, spotifyReceiverStatus(Kind.UNAVAILABLE, EngineStatus.PLAYING))
    }

    @Test fun endOfTrackCompletesOnlyNearTheEnd() {
        assertTrue(spotifyTrackFinished(Kind.END_OF_TRACK, sameTrack = true, confirmed = true, positionMs = 179_000, durationMs = 180_000))
        // librespot also reports ended when a skip or replay closes the previous entry.
        assertFalse(spotifyTrackFinished(Kind.END_OF_TRACK, sameTrack = true, confirmed = true, positionMs = 2_000, durationMs = 180_000))
        assertTrue(spotifyTrackFinished(Kind.END_OF_TRACK, sameTrack = true, confirmed = true, positionMs = 2_000, durationMs = null))
    }

    @Test fun rollingIntoAnotherTrackCompletesOnlyAConfirmedTrackNearItsEnd() {
        assertTrue(spotifyTrackFinished(Kind.PLAYING, sameTrack = false, confirmed = true, positionMs = 179_000, durationMs = 180_000))
        assertFalse(spotifyTrackFinished(Kind.PLAYING, sameTrack = false, confirmed = true, positionMs = 60_000, durationMs = 180_000))
        assertFalse(spotifyTrackFinished(Kind.PLAYING, sameTrack = false, confirmed = false, positionMs = 179_000, durationMs = 180_000))
        assertFalse(spotifyTrackFinished(Kind.PAUSED, sameTrack = false, confirmed = true, positionMs = 179_000, durationMs = 180_000))
    }
}
