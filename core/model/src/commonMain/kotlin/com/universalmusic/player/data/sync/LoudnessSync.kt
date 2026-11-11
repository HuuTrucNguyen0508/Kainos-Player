package com.universalmusic.player.data.sync

import com.universalmusic.player.data.loudness.LoudnessOrigin
import com.universalmusic.player.data.loudness.LoudnessSubject
import com.universalmusic.player.data.loudness.StoredLoudness
import com.universalmusic.player.domain.playback.MeasuredLoudness
import kotlinx.serialization.Serializable

/**
 * Loudness measurements of local files. A phone cannot run ffmpeg, so it levels with what the PC
 * measured. Hubs from before loudness sync answer 404 and the client skips the exchange.
 */
const val HOME_LAN_SYNC_PATH_V3_LOUDNESS = "/kainos-sync/v3/loudness"

/** This device's measurements as Home sync exchanges them, over the library it has now. */
interface LoudnessSyncPort {
    fun export(deviceId: String): LoudnessSyncDocument

    /** Stores what the peer measured of this device's files; returns how many rows changed. */
    suspend fun import(remote: LoudnessSyncDocument): Int
}

/** What one device sends on [HOME_LAN_SYNC_PATH_V3_LOUDNESS]: the files it measured itself. */
@Serializable
data class LoudnessSyncDocument(
    val deviceId: String = "",
    val entries: List<LoudnessSyncEntry> = emptyList(),
)

/** One measured library file, named by its portable `lc1:` content key and its basename. */
@Serializable
data class LoudnessSyncEntry(
    val contentKey: String? = null,
    val fileName: String,
    val integratedLufs: Double,
    val truePeakDbtp: Double,
    val durationMs: Long? = null,
    val albumKey: String? = null,
    val albumLufs: Double? = null,
    val albumPeakDbtp: Double? = null,
)

/** This device's own measurements of the library files in [subjects]; relayed values are not passed on. */
fun exportLoudness(deviceId: String, rows: (String) -> StoredLoudness?, subjects: List<LoudnessSubject>): LoudnessSyncDocument =
    LoudnessSyncDocument(
        deviceId = deviceId,
        entries = subjects.mapNotNull { subject ->
            val row = rows(subject.location)?.takeIf { it.origin == LoudnessOrigin.MEASURED && it.describes(subject) }
            val track = row?.track ?: return@mapNotNull null
            val fileName = subject.fileName ?: return@mapNotNull null
            LoudnessSyncEntry(
                contentKey = subject.contentKey,
                fileName = fileName,
                integratedLufs = track.integratedLufs,
                truePeakDbtp = track.truePeakDbtp,
                durationMs = row.durationMs,
                albumKey = row.albumKey,
                albumLufs = row.album?.integratedLufs,
                albumPeakDbtp = row.album?.truePeakDbtp,
            )
        },
    )

/**
 * Rows for this device's files that [remote] measured: by content key first, then by a basename
 * that is unique on both sides. Files this device measured itself keep their own values.
 */
fun importLoudness(
    remote: LoudnessSyncDocument,
    subjects: List<LoudnessSubject>,
    rows: (String) -> StoredLoudness?,
): List<StoredLoudness> {
    val byKey = remote.entries.filter { it.contentKey != null }.groupBy { it.contentKey!! }
    val remoteByName = remote.entries.groupBy { it.fileName }
    val localNameCounts = subjects.mapNotNull { it.fileName }.groupingBy { it }.eachCount()
    return subjects.mapNotNull { subject ->
        val existing = rows(subject.location)
        if (existing?.origin == LoudnessOrigin.MEASURED && existing.describes(subject)) return@mapNotNull null
        val entry = subject.contentKey?.let { byKey[it]?.singleOrNull() }
            ?: subject.fileName?.takeIf { localNameCounts[it] == 1 }?.let { remoteByName[it]?.singleOrNull() }
            ?: return@mapNotNull null
        StoredLoudness(
            location = subject.location,
            contentLength = subject.contentLength,
            fileModifiedMs = subject.fileModifiedMs,
            track = MeasuredLoudness(entry.integratedLufs, entry.truePeakDbtp),
            durationMs = entry.durationMs ?: subject.durationMs,
            albumKey = entry.albumKey,
            album = if (entry.albumLufs != null && entry.albumPeakDbtp != null) MeasuredLoudness(entry.albumLufs, entry.albumPeakDbtp) else null,
            origin = LoudnessOrigin.SYNCED,
        ).takeIf { it != existing }
    }
}
