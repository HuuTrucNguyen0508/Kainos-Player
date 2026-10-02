package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QueueItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LearnedDurationTest {
    private val unknown = track("Folder song", "Artist", durationMs = null, provider = ProviderId.LOCAL)
    private val other = track("Other", "Artist", durationMs = null, provider = ProviderId.LOCAL)
    private val queue = PlaybackQueue(
        items = listOf(QueueItem("a", other), QueueItem("b", unknown)),
        currentIndex = 1,
    )

    @Test
    fun engineDurationIsKeptOnTheCurrentItemOnly() {
        val now = NowPlayingState(track = unknown, queueItemId = "b", durationMs = 212_000)
        val updated = queue.withLearnedDuration(now)
        assertEquals(212_000, updated.items[1].track.durationMs)
        assertNull(updated.items[0].track.durationMs)
    }

    @Test
    fun scannedDurationIsNeverOverwritten() {
        val known = queue.copy(items = listOf(queue.items[0], QueueItem("b", unknown.copy(durationMs = 100_000))))
        val now = NowPlayingState(queueItemId = "b", durationMs = 212_000)
        assertSame(known, known.withLearnedDuration(now))
    }

    @Test
    fun staleOrMissingEngineDurationLeavesTheQueueAlone() {
        assertSame(queue, queue.withLearnedDuration(NowPlayingState(queueItemId = "a", durationMs = 212_000)))
        assertSame(queue, queue.withLearnedDuration(NowPlayingState(queueItemId = "b", durationMs = null)))
        assertSame(queue, queue.withLearnedDuration(NowPlayingState(queueItemId = "b", durationMs = 0)))
    }
}
