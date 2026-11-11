package com.universalmusic.player.data.db

import com.universalmusic.player.data.loudness.LoudnessOrigin
import com.universalmusic.player.data.loudness.LoudnessStore
import com.universalmusic.player.data.loudness.StoredLoudness
import com.universalmusic.player.domain.failure.StorageArea
import com.universalmusic.player.domain.playback.MeasuredLoudness

class DbLoudnessStore(storage: KainosStorage) : LoudnessStore {
    private val storage = AreaStorage(storage, StorageArea.LOCAL_SCAN)

    override suspend fun readAll(): List<StoredLoudness> = storage.read { db ->
        db.trackLoudnessQueries.selectAll().executeAsList().map { row ->
            StoredLoudness(
                location = row.location,
                contentLength = row.content_length,
                fileModifiedMs = row.file_modified_ms,
                track = measured(row.integrated_lufs, row.true_peak_dbtp),
                durationMs = row.duration_ms,
                albumKey = row.album_key,
                album = measured(row.album_lufs, row.album_peak_dbtp),
                origin = if (row.origin == ORIGIN_SYNCED) LoudnessOrigin.SYNCED else LoudnessOrigin.MEASURED,
            )
        }
    }

    override suspend fun upsert(rows: List<StoredLoudness>) {
        if (rows.isEmpty()) return
        storage.write { db ->
            for (row in rows) {
                db.trackLoudnessQueries.upsert(
                    row.location,
                    row.contentLength,
                    row.fileModifiedMs,
                    row.track?.integratedLufs,
                    row.track?.truePeakDbtp,
                    row.durationMs,
                    row.albumKey,
                    row.album?.integratedLufs,
                    row.album?.truePeakDbtp,
                    if (row.origin == LoudnessOrigin.SYNCED) ORIGIN_SYNCED else ORIGIN_MEASURED,
                )
            }
        }
    }

    private fun measured(lufs: Double?, peak: Double?) =
        if (lufs != null && peak != null) MeasuredLoudness(lufs, peak) else null

    private companion object {
        const val ORIGIN_MEASURED = "measured"
        const val ORIGIN_SYNCED = "synced"
    }
}
