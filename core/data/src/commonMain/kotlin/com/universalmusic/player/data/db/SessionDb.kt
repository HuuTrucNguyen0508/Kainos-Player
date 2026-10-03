package com.universalmusic.player.data.db

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.SESSION_SNAPSHOT_FORMAT_VERSION
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.SessionSnapshotStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal fun KainosDatabase.readSessionSnapshot(): SessionSnapshot {
    val state = listeningSessionQueries.selectState().executeAsOneOrNull() ?: return SessionSnapshot()
    val items = listeningSessionQueries.selectItems().executeAsList().map { row ->
        PersistedQueueItem(
            id = row.item_id,
            track = storageJson.decodeFromString<PersistedTrack>(row.track_json),
        )
    }
    return SessionSnapshot(
        version = SESSION_SNAPSHOT_FORMAT_VERSION,
        items = items,
        currentIndex = state.current_index.toInt(),
        shuffle = state.shuffle,
        repeat = state.repeat_mode,
        shuffleOrder = storageJson.decodeFromString(state.shuffle_order),
        positionMs = state.position_ms,
    )
}

internal fun KainosDatabase.writeSessionSnapshot(snapshot: SessionSnapshot) {
    listeningSessionQueries.clearItems()
    snapshot.items.forEachIndexed { index, item ->
        listeningSessionQueries.insertItem(
            index.toLong(),
            item.id,
            item.track.canonicalId,
            storageJson.encodeToString(item.track),
        )
    }
    listeningSessionQueries.upsertState(
        snapshot.currentIndex.toLong(),
        snapshot.shuffle,
        snapshot.repeat,
        storageJson.encodeToString(snapshot.shuffleOrder),
        snapshot.positionMs,
    )
}

class DbSessionSnapshotStore(
    private val storage: KainosStorage,
) : SessionSnapshotStore {
    override suspend fun read(): SessionSnapshot = storage.read { it.readSessionSnapshot() }

    override suspend fun write(snapshot: SessionSnapshot) {
        storage.write { it.writeSessionSnapshot(snapshot) }
    }
}
