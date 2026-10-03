package com.universalmusic.player.data.db

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.playlist.KAINOS_PLAYLISTS_FORMAT_VERSION
import com.universalmusic.player.data.playlist.KainosPlaylistStore
import com.universalmusic.player.data.playlist.KainosPlaylistsSnapshot
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.playlist.PersistedPlaylistEntry
import com.universalmusic.player.data.playlist.PlaylistTombstone
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal fun KainosDatabase.readKainosPlaylists(): KainosPlaylistsSnapshot {
    val entriesByPlaylist = kainosPlaylistsQueries.selectEntries().executeAsList().groupBy { it.playlist_id }
    val playlists = kainosPlaylistsQueries.selectPlaylists().executeAsList().map { row ->
        PersistedKainosPlaylist(
            id = row.id,
            title = row.title,
            entries = entriesByPlaylist[row.id].orEmpty().map { entry ->
                PersistedPlaylistEntry(
                    entryId = entry.entry_id,
                    track = storageJson.decodeFromString<PersistedTrack>(entry.track_json),
                )
            },
            createdAtMs = row.created_at_ms,
            updatedAtMs = row.updated_at_ms,
            revision = row.revision,
            deviceId = row.device_id,
        )
    }
    val tombstones = kainosPlaylistsQueries.selectTombstones().executeAsList().map { row ->
        PlaylistTombstone(
            playlistId = row.playlist_id,
            revision = row.revision,
            deviceId = row.device_id,
            deletedAtMs = row.deleted_at_ms,
        )
    }
    return KainosPlaylistsSnapshot(
        version = KAINOS_PLAYLISTS_FORMAT_VERSION,
        playlists = playlists,
        tombstones = tombstones,
    )
}

internal fun KainosDatabase.writeKainosPlaylists(snapshot: KainosPlaylistsSnapshot) {
    val previous = readKainosPlaylists()
    val oldPlaylists = previous.playlists.associateBy { it.id }
    val newIds = snapshot.playlists.map { it.id }.toSet()
    (oldPlaylists.keys - newIds).forEach { id ->
        kainosPlaylistsQueries.deletePlaylistEntries(id)
        kainosPlaylistsQueries.deletePlaylist(id)
    }
    val oldPositions = previous.playlists.mapIndexed { index, playlist -> playlist.id to index }.toMap()
    snapshot.playlists.forEachIndexed { index, playlist ->
        val old = oldPlaylists[playlist.id]
        if (old == null || old.copy(entries = emptyList()) != playlist.copy(entries = emptyList()) || oldPositions[playlist.id] != index) {
            // Update in place; REPLACE would cascade-delete its entries.
            if (old == null) {
                kainosPlaylistsQueries.insertPlaylist(playlist.id, index.toLong(), playlist.title,
                    playlist.createdAtMs, playlist.updatedAtMs, playlist.revision, playlist.deviceId)
            } else {
                kainosPlaylistsQueries.updatePlaylist(index.toLong(), playlist.title,
                    playlist.createdAtMs, playlist.updatedAtMs, playlist.revision, playlist.deviceId, playlist.id)
            }
        }
        syncOrderedRows(old?.entries.orEmpty(), playlist.entries,
            { position -> kainosPlaylistsQueries.deleteEntryAt(playlist.id, position) },
        ) { position, entry ->
            kainosPlaylistsQueries.insertEntry(playlist.id, position, entry.entryId,
                entry.track.canonicalId, storageJson.encodeToString(entry.track))
        }
    }
    val oldTombstones = previous.tombstones.associateBy { it.playlistId }
    val newTombstones = snapshot.tombstones.associateBy { it.playlistId }
    (oldTombstones.keys - newTombstones.keys).forEach(kainosPlaylistsQueries::deleteTombstone)
    newTombstones.values.filter { oldTombstones[it.playlistId] != it }.forEach { tombstone ->
        kainosPlaylistsQueries.insertTombstone(tombstone.playlistId, tombstone.revision,
            tombstone.deviceId, tombstone.deletedAtMs)
    }
}

class DbKainosPlaylistStore(
    private val storage: KainosStorage,
) : KainosPlaylistStore {
    override suspend fun read(): KainosPlaylistsSnapshot = storage.read { it.readKainosPlaylists() }

    override suspend fun write(snapshot: KainosPlaylistsSnapshot) {
        storage.write { it.writeKainosPlaylists(snapshot) }
    }
}
