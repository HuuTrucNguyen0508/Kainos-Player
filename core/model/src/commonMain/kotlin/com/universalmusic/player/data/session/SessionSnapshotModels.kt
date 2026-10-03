package com.universalmusic.player.data.session

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.RepeatMode
import kotlinx.serialization.Serializable

const val SESSION_SNAPSHOT_FORMAT_VERSION = 1

/**
 * Versioned listening-session snapshot. Stores queue identity and display metadata only —
 * never resolved streaming URLs or protected Spotify audio.
 */
@Serializable
data class SessionSnapshot(
    val version: Int = SESSION_SNAPSHOT_FORMAT_VERSION,
    val items: List<PersistedQueueItem> = emptyList(),
    val currentIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeat: String = RepeatMode.OFF.name,
    val shuffleOrder: List<Int> = emptyList(),
    /** Coarse playback position within the current track. */
    val positionMs: Long = 0L,
)

@Serializable
data class PersistedQueueItem(
    val id: String,
    val track: PersistedTrack,
)

interface SessionSnapshotStore {
    suspend fun read(): SessionSnapshot
    suspend fun write(snapshot: SessionSnapshot)
}

fun SessionSnapshot.migrated(): SessionSnapshot {
    if (items.isEmpty()) {
        return copy(
            version = SESSION_SNAPSHOT_FORMAT_VERSION,
            currentIndex = 0,
            shuffleOrder = if (shuffle) emptyList() else shuffleOrder,
            positionMs = positionMs.coerceAtLeast(0L),
        )
    }
    val index = currentIndex.coerceIn(0, items.lastIndex)
    val order = when {
        !shuffle -> emptyList()
        shuffleOrder.size == items.size &&
            shuffleOrder.toSet() == items.indices.toSet() -> shuffleOrder
        else -> emptyList()
    }
    val repeatMode = runCatching { RepeatMode.valueOf(repeat) }.getOrDefault(RepeatMode.OFF)
    return copy(
        version = SESSION_SNAPSHOT_FORMAT_VERSION,
        currentIndex = index,
        shuffle = shuffle,
        repeat = repeatMode.name,
        shuffleOrder = order,
        positionMs = positionMs.coerceAtLeast(0L),
    )
}

fun PlaybackQueue.toSessionSnapshot(positionMs: Long, nowMs: Long): SessionSnapshot {
    val persistedItems = items.map { item ->
        PersistedQueueItem(id = item.id, track = item.track.toPersisted(nowMs))
    }
    return SessionSnapshot(
        version = SESSION_SNAPSHOT_FORMAT_VERSION,
        items = persistedItems,
        currentIndex = if (persistedItems.isEmpty()) 0 else currentIndex.coerceIn(0, persistedItems.lastIndex),
        shuffle = shuffle,
        repeat = repeat.name,
        shuffleOrder = if (shuffle && shuffleOrder.size == items.size) shuffleOrder else emptyList(),
        positionMs = positionMs.coerceAtLeast(0L),
    )
}

fun SessionSnapshot.toPlaybackQueue(): PlaybackQueue {
    val migrated = migrated()
    if (migrated.items.isEmpty()) {
        return PlaybackQueue(
            shuffle = migrated.shuffle,
            repeat = runCatching { RepeatMode.valueOf(migrated.repeat) }.getOrDefault(RepeatMode.OFF),
        )
    }
    val queueItems = migrated.items.map { QueueItem(id = it.id, track = it.track.toDomain()) }
    val index = migrated.currentIndex.coerceIn(0, queueItems.lastIndex)
    val repeatMode = runCatching { RepeatMode.valueOf(migrated.repeat) }.getOrDefault(RepeatMode.OFF)
    return PlaybackQueue(
        items = queueItems,
        currentIndex = index,
        shuffle = migrated.shuffle,
        repeat = repeatMode,
        shuffleOrder = migrated.shuffleOrder,
    )
}
