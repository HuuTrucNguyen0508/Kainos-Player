package com.universalmusic.player.data.db

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.SESSION_SNAPSHOT_FORMAT_VERSION
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.SessionSnapshotStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Rewrites the queue rows only when the queue differs from [storedQueue], and returns the
 * queue as stored afterwards. Both sides ignore [PersistedTrack.cachedAtMs], which every
 * snapshot stamps with the current time.
 */
internal fun KainosDatabase.writeSessionSnapshot(
    snapshot: SessionSnapshot,
    storedQueue: List<PersistedQueueItem> = readQueueItems().withoutCacheStamps(),
): List<PersistedQueueItem> {
    val queue = snapshot.items.withoutCacheStamps()
    if (queue != storedQueue) {
        listeningSessionQueries.clearItems()
        snapshot.items.forEachIndexed { index, item ->
            listeningSessionQueries.insertItem(
                index.toLong(),
                item.id,
                item.track.canonicalId,
                storageJson.encodeToString(item.track),
            )
        }
    }
    listeningSessionQueries.upsertState(
        snapshot.currentIndex.toLong(),
        snapshot.shuffle,
        snapshot.repeat,
        storageJson.encodeToString(snapshot.shuffleOrder),
        snapshot.positionMs,
    )
    return queue
}

private fun KainosDatabase.readQueueItems(): List<PersistedQueueItem> =
    listeningSessionQueries.selectItems().executeAsList().map { row ->
        PersistedQueueItem(
            id = row.item_id,
            track = storageJson.decodeFromString<PersistedTrack>(row.track_json),
        )
    }

private fun List<PersistedQueueItem>.withoutCacheStamps(): List<PersistedQueueItem> =
    map { it.copy(track = it.track.copy(cachedAtMs = 0)) }

class DbSessionSnapshotStore(
    private val storage: KainosStorage,
) : SessionSnapshotStore {
    private val writeMutex = Mutex()

    /**
     * The queue this store last committed, so a skip does not read 500 rows back. Null until
     * the first write and after a failed one, when the write compares against the stored rows.
     */
    private var committedQueue: List<PersistedQueueItem>? = null

    override suspend fun read(): SessionSnapshot = storage.read { it.readSessionSnapshot() }

    override suspend fun write(snapshot: SessionSnapshot) = writeMutex.withLock {
        val known = committedQueue
        committedQueue = null
        var written: List<PersistedQueueItem>? = null
        storage.write { db ->
            written = if (known == null) db.writeSessionSnapshot(snapshot) else db.writeSessionSnapshot(snapshot, known)
        }
        committedQueue = written
    }
}
