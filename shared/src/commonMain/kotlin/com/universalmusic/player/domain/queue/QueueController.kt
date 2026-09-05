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
        val item = QueueItem(idFactory(), track)
        _queue.value = PlaybackQueue(items = listOf(item), currentIndex = 0)
    }

    fun playNow(tracks: List<Track>, startIndex: Int = 0) {
        val items = tracks.map { QueueItem(idFactory(), it) }
        _queue.value = PlaybackQueue(
            items = items,
            currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        )
    }

    fun addToQueue(track: Track) {
        _queue.update { current ->
            val items = current.items + QueueItem(idFactory(), track)
            current.copy(items = items, shuffleOrder = rebuildShuffle(current, items.size))
        }
    }

    fun playNext(track: Track) {
        _queue.update { current ->
            val insertAt = (current.currentIndex + 1).coerceAtMost(current.items.size)
            val items = current.items.toMutableList()
            items.add(insertAt, QueueItem(idFactory(), track))
            current.copy(items = items, shuffleOrder = rebuildShuffle(current, items.size))
        }
    }

    fun remove(itemId: String) {
        _queue.update { current ->
            val index = current.items.indexOfFirst { it.id == itemId }
            if (index < 0) return@update current
            val items = current.items.toMutableList().also { it.removeAt(index) }
            val newIndex = when {
                items.isEmpty() -> 0
                index < current.currentIndex -> current.currentIndex - 1
                index == current.currentIndex -> index.coerceAtMost(items.lastIndex)
                else -> current.currentIndex
            }
            current.copy(items = items, currentIndex = newIndex, shuffleOrder = rebuildShuffle(current, items.size))
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
            current.copy(items = items, currentIndex = newCurrent)
        }
    }

    fun clear() {
        _queue.value = PlaybackQueue()
    }

    fun setShuffle(enabled: Boolean) {
        _queue.update { current ->
            if (!enabled) return@update current.copy(shuffle = false, shuffleOrder = emptyList())
            val order = current.items.indices.toMutableList()
            if (order.size > 1) {
                val currentIdx = current.currentIndex
                order.remove(currentIdx)
                order.shuffle()
                order.add(0, currentIdx)
            }
            current.copy(shuffle = true, shuffleOrder = order)
        }
    }

    fun setRepeat(mode: RepeatMode) {
        _queue.update { it.copy(repeat = mode) }
    }

    fun nextIndex(): Int? {
        val current = _queue.value
        if (current.items.isEmpty()) return null
        if (current.repeat == RepeatMode.ONE) return current.currentIndex
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

    private fun rebuildShuffle(current: PlaybackQueue, newSize: Int): List<Int> {
        if (!current.shuffle) return emptyList()
        val order = (0 until newSize).toMutableList()
        if (order.size > 1) {
            val currentIdx = current.currentIndex.coerceIn(0, newSize - 1)
            order.remove(currentIdx)
            order.shuffle()
            order.add(0, currentIdx)
        }
        return order
    }
}

private fun randomId(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString(16) {
        repeat(16) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }
}
