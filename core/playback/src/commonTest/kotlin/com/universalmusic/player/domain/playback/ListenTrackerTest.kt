package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.history.PlayEvent
import com.universalmusic.player.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListenTrackerTest {
    private var now = 1_000L
    private val tracker = ListenTracker { now }
    private val first = Track("local:first", "First", emptyList(), durationMs = 200_000)
    private val second = Track("local:second", "Second", emptyList(), durationMs = 180_000)

    private fun at(ms: Long, item: String?, track: Track?, playing: Boolean, position: Long): PlayEvent? {
        now = ms
        return tracker.observe(NowPlayingState(track = track, queueItemId = item, isPlaying = playing, positionMs = position, durationMs = track?.durationMs))
    }

    @Test
    fun skippingMidTrackRecordsTheTimeHeardAsASkip() {
        assertNull(at(1_000, "a", first, playing = true, position = 0))
        assertNull(at(41_000, "a", first, playing = true, position = 40_000))
        assertEquals(PlayEvent(first, 1_000, 45_000, completed = false), at(46_000, "b", second, playing = true, position = 0))
    }

    @Test
    fun reachingTheEndRecordsACompletedPlay() {
        at(1_000, "a", first, playing = true, position = 0)
        at(200_600, "a", first, playing = true, position = 199_600)
        assertEquals(PlayEvent(first, 1_000, 200_000, completed = true), at(201_000, "b", second, playing = true, position = 0))
    }

    @Test
    fun pausedTimeIsNotListening() {
        at(1_000, "a", first, playing = true, position = 0)
        at(11_000, "a", first, playing = false, position = 10_000)
        at(611_000, "a", first, playing = true, position = 10_000)
        assertEquals(PlayEvent(first, 1_000, 15_000, completed = false), at(616_000, null, null, playing = false, position = 0))
    }

    @Test
    fun repeatOneStartsANewListenWhenTheFinishedTrackJumpsBackToTheStart() {
        at(1_000, "a", first, playing = true, position = 0)
        at(200_500, "a", first, playing = true, position = 199_500)
        assertEquals(PlayEvent(first, 1_000, 200_000, completed = true), at(201_000, "a", first, playing = true, position = 0))
        assertEquals(PlayEvent(first, 201_000, 5_000, completed = false), at(206_000, "b", second, playing = false, position = 0))
    }

    @Test
    fun anEntryThatNeverPlayedOrPlayedUnderASecondRecordsNothing() {
        at(1_000, "a", first, playing = false, position = 0)
        assertNull(at(9_000, "b", second, playing = true, position = 0))
        assertNull(at(9_021, "c", first, playing = true, position = 0))
    }
}
