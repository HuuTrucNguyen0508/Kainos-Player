package com.universalmusic.player.domain.queue

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueControllerTest {
    @Test
    fun playNowReplacesQueue() {
        val queue = QueueController { "a" }
        queue.playNow(track("One", "A", provider = ProviderId.SPOTIFY))
        queue.playNow(track("Two", "B", provider = ProviderId.YOUTUBE_MUSIC))
        assertEquals(1, queue.queue.value.items.size)
        assertEquals("Two", queue.queue.value.current?.track?.title)
    }

    @Test
    fun playNextInsertsAfterCurrent() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("C", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.playNext(track("B", "X", provider = ProviderId.YOUTUBE_MUSIC))
        assertEquals(listOf("A", "B", "C"), queue.queue.value.items.map { it.track.title })
    }

    @Test
    fun removeAdjustsCurrentIndex() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
                track("C", "X", provider = ProviderId.SPOTIFY),
            ),
            startIndex = 1,
        )
        queue.remove("id-1")
        assertEquals("C", queue.queue.value.current?.track?.title)
        assertEquals(2, queue.queue.value.items.size)
    }

    @Test
    fun reorderMovesCurrentWithItem() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
                track("C", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.move(0, 2)
        assertEquals(listOf("B", "C", "A"), queue.queue.value.items.map { it.track.title })
        assertEquals("A", queue.queue.value.current?.track?.title)
    }

    @Test
    fun shuffleKeepsCurrentFirstInOrder() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
                track("C", "X", provider = ProviderId.SPOTIFY),
                track("D", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.setShuffle(true)
        assertTrue(queue.queue.value.shuffle)
        assertEquals(0, queue.queue.value.shuffleOrder.first())
    }

    @Test
    fun repeatAllWraps() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
            ),
            startIndex = 1,
        )
        queue.setRepeat(RepeatMode.ALL)
        assertEquals(0, queue.nextIndex())
    }

    @Test
    fun repeatOneStaysOnCurrent() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(track("A", "X", provider = ProviderId.SPOTIFY))
        queue.setRepeat(RepeatMode.ONE)
        assertEquals(0, queue.nextIndex())
    }

    @Test
    fun manualNextIndexEscapesRepeatOne() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.setRepeat(RepeatMode.ONE)
        assertEquals(0, queue.nextIndex(respectRepeatOne = true))
        assertEquals(1, queue.nextIndex(respectRepeatOne = false))
    }

    @Test
    fun replaceCurrentTrackKeepsQueueIdentity() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(track("A", "X", provider = ProviderId.SPOTIFY))
        val originalId = queue.queue.value.current?.id
        queue.replaceCurrentTrack(track("A", "X", provider = ProviderId.YOUTUBE_MUSIC))
        assertEquals(originalId, queue.queue.value.current?.id)
        assertEquals(ProviderId.YOUTUBE_MUSIC, queue.queue.value.current?.track?.sources?.single()?.provider)
    }

    @Test
    fun playNowPreservesShuffleAndRepeat() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.setShuffle(true)
        queue.setRepeat(RepeatMode.ALL)

        queue.playNow(
            listOf(
                track("C", "X", provider = ProviderId.SPOTIFY),
                track("D", "X", provider = ProviderId.SPOTIFY),
                track("E", "X", provider = ProviderId.SPOTIFY),
            ),
            startIndex = 1,
        )

        assertTrue(queue.queue.value.shuffle)
        assertEquals(RepeatMode.ALL, queue.queue.value.repeat)
        assertEquals("D", queue.queue.value.current?.track?.title)
        assertEquals(1, queue.queue.value.shuffleOrder.first())
        assertEquals(queue.queue.value.shuffleOrder[1], queue.nextIndex())
    }

    @Test
    fun shuffleChangesSkipOrder() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
                track("C", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.setShuffle(true)
        val order = queue.queue.value.shuffleOrder
        assertEquals(0, order.first())
        assertEquals(order.getOrNull(1), queue.nextIndex())
    }

    @Test
    fun nextIsNullWhenRepeatOffAtEnd() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(track("A", "X", provider = ProviderId.SPOTIFY))
        assertNull(queue.nextIndex())
    }

    @Test
    fun playNextUnderShuffleInsertsImmediatelyAfterCurrent() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("C", "X", provider = ProviderId.SPOTIFY),
                track("D", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.setShuffle(true)
        val beforeUpcoming = queue.queue.value.upcoming.map { it.track.title }
        queue.playNext(track("B", "X", provider = ProviderId.YOUTUBE_MUSIC))
        assertEquals("B", queue.queue.value.upcoming.first().track.title)
        // Prior upcoming (minus reshuffled-only brand-new) still present after B.
        assertTrue(queue.queue.value.upcoming.map { it.track.title }.containsAll(beforeUpcoming))
    }

    @Test
    fun shuffleAddDoesNotReplayHistoryEntries() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
                track("C", "X", provider = ProviderId.SPOTIFY),
                track("D", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.setShuffle(true)
        // Force a known order: current A, then B, C, D by jumping through B.
        // Advance current to B via jump so A becomes history in shuffle order.
        val order = queue.queue.value.shuffleOrder.toMutableList()
        // Ensure A is first (current), then pin B second for a stable history after jump.
        val aIdx = 0
        val bIdx = 1
        val rest = order.filter { it != aIdx && it != bIdx }
        // Rebuild by disabling/enabling is random — instead jumpTo B after manually setting order via playNext path.
        // Simpler: play A,B,C without shuffle, enable shuffle, jump to second in shuffleOrder.
        val second = queue.queue.value.shuffleOrder[1]
        queue.jumpTo(second)
        val historyIds = queue.queue.value.history.map { it.id }
        assertTrue(historyIds.isNotEmpty())
        queue.addToQueue(track("E", "X", provider = ProviderId.SPOTIFY))
        val historyAfter = queue.queue.value.history.map { it.id }
        assertEquals(historyIds, historyAfter)
        assertTrue(queue.queue.value.upcoming.any { it.track.title == "E" })
        assertTrue(queue.queue.value.history.none { it.track.title == "E" })
    }

    @Test
    fun removeUnderShuffleKeepsRemainingUpcomingIdentity() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
                track("C", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.setShuffle(true)
        val upcomingIds = queue.queue.value.upcoming.map { it.id }
        val removeId = upcomingIds.first()
        queue.remove(removeId)
        assertTrue(queue.queue.value.items.none { it.id == removeId })
        assertEquals(
            upcomingIds.drop(1).toSet(),
            queue.queue.value.upcoming.map { it.id }.toSet(),
        )
    }

    @Test
    fun upcomingReflectsPlaybackOrderNotStorageOrderWhenShuffled() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
                track("C", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.setShuffle(true)
        val orderTitles = queue.queue.value.playbackOrder().map { queue.queue.value.items[it].track.title }
        val upcomingTitles = queue.queue.value.upcoming.map { it.track.title }
        assertEquals(orderTitles.drop(1), upcomingTitles)
    }

    @Test
    fun appendTracksKeepsCurrentAndExtendsTailAfterManualPlayNext() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(
            listOf(
                track("A", "X", provider = ProviderId.SPOTIFY),
                track("B", "X", provider = ProviderId.SPOTIFY),
            ),
        )
        queue.playNext(track("Manual", "X", provider = ProviderId.YOUTUBE_MUSIC))
        queue.appendTracks(
            listOf(
                track("Auto1", "X", provider = ProviderId.YOUTUBE_MUSIC),
                track("Auto2", "X", provider = ProviderId.YOUTUBE_MUSIC),
            ),
        )
        assertEquals(
            listOf("A", "Manual", "B", "Auto1", "Auto2"),
            queue.queue.value.items.map { it.track.title },
        )
        assertEquals(0, queue.queue.value.currentIndex)
        assertEquals(1, queue.nextIndex(respectRepeatOne = false))
    }
}
