package com.universalmusic.player.domain.queue

import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

class QueueController(
    private val idFactory: () -> String = { randomId() },
) {
    private val _queue = MutableStateFlow(PlaybackQueue())
    val queue: StateFlow<PlaybackQueue> = _queue.asStateFlow()

    fun playNow(track: Track) {
        playNow(listOf(track), startIndex = 0)
    }

    fun playNow(tracks: List<Track>, startIndex: Int = 0) {
        val items = tracks.map { QueueItem(idFactory(), it) }
        if (items.isEmpty()) {
            _queue.value = PlaybackQueue()
            return
        }
        val index = startIndex.coerceIn(0, items.lastIndex)
        _queue.update { current ->
            val base = PlaybackQueue(
                items = items,
                currentIndex = index,
                shuffle = current.shuffle,
                repeat = current.repeat,
            )
            base.copy(shuffleOrder = freshShuffleOrder(base.shuffle, items.size, index))
        }
    }

    fun addToQueue(track: Track) {
        _queue.update { current ->
            val newItem = QueueItem(idFactory(), track)
            val items = current.items + newItem
            current.copy(
                items = items,
                shuffleOrder = preserveShuffleOrder(
                    previous = current,
                    newItems = items,
                    newCurrentIndex = current.currentIndex.coerceIn(0, items.lastIndex),
                    playNextId = null,
                    appendNewIds = listOf(newItem.id),
                ),
            )
        }
    }

    /** Append tracks at the tail without changing the current index (autoplay continuation). */
    fun appendTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _queue.update { current ->
            val newItems = tracks.map { QueueItem(idFactory(), it) }
            val items = current.items + newItems
            current.copy(
                items = items,
                shuffleOrder = preserveShuffleOrder(
                    previous = current,
                    newItems = items,
                    newCurrentIndex = current.currentIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)),
                    playNextId = null,
                    appendNewIds = newItems.map { it.id },
                ),
            )
        }
    }

    fun playNext(track: Track) {
        _queue.update { current ->
            val newItem = QueueItem(idFactory(), track)
            val insertAt = (current.currentIndex + 1).coerceAtMost(current.items.size)
            val items = current.items.toMutableList().also { it.add(insertAt, newItem) }
            val newCurrent = if (insertAt <= current.currentIndex) current.currentIndex + 1 else current.currentIndex
            current.copy(
                items = items,
                currentIndex = newCurrent,
                shuffleOrder = preserveShuffleOrder(
                    previous = current,
                    newItems = items,
                    newCurrentIndex = newCurrent,
                    playNextId = newItem.id,
                    appendNewIds = emptyList(),
                ),
            )
        }
    }

    fun remove(itemId: String) {
        _queue.update { current ->
            val index = current.items.indexOfFirst { it.id == itemId }
            if (index < 0) return@update current
            val items = current.items.toMutableList().also { it.removeAt(index) }
            if (items.isEmpty()) return@update PlaybackQueue(shuffle = current.shuffle, repeat = current.repeat)
            val newIndex = when {
                index < current.currentIndex -> current.currentIndex - 1
                index == current.currentIndex -> index.coerceAtMost(items.lastIndex)
                else -> current.currentIndex
            }
            current.copy(
                items = items,
                currentIndex = newIndex,
                shuffleOrder = preserveShuffleOrder(
                    previous = current,
                    newItems = items,
                    newCurrentIndex = newIndex,
                    playNextId = null,
                    appendNewIds = emptyList(),
                ),
            )
        }
    }

    fun move(from: Int, to: Int) {
        _queue.update { current ->
            if (from !in current.items.indices || to !in current.items.indices) return@update current
            val items = current.items.toMutableList()
            val item = items.removeAt(from)
            items.add(to, item)
            val newCurrent = when (current.currentIndex) {
                from -> to
                in (minOf(from, to)..maxOf(from, to)) -> {
                    if (from < current.currentIndex) current.currentIndex - 1
                    else current.currentIndex + 1
                }
                else -> current.currentIndex
            }
            current.copy(
                items = items,
                currentIndex = newCurrent,
                shuffleOrder = preserveShuffleOrder(
                    previous = current,
                    newItems = items,
                    newCurrentIndex = newCurrent,
                    playNextId = null,
                    appendNewIds = emptyList(),
                ),
            )
        }
    }

    /** Move by position in [PlaybackQueue.playbackOrder] (effective upcoming/history UI). */
    fun moveInPlaybackOrder(fromOrderPos: Int, toOrderPos: Int) {
        _queue.update { current ->
            val order = current.playbackOrder().toMutableList()
            if (fromOrderPos !in order.indices || toOrderPos !in order.indices) return@update current
            val storageFrom = order[fromOrderPos]
            val storageTo = order[toOrderPos]
            if (!current.shuffle) {
                return@update afterStorageMove(current, storageFrom, storageTo)
            }
            val moved = order.removeAt(fromOrderPos)
            order.add(toOrderPos, moved)
            current.copy(shuffleOrder = order)
        }
    }

    fun clear() {
        _queue.value = PlaybackQueue()
    }

    fun setShuffle(enabled: Boolean) {
        _queue.update { current ->
            if (!enabled) return@update current.copy(shuffle = false, shuffleOrder = emptyList())
            current.copy(
                shuffle = true,
                shuffleOrder = freshShuffleOrder(true, current.items.size, current.currentIndex),
            )
        }
    }

    fun setRepeat(mode: RepeatMode) {
        _queue.update { it.copy(repeat = mode) }
    }

    fun nextIndex(): Int? = nextIndex(respectRepeatOne = true)

    /**
     * Next queue index for natural completion ([respectRepeatOne] = true) or manual skip
     * ([respectRepeatOne] = false). Manual skip escapes Repeat One.
     */
    fun nextIndex(respectRepeatOne: Boolean): Int? {
        val current = _queue.value
        if (current.items.isEmpty()) return null
        if (respectRepeatOne && current.repeat == RepeatMode.ONE) return current.currentIndex
        val order = current.playbackOrder()
        val pos = order.indexOf(current.currentIndex)
        val nextPos = pos + 1
        return when {
            nextPos in order.indices -> order[nextPos]
            current.repeat == RepeatMode.ALL -> order.firstOrNull()
            else -> null
        }
    }

    fun previousIndex(): Int? {
        val current = _queue.value
        if (current.items.isEmpty()) return null
        val order = current.playbackOrder()
        val pos = order.indexOf(current.currentIndex)
        val prevPos = pos - 1
        return when {
            prevPos in order.indices -> order[prevPos]
            current.repeat == RepeatMode.ALL -> order.lastOrNull()
            else -> current.currentIndex
        }
    }

    fun jumpTo(index: Int) {
        _queue.update { current ->
            if (index !in current.items.indices) current else current.copy(currentIndex = index)
        }
    }

    fun replaceCurrentTrack(track: Track) {
        _queue.update { current ->
            if (current.items.isEmpty()) return@update current
            val items = current.items.toMutableList()
            val existing = items[current.currentIndex]
            items[current.currentIndex] = existing.copy(track = track)
            current.copy(items = items)
        }
    }

    private fun afterStorageMove(current: PlaybackQueue, from: Int, to: Int): PlaybackQueue {
        val items = current.items.toMutableList()
        val item = items.removeAt(from)
        items.add(to, item)
        val newCurrent = when (current.currentIndex) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> {
                if (from < current.currentIndex) current.currentIndex - 1
                else current.currentIndex + 1
            }
            else -> current.currentIndex
        }
        return current.copy(items = items, currentIndex = newCurrent)
    }

    private fun freshShuffleOrder(shuffle: Boolean, size: Int, currentIndex: Int): List<Int> {
        if (!shuffle || size == 0) return emptyList()
        val order = (0 until size).toMutableList()
        if (order.size > 1) {
            val currentIdx = currentIndex.coerceIn(0, size - 1)
            order.remove(currentIdx)
            order.shuffle()
            order.add(0, currentIdx)
        }
        return order
    }

    /**
     * Rebuild shuffle indices from entry IDs so history (already passed in the prior order)
     * stays before current, Play next sits immediately after current, and only truly new
     * entries are shuffled into the remaining upcoming tail.
     */
    private fun preserveShuffleOrder(
        previous: PlaybackQueue,
        newItems: List<QueueItem>,
        newCurrentIndex: Int,
        playNextId: String?,
        appendNewIds: List<String>,
    ): List<Int> {
        if (!previous.shuffle || newItems.isEmpty()) {
            return freshShuffleOrder(previous.shuffle, newItems.size, newCurrentIndex)
        }
        val idToIndex = newItems.mapIndexed { index, item -> item.id to index }.toMap()
        val previousOrderIds = previous.playbackOrder().mapNotNull { previous.items.getOrNull(it)?.id }
        val previousCurrentId = previous.items.getOrNull(previous.currentIndex)?.id
        val previousCurrentPos = previousOrderIds.indexOf(previousCurrentId).takeIf { it >= 0 } ?: 0
        val historyIds = previousOrderIds
            .take(previousCurrentPos)
            .filter { it in idToIndex }
        val remainingIds = previousOrderIds
            .drop(previousCurrentPos + 1)
            .filter { it in idToIndex }
        val currentId = newItems.getOrNull(newCurrentIndex)?.id
        val known = (historyIds + listOfNotNull(currentId) + remainingIds + listOfNotNull(playNextId)).toSet()
        val brandNew = (appendNewIds + newItems.map { it.id })
            .distinct()
            .filter { it !in known && it in idToIndex }
        val upcoming = buildList {
            if (playNextId != null && playNextId in idToIndex && playNextId != currentId) {
                add(playNextId)
            }
            addAll(remainingIds.filter { it != playNextId && it != currentId })
            addAll(brandNew.shuffled().filter { it != playNextId && it != currentId })
        }
        val orderedIds = historyIds.filter { it != currentId } +
            listOfNotNull(currentId) +
            upcoming
        return orderedIds.mapNotNull { idToIndex[it] }.distinct()
            .ifEmpty { freshShuffleOrder(true, newItems.size, newCurrentIndex) }
            .let { order ->
                // Ensure every index appears exactly once.
                val missing = newItems.indices.filter { it !in order }
                order + missing.shuffled()
            }
    }
}

private fun randomId(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString(16) {
        repeat(16) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }
}
