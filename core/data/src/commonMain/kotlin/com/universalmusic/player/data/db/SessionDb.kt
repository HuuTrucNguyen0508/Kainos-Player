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

internal fun KainosDatabase.writeSessionSnapshot(
    snapshot: SessionSnapshot,
    storedQueue: QueueContent = QueueContent.of(readQueueItems()),
): QueueContent {
    val queue = QueueContent.of(snapshot.items)
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

internal data class QueueContent private constructor(private val items: List<PersistedQueueItem>) {
    companion object {
        fun of(items: List<PersistedQueueItem>) =
            QueueContent(items.map { it.copy(track = it.track.copy(cachedAtMs = 0)) })
    }
}

class DbSessionSnapshotStore(
    private val storage: KainosStorage,
) : SessionSnapshotStore {
    private val writeMutex = Mutex()

    private var queueKnownStored: QueueContent? = null

    override suspend fun read(): SessionSnapshot = storage.read { it.readSessionSnapshot() }

    override suspend fun write(snapshot: SessionSnapshot) = writeMutex.withLock {
        val known = queueKnownStored
        queueKnownStored = null
        var written: QueueContent? = null
        storage.write { db ->
            written = if (known == null) db.writeSessionSnapshot(snapshot) else db.writeSessionSnapshot(snapshot, known)
        }
        queueKnownStored = written
    }
}
