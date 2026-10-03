package com.universalmusic.player.data.db

import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.USER_LIBRARY_FORMAT_VERSION
import com.universalmusic.player.data.library.UserLibrarySnapshot
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.sync.HeartAction
import com.universalmusic.player.data.sync.HeartOp
import com.universalmusic.player.data.sync.LocalSyncIdentity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal fun KainosDatabase.readUserLibrary(): UserLibrarySnapshot = UserLibrarySnapshot(
    version = USER_LIBRARY_FORMAT_VERSION,
    spotifyAccountId = userLibraryQueries.selectMeta().executeAsOneOrNull()?.spotify_account_id,
    favoriteIds = userLibraryQueries.selectFavorites().executeAsList(),
    heartOps = userLibraryQueries.selectHeartOps().executeAsList().map { row ->
        HeartOp(
            canonicalId = row.canonical_id,
            action = HeartAction.valueOf(row.action),
            revision = row.revision,
            deviceId = row.device_id,
            spotifyAccountId = row.spotify_account_id,
            localIdentity = row.local_identity_json?.let { storageJson.decodeFromString<LocalSyncIdentity>(it) },
        )
    },
    remembered = userLibraryQueries.selectRemembered().executeAsList().map { json ->
        storageJson.decodeFromString<PersistedTrack>(json)
    },
    recents = userLibraryQueries.selectRecents().executeAsList().map { json ->
        storageJson.decodeFromString<PersistedTrack>(json)
    },
    homePins = userLibraryQueries.selectHomePins().executeAsList().map { json ->
        storageJson.decodeFromString<PersistedHomePin>(json)
    },
)

/** Store inputs remain snapshots; only changed rows are written inside the caller's transaction. */
internal fun KainosDatabase.writeUserLibrary(snapshot: UserLibrarySnapshot) {
    val previous = readUserLibrary()
    syncOrderedRows(previous.favoriteIds, snapshot.favoriteIds, userLibraryQueries::deleteFavoriteAt) { position, id ->
        userLibraryQueries.insertFavorite(position, id)
    }
    val oldOps = previous.heartOps.associateBy { it.canonicalId }
    val newOps = snapshot.heartOps.associateBy { it.canonicalId }
    (oldOps.keys - newOps.keys).forEach(userLibraryQueries::deleteHeartOp)
    newOps.values.filter { oldOps[it.canonicalId] != it }.forEach { op ->
        userLibraryQueries.insertHeartOp(op.canonicalId, op.action.name, op.revision, op.deviceId, op.spotifyAccountId, op.localIdentity?.let { storageJson.encodeToString(it) })
    }
    syncOrderedRows(previous.remembered, snapshot.remembered, userLibraryQueries::deleteRememberedAt) { position, track ->
        userLibraryQueries.insertRemembered(position, track.canonicalId, storageJson.encodeToString(track))
    }
    syncOrderedRows(previous.recents, snapshot.recents, userLibraryQueries::deleteRecentAt) { position, track ->
        userLibraryQueries.insertRecent(position, track.canonicalId, storageJson.encodeToString(track))
    }
    syncOrderedRows(previous.homePins, snapshot.homePins, userLibraryQueries::deleteHomePinAt) { position, pin ->
        userLibraryQueries.insertHomePin(position, pin.id, pin.kind.name, pin.targetId, storageJson.encodeToString(pin))
    }
    if (previous.spotifyAccountId != snapshot.spotifyAccountId || userLibraryQueries.selectMeta().executeAsOneOrNull() == null) {
        userLibraryQueries.upsertMeta(snapshot.spotifyAccountId)
    }
}

/** Delete changed positions before inserting so moved unique ids cannot conflict with old rows. */
internal fun <T> syncOrderedRows(
    previous: List<T>,
    next: List<T>,
    deleteAt: (Long) -> Unit,
    insertAt: (Long, T) -> Unit,
) {
    previous.indices.filter { it >= next.size || previous[it] != next[it] }.forEach { deleteAt(it.toLong()) }
    next.forEachIndexed { index, value ->
        if (index >= previous.size || previous[index] != value) insertAt(index.toLong(), value)
    }
}

class DbUserLibraryStore(
    private val storage: KainosStorage,
) : UserLibraryStore {
    override suspend fun read(): UserLibrarySnapshot = storage.read { it.readUserLibrary() }

    override suspend fun write(snapshot: UserLibrarySnapshot) {
        storage.write { it.writeUserLibrary(snapshot) }
    }
}
