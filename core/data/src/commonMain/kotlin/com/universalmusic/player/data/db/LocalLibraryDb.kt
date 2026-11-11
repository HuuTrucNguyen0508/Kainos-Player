package com.universalmusic.player.data.db

import com.universalmusic.player.domain.failure.StorageArea
import com.universalmusic.player.data.local.LocalLibraryScanCache
import com.universalmusic.player.data.local.LocalTrack
import com.universalmusic.player.data.local.StoredLocalTrack
import com.universalmusic.player.data.local.toLocalTrackOrNull
import com.universalmusic.player.data.local.toStored
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal fun KainosDatabase.readLocalScan(configKey: String, strict: Boolean = false): List<LocalTrack>? {
    if (!localLibraryQueries.hasScan(configKey).executeAsOne()) return null
    return localLibraryQueries.selectTracks().executeAsList().mapNotNull { row ->
        readStorageRow(strict) { row.toLocalTrackOrNull() }
    }
}

/** track_json holds the scan fields; the file's tags live in their own columns (migration 9). */
private fun Local_track.toLocalTrackOrNull(): LocalTrack? =
    storageJson.decodeFromString<StoredLocalTrack>(track_json).toLocalTrackOrNull()?.copy(
        albumArtist = album_artist,
        trackNumber = track_number?.toInt(),
        discNumber = disc_number?.toInt(),
        year = release_year?.toInt(),
        genre = genre,
        tagsVersion = tags_version.toInt(),
    )

internal fun KainosDatabase.writeLocalScan(configKey: String, tracks: List<LocalTrack>, nowMs: Long) {
    val previous = readLocalScan(configKey, strict = true)
    if (previous == null) localLibraryQueries.clearTracks()
    localLibraryQueries.clearScans()
    localLibraryQueries.insertScan(configKey, nowMs)
    syncOrderedRows(previous.orEmpty(), tracks, localLibraryQueries::deleteTrackAt) { position, track ->
        localLibraryQueries.insertTrack(
            position,
            track.id,
            track.location,
            track.contentLength,
            track.fileModifiedEpochMs,
            track.contentKey,
            storageJson.encodeToString(track.toStored()),
            track.albumArtist,
            track.trackNumber?.toLong(),
            track.discNumber?.toLong(),
            track.year?.toLong(),
            track.genre,
            track.tagsVersion.toLong(),
        )
    }
}

class DbLocalLibraryScanCache(
    storage: KainosStorage,
) : LocalLibraryScanCache {
    private val storage = AreaStorage(storage, StorageArea.LOCAL_SCAN)

    override suspend fun read(configKey: String): List<LocalTrack>? =
        storage.read { it.readLocalScan(configKey) }

    override suspend fun write(configKey: String, tracks: List<LocalTrack>) {
        storage.write { it.writeLocalScan(configKey, tracks, currentTimeMillis()) }
    }

    override suspend fun findByContentKey(configKey: String, contentKey: String): List<LocalTrack> = storage.read { db ->
        if (!db.localLibraryQueries.hasScan(configKey).executeAsOne()) emptyList()
        else db.localLibraryQueries.selectTracksByContentKey(contentKey).executeAsList().mapNotNull { it.toLocalTrackOrNull() }
    }

    suspend fun clear() {
        storage.write {
            it.localLibraryQueries.clearTracks()
            it.localLibraryQueries.clearScans()
        }
    }
}
