package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class QueueUiState(
    val queue: PlaybackQueue,
    val orderedItems: List<Pair<Int, QueueItem>>,
    val currentOrderPosition: Int,
)

internal fun queueUiState(queue: PlaybackQueue): QueueUiState {
    val order = queue.playbackOrder()
    return QueueUiState(
        queue,
        order.mapNotNull { index -> queue.items.getOrNull(index)?.let { index to it } },
        order.indexOf(queue.currentIndex),
    )
}

internal class QueuePresenter(
    private val queue: StateFlow<PlaybackQueue>,
    private val playIndex: (Int) -> Unit,
    private val clearQueue: () -> Unit,
    private val undoEdit: () -> Boolean,
    private val removeItem: (String) -> Unit,
    private val moveItem: (Int, Int) -> Unit,
    private val savePlaylist: (String, List<Track>) -> PersistedKainosPlaylist,
    scope: CoroutineScope,
) {
    val state: StateFlow<QueueUiState> = queue.map(::queueUiState)
        .stateIn(scope, SharingStarted.Eagerly, queueUiState(queue.value))

    fun play(itemId: String) {
        val index = queue.value.items.indexOfFirst { it.id == itemId }
        if (index >= 0) playIndex(index)
    }

    fun move(itemId: String, steps: Int): Boolean {
        val current = queue.value
        val from = current.orderIndexOf(itemId)
        if (from < 0) return false
        val to = (from + steps).coerceIn(0, current.playbackOrder().lastIndex)
        if (from == to) return false
        moveItem(from, to)
        return true
    }

    fun clear() = clearQueue()
    fun undo(): Boolean = undoEdit()
    fun remove(itemId: String) = removeItem(itemId)

    fun save(name: String): String {
        val tracks = queueUiState(queue.value).orderedItems.map { it.second.track }
        val saved = savePlaylist(name.trim().ifBlank { "Queue" }, tracks)
        return "Saved \"${saved.title}\" (${saved.entries.size} tracks)"
    }

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope): QueuePresenter = QueuePresenter(
            queue = container.player.queue.queue,
            playIndex = container.player::playQueueIndex,
            clearQueue = container::clearPlaybackQueue,
            undoEdit = container.player::undoQueueEdit,
            removeItem = container.player::removeFromQueue,
            moveItem = container.player::moveInPlaybackOrder,
            savePlaylist = container.kainosPlaylists::saveQueueAsPlaylist,
            scope = scope,
        )
    }
}
