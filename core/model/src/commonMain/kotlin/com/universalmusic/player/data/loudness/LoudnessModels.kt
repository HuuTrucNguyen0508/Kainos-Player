package com.universalmusic.player.data.loudness

import com.universalmusic.player.data.local.percentDecodeUtf8
import com.universalmusic.player.domain.playback.LoudnessLookup
import com.universalmusic.player.domain.playback.MeasuredLoudness
import kotlin.math.log10
import kotlin.math.pow

/** Where a stored measurement came from. */
enum class LoudnessOrigin {
    /** ffmpeg measured this device's own file. */
    MEASURED,

    /** Another device measured the same file and sent the result over Home sync. */
    SYNCED,
}

/**
 * One file's loudness as `track_loudness` keeps it. [contentLength] and [fileModifiedMs] describe
 * the file when it was measured, so an unchanged file is never measured twice. [track] is null
 * when ffmpeg could not measure the file (silence, unreadable); the row still stops a retry until
 * the file changes.
 */
data class StoredLoudness(
    val location: String,
    val contentLength: Long?,
    val fileModifiedMs: Long?,
    val track: MeasuredLoudness?,
    val durationMs: Long? = null,
    val albumKey: String? = null,
    val album: MeasuredLoudness? = null,
    val origin: LoudnessOrigin = LoudnessOrigin.MEASURED,
) {
    fun describes(subject: LoudnessSubject): Boolean =
        loudnessLocationKey(location) == loudnessLocationKey(subject.location) && contentLength == subject.contentLength && fileModifiedMs == subject.fileModifiedMs
}

/** A file levelling may need a measurement for, as this device sees it now. */
data class LoudnessSubject(
    val location: String,
    val contentLength: Long?,
    val fileModifiedMs: Long?,
    val durationMs: Long? = null,
    val albumKey: String? = null,
    /** Portable `lc1:` content key, matched across devices before the basename is. */
    val contentKey: String? = null,
    val fileName: String? = null,
)

interface LoudnessStore {
    suspend fun readAll(): List<StoredLoudness>
    suspend fun upsert(rows: List<StoredLoudness>)
}

/** The measurements this device holds, read synchronously by the player and written by the passes that fill them. */
interface LoudnessCatalog : LoudnessLookup {
    fun stored(location: String): StoredLoudness?
    suspend fun record(rows: List<StoredLoudness>)
}

/** Measurements are keyed by plain path; hearted-cache files play as `file:` URIs of the same path. */
fun loudnessLocationKey(location: String): String {
    if (!location.startsWith("file:")) return location
    val path = location.removePrefix("file:").let { if (it.startsWith("///")) it.removePrefix("//") else it }
    return if (path.startsWith("/")) percentDecodeUtf8(path) else location
}

/**
 * An album's loudness from its tracks' measurements: the duration-weighted energy mean of their
 * integrated loudness, and the highest true peak. Close to measuring the whole album as one
 * programme, without decoding it again. Null unless every track has a measurement and a duration.
 */
fun albumLoudness(tracks: List<Pair<MeasuredLoudness?, Long?>>): MeasuredLoudness? {
    if (tracks.isEmpty()) return null
    var energy = 0.0
    var duration = 0.0
    var peak = Double.NEGATIVE_INFINITY
    for ((loudness, durationMs) in tracks) {
        if (loudness == null || durationMs == null || durationMs <= 0) return null
        energy += durationMs * 10.0.pow(loudness.integratedLufs / 10)
        duration += durationMs
        peak = maxOf(peak, loudness.truePeakDbtp)
    }
    return MeasuredLoudness(10 * log10(energy / duration), peak)
}

/**
 * [rows] with each one's album aggregate taken over the rows sharing its album key. An album
 * with a single file here, or with any file still unmeasured, gets none.
 */
fun withAlbumAggregates(rows: List<StoredLoudness>): List<StoredLoudness> {
    val aggregates = rows.filter { it.albumKey != null }
        .groupBy { it.albumKey!! }
        .mapValues { (_, album) -> album.takeIf { it.size > 1 }?.let { tracks -> albumLoudness(tracks.map { it.track to it.durationMs }) } }
    return rows.map { row -> row.copy(album = row.albumKey?.let(aggregates::get)) }
}
