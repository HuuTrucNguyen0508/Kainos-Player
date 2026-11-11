package com.universalmusic.player.data.local

import com.universalmusic.player.data.loudness.LoudnessCatalog
import com.universalmusic.player.data.loudness.LoudnessOrigin
import com.universalmusic.player.data.loudness.LoudnessSubject
import com.universalmusic.player.data.loudness.StoredLoudness
import com.universalmusic.player.data.loudness.withAlbumAggregates
import com.universalmusic.player.data.sync.basenameFromLocalLocation
import com.universalmusic.player.domain.playback.MeasuredLoudness
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Measures one file's EBU R128 loudness; null when it cannot (unreadable, silent). */
fun interface LocalLoudnessAnalyzer {
    suspend fun measure(location: String): MeasuredLoudness?
}

/** ffmpeg on the desktop; null on Android, which levels with values the PC sends over Home sync. */
expect fun createLocalLoudnessAnalyzer(): LocalLoudnessAnalyzer?

/** ebur128 below this is silence, where a gain would be meaningless. */
private const val MEASUREMENT_FLOOR_LUFS = -70.0
private val INTEGRATED = Regex("""\bI:\s+(-?(?:\d+(?:\.\d+)?|inf|nan))\s+LUFS""")
private val TRUE_PEAK = Regex("""\bPeak:\s+(-?(?:\d+(?:\.\d+)?|inf|nan))\s+dBFS""")

/** Integrated loudness and true peak from the summary ffmpeg's `ebur128=peak=true` prints last. */
fun parseEbur128Summary(log: String): MeasuredLoudness? {
    if ("Summary:" !in log) return null
    val summary = log.substringAfterLast("Summary:")
    val lufs = INTEGRATED.find(summary)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val peak = TRUE_PEAK.find(summary)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    if (lufs <= MEASUREMENT_FLOOR_LUFS) return null
    return MeasuredLoudness(lufs, peak)
}

/**
 * What levelling needs to know about a library file. Only files whose album came from a tag and
 * that carry a track number get an album key: an untagged file's album is its folder name, and
 * a shuffled folder of singles must not be levelled as one album.
 */
fun LocalTrack.loudnessSubject(): LoudnessSubject {
    val tagged = album?.takeIf { tagsRead && trackNumber != null && it.isNotBlank() }
    return LoudnessSubject(
        location = location,
        contentLength = contentLength,
        fileModifiedMs = fileModifiedEpochMs,
        durationMs = durationMs,
        albumKey = tagged?.let { localAlbumCanonicalId(it, albumArtists, albumGroupKey) },
        contentKey = contentKey,
        fileName = basenameFromLocalLocation(location),
    )
}

/**
 * Background pass after each scan: measures every file whose path, size or modification time
 * changed since it was last measured, one at a time and cancellable, then refreshes each album's
 * aggregate. Rows are recorded every [PERSIST_EVERY] files so a long first pass survives a restart.
 */
class LocalLoudnessPass(
    private val analyzer: LocalLoudnessAnalyzer?,
    private val catalog: LoudnessCatalog,
) {
    /** Returns how many files it measured. */
    suspend fun run(subjects: List<LoudnessSubject>): Int {
        val analyzer = analyzer ?: return 0
        val pending = subjects.filter { subject -> current(subject) == null }
        val started = currentTimeMillis()
        val batch = mutableListOf<StoredLoudness>()
        for (subject in pending) {
            currentCoroutineContext().ensureActive()
            val measured = runCatching { analyzer.measure(subject.location) }
                .getOrElse { if (it is CancellationException) throw it else null }
            batch += StoredLoudness(
                location = subject.location,
                contentLength = subject.contentLength,
                fileModifiedMs = subject.fileModifiedMs,
                track = measured,
                durationMs = subject.durationMs,
                albumKey = subject.albumKey,
            )
            if (batch.size >= PERSIST_EVERY) {
                catalog.record(batch.toList())
                batch.clear()
            }
        }
        catalog.record(batch)
        refreshAlbums(subjects)
        if (pending.isNotEmpty()) {
            PlaybackTrace.log("Loudness", "measured=${pending.size} of ${subjects.size} in ${currentTimeMillis() - started} ms")
        }
        return pending.size
    }

    /** Measures one file now unless an up-to-date measurement exists, as after a download. */
    suspend fun measure(subject: LoudnessSubject) {
        val analyzer = analyzer ?: return
        if (current(subject) != null) return
        val measured = analyzer.measure(subject.location)
        catalog.record(listOf(StoredLoudness(subject.location, subject.contentLength, subject.fileModifiedMs, measured, subject.durationMs, subject.albumKey)))
    }

    private fun current(subject: LoudnessSubject): StoredLoudness? =
        catalog.stored(subject.location)?.takeIf { it.origin == LoudnessOrigin.MEASURED && it.describes(subject) }

    /** Album keys and durations follow the latest tags, so an unchanged file whose tags were read late still joins its album. */
    private suspend fun refreshAlbums(subjects: List<LoudnessSubject>) {
        val rows = subjects.mapNotNull { subject ->
            current(subject)?.let { it.copy(albumKey = subject.albumKey, durationMs = subject.durationMs ?: it.durationMs) }
        }
        val before = rows.associate { it.location to catalog.stored(it.location) }
        catalog.record(withAlbumAggregates(rows).filter { it != before[it.location] })
    }

    private companion object {
        const val PERSIST_EVERY = 20
    }
}
