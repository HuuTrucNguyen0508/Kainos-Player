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

    @Test fun unconfirmedStartFailsAtTheDeadlineInsteadOfFakingPlayback() {
        val requested = "spotify:track:wanted"
        fun seen(playing: Boolean, id: String? = "wanted") = SpotifyObservedPlayback(isPlaying = playing, progressMs = 1_000, trackId = id)
        assertEquals(SpotifyStartCheck.PLAYING, spotifyStartCheck(seen(true), requested, waitedMs = 10_000))
        // An observation that failed is not proof of sound.
        assertEquals(SpotifyStartCheck.WAIT, spotifyStartCheck(null, requested, waitedMs = 10_000))
        assertEquals(SpotifyStartCheck.FAIL, spotifyStartCheck(null, requested, waitedMs = SPOTIFY_START_DEADLINE_MS))
        // A receiver that never starts must not keep the start buffering forever.
        assertEquals(SpotifyStartCheck.WAIT, spotifyStartCheck(seen(false), requested, waitedMs = 20_000))
        assertEquals(SpotifyStartCheck.FAIL, spotifyStartCheck(seen(false), requested, waitedMs = SPOTIFY_START_DEADLINE_MS))
    }

    @Test fun anotherTrackPlayingDoesNotConfirmTheRequestedStart() {
        val requested = "spotify:track:wanted"
        fun seen(id: String?) = SpotifyObservedPlayback(isPlaying = true, progressMs = 1_000, trackId = id)
        assertEquals(SpotifyStartCheck.WAIT, spotifyStartCheck(seen("previous"), requested, waitedMs = 10_000))
        assertEquals(SpotifyStartCheck.WAIT, spotifyStartCheck(seen(null), requested, waitedMs = 10_000))
        assertEquals(SpotifyStartCheck.FAIL, spotifyStartCheck(seen("previous"), requested, waitedMs = SPOTIFY_START_DEADLINE_MS))
        assertEquals(SpotifyStartCheck.PLAYING, spotifyStartCheck(seen("wanted"), "wanted", waitedMs = 10_000))
    }

    @Test fun aReceiverThatOnlyBuffersStillFailsAtTheDeadline() {
        // A matching BUFFERING or SEEKED event must not stop the start watchdog; only PLAYING does.
        assertFalse(spotifyReceiverConfirmsStart(Kind.BUFFERING))
        assertFalse(spotifyReceiverConfirmsStart(Kind.SEEKED))
        assertFalse(spotifyReceiverConfirmsStart(Kind.POSITION_CORRECTION))
        assertTrue(spotifyReceiverConfirmsStart(Kind.PLAYING))
        // With no confirmation and no usable observation, the watchdog fails the start at the deadline.
        assertEquals(EngineStatus.BUFFERING, spotifyReceiverStatus(Kind.BUFFERING, EngineStatus.BUFFERING))
        assertEquals(SpotifyStartCheck.FAIL, spotifyStartCheck(null, "spotify:track:wanted", waitedMs = SPOTIFY_START_DEADLINE_MS))
    }
}
