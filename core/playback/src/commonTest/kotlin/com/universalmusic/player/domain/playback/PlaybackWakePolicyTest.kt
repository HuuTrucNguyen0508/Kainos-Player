package com.universalmusic.player.domain.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackWakePolicyTest {
    @Test fun holdsAcrossTransitionsAndSpotifyPlaybackButReleasesOnPauseAndStop() {
        assertTrue(playbackNeedsWakeLock(false, EngineStatus.BUFFERING))
        assertTrue(playbackNeedsWakeLock(true, EngineStatus.BUFFERING))
        assertTrue(playbackNeedsWakeLock(true, EngineStatus.PLAYING))
        assertFalse(playbackNeedsWakeLock(true, EngineStatus.PAUSED))
        assertFalse(playbackNeedsWakeLock(true, EngineStatus.IDLE))
        assertFalse(playbackNeedsWakeLock(true, EngineStatus.FAILED))
        assertFalse(playbackNeedsWakeLock(false, EngineStatus.PLAYING))
    }
}
