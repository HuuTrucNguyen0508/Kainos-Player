package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class QueuePresenterTest {
    @Test
    fun shuffledActionsFollowEntryIdentityAfterQueueChanges() = runTest {
        val song = track("Song", "Artist", provider = ProviderId.LOCAL)
        val first = QueueItem("first", song)
        val second = QueueItem("second", song)
        val third = QueueItem("third", song)
        val queue = MutableStateFlow(PlaybackQueue(
            items = listOf(first, second, third), currentIndex = 1,
            shuffle = true, shuffleOrder = listOf(2, 1, 0),
        ))
        val played = mutableListOf<Int>()
        val moves = mutableListOf<Pair<Int, Int>>()
        var savedTracks = 0
        val presenter = QueuePresenter(queue, { played += it }, {}, { false }, {},
            { from, to -> moves += from to to },
            { _, tracks -> savedTracks = tracks.size; PersistedKainosPlaylist("playlist", "Saved") },
            backgroundScope)
        assertEquals(listOf("third", "second", "first"),
            presenter.state.value.orderedItems.map { it.second.id })
        assertEquals(1, presenter.state.value.currentOrderPosition)

        // Actions use current queue data even before the UI flow publishes the new order.
        queue.value = queue.value.copy(items = listOf(third, second, first), shuffleOrder = listOf(2, 0, 1))
        presenter.play("third")
        presenter.move("third", 1)
        assertEquals(listOf(0), played)
        assertEquals(listOf(1 to 2), moves)
        assertFalse(presenter.move("missing", 1))

        presenter.save("Queue")
        assertEquals(3, savedTracks)
    }
}
