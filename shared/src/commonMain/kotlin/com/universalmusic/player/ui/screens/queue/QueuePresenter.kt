package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal data class QueueUiState(
    val queue: PlaybackQueue,
    val orderedItems: List<Pair<Int, QueueItem>>,
    val currentOrderPosition: Int,
    /** The source whose radio plays on after the last entry; null when the queue just ends. */
    val radio: ProviderId? = null,
) {
    val upcomingCount: Int get() = (orderedItems.size - currentOrderPosition - 1).coerceAtLeast(0)
}

internal fun queueUiState(queue: PlaybackQueue, radio: ProviderId? = null): QueueUiState {
    val order = queue.playbackOrder()
    return QueueUiState(
        queue,
        order.mapNotNull { index -> queue.items.getOrNull(index)?.let { index to it } },
        order.indexOf(queue.currentIndex),
        radio,
    )
}

/** The footer under the queue while a radio continues it. */
internal fun radioFooterLabel(radio: ProviderId, upcomingCount: Int): String {
    val name = when (radio) {
        ProviderId.SPOTIFY -> "Spotify radio"
        ProviderId.YOUTUBE_MUSIC -> "YouTube Mix"
        else -> "Radio"
    }
    return if (upcomingCount == 0) "$name continues after this song" else "$name continues after these"
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
    private val radio: StateFlow<ProviderId?> = MutableStateFlow(null),
    private val clearUpcomingItems: () -> Unit = {},
    private val stopRadioAction: () -> Unit = {},
) {
    // Radio state is read from the session's flow; only session calls change it.
    val state: StateFlow<QueueUiState> = combine(queue, radio, ::queueUiState)
        .stateIn(scope, SharingStarted.Eagerly, queueUiState(queue.value, radio.value))

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

    /** Keeps the current song playing; undo brings the rest back. Returns how many were removed. */
    fun clearUpcoming(): Int {
        val count = queueUiState(queue.value).upcomingCount
        if (count > 0) clearUpcomingItems()
        return count
    }

    fun stopRadio() = stopRadioAction()
    fun undo(): Boolean = undoEdit()
    fun remove(itemId: String) = removeItem(itemId)

    fun save(name: String): String {
        val tracks = queueUiState(queue.value).orderedItems.map { it.second.track }
        val saved = savePlaylist(name.trim().ifBlank { "Queue" }, tracks)
        return "Saved \"${saved.title}\" (${saved.entries.size} tracks)"
    }

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope): QueuePresenter = QueuePresenter(
            queue = container.player.queueState,
            playIndex = container.player::playQueueIndex,
            clearQueue = container::clearPlaybackQueue,
            undoEdit = container.player::undoQueueEdit,
            removeItem = container.player::removeFromQueue,
            moveItem = container.player::moveInPlaybackOrder,
            savePlaylist = container.kainosPlaylists::saveQueueAsPlaylist,
            scope = scope,
            radio = container.player.radio,
            clearUpcomingItems = container.player::clearUpcoming,
            stopRadioAction = container.player::stopRadio,
        )
    }
}
