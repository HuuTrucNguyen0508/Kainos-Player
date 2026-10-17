package com.universalmusic.player.data.db

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.SESSION_SNAPSHOT_FORMAT_VERSION
import com.universalmusic.player.data.session.SessionSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal fun KainosDatabase.readSessionSnapshot(): SessionSnapshot {
    val state = listeningSessionQueries.selectState().executeAsOneOrNull() ?: return SessionSnapshot()
    return SessionSnapshot(
        version = SESSION_SNAPSHOT_FORMAT_VERSION,
        items = readQueueItems(),
        currentIndex = state.current_index.toInt(),
        shuffle = state.shuffle,
        repeat = state.repeat_mode,
        shuffleOrder = storageJson.decodeFromString(state.shuffle_order),
        positionMs = state.position_ms,
    )
}

internal fun KainosDatabase.readSessionOrigin(): PublicationOrigin =
    listeningSessionQueries.selectState().executeAsOneOrNull()?.publication_origin
        ?.let { stored -> PublicationOrigin.entries.firstOrNull { it.name == stored } }
        ?: PublicationOrigin.LOCAL

/**
 * Rewrites only what changed. [cached] is trusted only while the stored rows still equal its rows,
 * because another connection may have written the queue since.
 */
internal fun KainosDatabase.writeSessionSnapshot(
    snapshot: SessionSnapshot,
    cached: StoredQueue?,
    origin: PublicationOrigin = PublicationOrigin.LOCAL,
): StoredQueue {
    val storedRows = listeningSessionQueries.selectItems().executeAsList()
    val storedQueue = cached?.takeIf { it.rows == storedRows } ?: StoredQueue(storedRows, storedRows.decodeQueue())
    val queue = QueueContent.of(snapshot.items)
    val rows = if (queue == storedQueue.content) {
        storedQueue.rows
    } else if (storedQueue.content?.items?.map { it.id } == queue.items.map { it.id }) {
        snapshot.items.mapIndexed { index, item ->
            if (queue.items[index] == storedQueue.content.items[index]) {
                storedQueue.rows[index]
            } else {
                val row = item.toRow(index)
                listeningSessionQueries.updateItem(row.item_id, row.canonical_id, row.track_json, row.position)
                row
            }
        }
    } else {
        listeningSessionQueries.clearItems()
        snapshot.items.mapIndexed { index, item ->
            val row = item.toRow(index)
            listeningSessionQueries.insertItem(row.position, row.item_id, row.canonical_id, row.track_json)
            row
        }
    }
    listeningSessionQueries.upsertState(
        snapshot.currentIndex.toLong(),
        snapshot.shuffle,
        snapshot.repeat,
        storageJson.encodeToString(snapshot.shuffleOrder),
        snapshot.positionMs,
        origin.name,
    )
    return StoredQueue(rows, queue)
}

private fun PersistedQueueItem.toRow(index: Int) = Session_queue_item(
    position = index.toLong(),
    item_id = id,
    canonical_id = track.canonicalId,
    track_json = storageJson.encodeToString(track),
)

private fun KainosDatabase.readQueueItems(): List<PersistedQueueItem> =
    listeningSessionQueries.selectItems().executeAsList().map { row ->
        PersistedQueueItem(
            id = row.item_id,
            track = storageJson.decodeFromString<PersistedTrack>(row.track_json),
        )
    }

internal data class QueueContent private constructor(val items: List<PersistedQueueItem>) {
    companion object {
        fun of(items: List<PersistedQueueItem>) =
            QueueContent(items.map { it.copy(track = it.track.copy(cachedAtMs = 0)) })
    }
}

internal data class StoredQueue(val rows: List<Session_queue_item>, val content: QueueContent?)

private fun List<Session_queue_item>.decodeQueue(): QueueContent? {
    val items = ArrayList<PersistedQueueItem>(size)
    try {
        for ((index, row) in withIndex()) {
            val track = storageJson.decodeFromString<PersistedTrack>(row.track_json)
            if (row.position != index.toLong() || row.canonical_id != track.canonicalId) return null
            items += PersistedQueueItem(row.item_id, track)
        }
    } catch (_: SerializationException) {
        return null
    }
    return QueueContent.of(items)
}
