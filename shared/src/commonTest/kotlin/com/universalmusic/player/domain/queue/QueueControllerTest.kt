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
        queue.playNext(track("B", "X", provider = ProviderId.SOUNDCLOUD))
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
    fun nextIsNullWhenRepeatOffAtEnd() {
        var n = 0
        val queue = QueueController { "id-${n++}" }
        queue.playNow(track("A", "X", provider = ProviderId.SPOTIFY))
        assertNull(queue.nextIndex())
    }
}
