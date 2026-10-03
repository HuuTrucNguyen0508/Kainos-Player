package com.universalmusic.player.data.db

import com.universalmusic.player.data.local.LocalLibraryScanCache
import com.universalmusic.player.data.local.LocalTrack
import com.universalmusic.player.data.local.StoredLocalTrack
import com.universalmusic.player.data.local.toLocalTrackOrNull
import com.universalmusic.player.data.local.toStored
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal fun KainosDatabase.readLocalScan(configKey: String): List<LocalTrack>? {
    if (!localLibraryQueries.hasScan(configKey).executeAsOne()) return null
    return localLibraryQueries.selectTracks().executeAsList().mapNotNull { row ->
        storageJson.decodeFromString<StoredLocalTrack>(row.track_json).toLocalTrackOrNull()
    }
}

internal fun KainosDatabase.writeLocalScan(configKey: String, tracks: List<LocalTrack>, nowMs: Long) {
    val previous = readLocalScan(configKey)
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
        )
    }
}

class DbLocalLibraryScanCache(
    private val storage: KainosStorage,
) : LocalLibraryScanCache {
    override suspend fun read(configKey: String): List<LocalTrack>? =
        storage.read { it.readLocalScan(configKey) }

    override suspend fun write(configKey: String, tracks: List<LocalTrack>) {
        storage.write { it.writeLocalScan(configKey, tracks, currentTimeMillis()) }
    }

    override suspend fun findByContentKey(configKey: String, contentKey: String): List<LocalTrack> = storage.read { db ->
        if (!db.localLibraryQueries.hasScan(configKey).executeAsOne()) emptyList()
        else db.localLibraryQueries.selectTracksByContentKey(contentKey).executeAsList().mapNotNull {
            storageJson.decodeFromString<StoredLocalTrack>(it.track_json).toLocalTrackOrNull()
        }
    }

    suspend fun clear() {
        storage.write {
            it.localLibraryQueries.clearTracks()
            it.localLibraryQueries.clearScans()
        }
    }
}
