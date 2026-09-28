package com.universalmusic.player.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyPlaybackSyncTest {
    @Test
    fun remotePauseStopsTheLocalPlayingState() {
        val decision = reconcileSpotifyObservation(
            status = EngineStatus.PLAYING,
            positionMs = 12_000,
            durationMs = 180_000,
            expectedTrackId = "track",
            userPaused = false,
            misses = 0,
            observed = SpotifyObservedPlayback(isPlaying = false, progressMs = 12_400, trackId = "track"),
        )
        assertEquals(EngineStatus.PAUSED, decision.status)
        assertEquals(12_400, decision.positionMs)
        assertNull(decision.syncWarning)
    }

    @Test
    fun remoteSeekSnapsWhenDriftIsLarge() {
        val decision = reconcileSpotifyObservation(
            status = EngineStatus.PLAYING,
            positionMs = 10_000,
            durationMs = 180_000,
            expectedTrackId = "track",
            userPaused = false,
            misses = 1,
            observed = SpotifyObservedPlayback(isPlaying = true, progressMs = 40_000, trackId = "track"),
        )
        assertEquals(EngineStatus.PLAYING, decision.status)
        assertEquals(40_000, decision.positionMs)
        assertTrue(decision.snapClock)
        assertEquals(0, decision.misses)
    }

    @Test
    fun repeatedMissesSurfaceUnavailable() {
        val decision = reconcileSpotifyObservation(
            status = EngineStatus.PLAYING,
            positionMs = 1_000,
            durationMs = 180_000,
            expectedTrackId = "track",
            userPaused = false,
            misses = 2,
            observed = null,
        )
        assertEquals("Playback state unavailable", decision.syncWarning)
        assertEquals(EngineStatus.PLAYING, decision.status)
        assertEquals(3, decision.misses)
    }

    @Test
    fun completionNearTheEndEndsPlayback() {
        val decision = reconcileSpotifyObservation(
            status = EngineStatus.PLAYING,
            positionMs = 179_000,
            durationMs = 180_000,
            expectedTrackId = "track",
            userPaused = false,
            misses = 0,
            observed = SpotifyObservedPlayback(isPlaying = false, progressMs = 179_200, trackId = "track"),
        )
        assertEquals(EngineStatus.ENDED, decision.status)
    }

    @Test
    fun localPauseIsNotOverwritten() {
        val decision = reconcileSpotifyObservation(
            status = EngineStatus.PAUSED,
            positionMs = 5_000,
            durationMs = 180_000,
            expectedTrackId = "track",
            userPaused = true,
            misses = 0,
            observed = SpotifyObservedPlayback(isPlaying = true, progressMs = 8_000, trackId = "track"),
        )
        assertEquals(EngineStatus.PAUSED, decision.status)
        assertEquals(5_000, decision.positionMs)
        assertFalse(decision.clearUserPause)
        assertFalse(decision.pauseAcknowledged)
    }

    @Test
    fun remotePauseAcknowledgesTheLocalPause() {
        val decision = reconcileSpotifyObservation(
            status = EngineStatus.PAUSED,
            positionMs = 5_000,
            durationMs = 180_000,
            expectedTrackId = "track",
            userPaused = true,
            misses = 0,
            observed = SpotifyObservedPlayback(isPlaying = false, progressMs = 5_200, trackId = "track"),
        )
        assertEquals(EngineStatus.PAUSED, decision.status)
        assertTrue(decision.pauseAcknowledged)
        assertFalse(decision.clearUserPause)
    }

    @Test
    fun remotePlayAfterAcknowledgedPauseClearsTheLocalGuard() {
        val decision = reconcileSpotifyObservation(
            status = EngineStatus.PAUSED,
            positionMs = 5_200,
            durationMs = 180_000,
            expectedTrackId = "track",
            userPaused = true,
            misses = 0,
            observed = SpotifyObservedPlayback(isPlaying = true, progressMs = 9_000, trackId = "track"),
            pauseAcknowledged = true,
        )
        assertEquals(EngineStatus.PLAYING, decision.status)
        assertEquals(9_000, decision.positionMs)
        assertTrue(decision.clearUserPause)
        assertTrue(decision.snapClock)
    }

    @Test
    fun observationCapturedBeforeALocalPauseIsDropped() {
        val captured = SpotifyObservationGate(requestToken = 4, transportGeneration = 1, userPaused = false)
        val afterPause = captured.copy(transportGeneration = 2, userPaused = true)
        assertFalse(spotifyObservationStillValid(captured, afterPause, spotifyActive = true))
        assertTrue(spotifyObservationStillValid(afterPause, afterPause, spotifyActive = true))
    }
}
