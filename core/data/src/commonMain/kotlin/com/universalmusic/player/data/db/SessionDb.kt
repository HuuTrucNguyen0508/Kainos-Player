package com.universalmusic.player.data.db

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.SESSION_SNAPSHOT_FORMAT_VERSION
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.SessionSnapshotStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal fun KainosDatabase.writeSessionSnapshot(
    snapshot: SessionSnapshot,
    storedQueue: StoredQueue,
): StoredQueue {
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

class DbSessionSnapshotStore(
    private val storage: KainosStorage,
) : SessionSnapshotStore {
    private val writeMutex = Mutex()
    private var decodedQueue: StoredQueue? = null

    override suspend fun read(): SessionSnapshot = storage.read { it.readSessionSnapshot() }

    override suspend fun write(snapshot: SessionSnapshot) = writeMutex.withLock {
        decodedQueue = storage.write { db ->
            val rows = db.listeningSessionQueries.selectItems().executeAsList()
            val stored = decodedQueue?.takeIf { it.rows == rows } ?: StoredQueue(rows, rows.decodeQueue())
            db.writeSessionSnapshot(snapshot, stored)
        }
    }
}

internal data class StoredQueue(val rows: List<Session_queue_item>, val content: QueueContent?)

internal fun KainosDatabase.readStoredQueue(): StoredQueue {
    val rows = listeningSessionQueries.selectItems().executeAsList()
    return StoredQueue(rows, rows.decodeQueue())
}

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
