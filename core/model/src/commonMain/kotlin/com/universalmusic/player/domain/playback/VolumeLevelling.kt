package com.universalmusic.player.domain.playback

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What volume levelling does to the track that is audible now, as the engine playing it reports. */
sealed interface Levelling {
    /** The setting is off. */
    data object Off : Levelling

    /**
     * The setting is on, but the file has neither ReplayGain tags nor a loudness measurement, so
     * the track plays at its own level.
     */
    data object Untagged : Levelling

    /** The player applies this gain in dB, preamp and peak limit included, taken from [source]. */
    data class Applied(val gainDb: Double, val source: GainSource = GainSource.TAGS) : Levelling
}

/** Where a file's gain came from. Tags always win over an analysis of the audio. */
enum class GainSource { TAGS, ANALYSED }

/**
 * Gain in dB against the -18 LUFS ReplayGain reference, and a linear full-scale peak: the sample
 * peak a tagger wrote for [GainSource.TAGS], the true peak ffmpeg measured for [GainSource.ANALYSED].
 */
data class ReplayGain(val gainDb: Double, val peak: Double?, val source: GainSource = GainSource.TAGS)

/** An EBU R128 measurement of one file, or the aggregate of an album's files. */
data class MeasuredLoudness(val integratedLufs: Double, val truePeakDbtp: Double)

/**
 * What analysis knows about one file: its own loudness, and its album's once the album's files
 * were measured. [albumKey] is opaque; it only says whether two files belong to the same album.
 */
data class TrackLoudness(
    val track: MeasuredLoudness,
    val album: MeasuredLoudness? = null,
    val albumKey: String? = null,
)

/** Analysed loudness by playback location (a path, `file:` URI or content URI); null when unmeasured. */
fun interface LoudnessLookup {
    fun loudnessOf(location: String): TrackLoudness?

    companion object {
        val None = LoudnessLookup { null }
    }
}

object VolumeLevelling {
    /**
     * ReplayGain targets -18 LUFS. Spotify plays at Loud (-14 LUFS plus a 3 dB pregain), so
     * 7 dB more puts levelled local files near it, before the peak limit.
     */
    const val PREAMP_DB = 7.0

    /** The loudness ReplayGain gains are relative to. */
    const val REFERENCE_LUFS = -18.0

    /**
     * Analysed files keep their measured true peak this far below full scale, as the Echo
     * gateway does: a boosted file never clips, even after a lossy re-encode or resampling.
     */
    const val TRUE_PEAK_CEILING_DBTP = -1.0

    /**
     * Track gain, falling back to album gain as ffmpeg's `volume=replaygain=track` does.
     * [tag] looks a tag up by its upper-case name.
     */
    fun parse(tag: (String) -> String?): ReplayGain? {
        for (scope in listOf("TRACK", "ALBUM")) {
            val gain = tag("REPLAYGAIN_${scope}_GAIN")?.leadingNumber() ?: continue
            return ReplayGain(gain, tag("REPLAYGAIN_${scope}_PEAK")?.leadingNumber()?.takeIf { it > 0 })
        }
        return null
    }

    /** A measurement expressed as the ReplayGain a tagger would have written for it. */
    fun analysed(loudness: MeasuredLoudness): ReplayGain = ReplayGain(
        gainDb = REFERENCE_LUFS - loudness.integratedLufs,
        peak = 10.0.pow(loudness.truePeakDbtp / 20),
        source = GainSource.ANALYSED,
    )

    /**
     * The gain for one file: its tags when it has them, otherwise its analysed track gain. When the
     * file follows [previousAlbumKey]'s file gaplessly and is on the same album, the album gain
     * applies instead, so the level does not change between the tracks of an album played in order.
     */
    fun choose(tags: ReplayGain?, analysed: TrackLoudness?, previousAlbumKey: String? = null): ReplayGain? {
        if (tags != null) return tags
        val loudness = analysed ?: return null
        val album = loudness.album?.takeIf { loudness.albumKey != null && loudness.albumKey == previousAlbumKey }
        return analysed(album ?: loudness.track)
    }

    /**
     * Gain plus [PREAMP_DB], never above what the peak allows or [ceilingDb]. A tagged sample peak
     * may reach full scale; an analysed true peak stops at [TRUE_PEAK_CEILING_DBTP]. The peak only
     * limits a boost: many masters already peak above 0 dBTP, and cutting one below its own level
     * for that would leave it quieter than its neighbours without removing any overs it shipped with.
     */
    fun appliedGainDb(replayGain: ReplayGain, ceilingDb: Double = Double.POSITIVE_INFINITY): Double {
        val headroomDb = if (replayGain.source == GainSource.ANALYSED) TRUE_PEAK_CEILING_DBTP else 0.0
        val peakLimitDb = replayGain.peak?.let { max(headroomDb - 20 * log10(it), 0.0) } ?: Double.POSITIVE_INFINITY
        return min(min(replayGain.gainDb + PREAMP_DB, peakLimitDb), ceilingDb)
    }

    private fun String.leadingNumber(): Double? =
        Regex("""^\s*([+-]?\d+(?:\.\d+)?)""").find(this)?.groupValues?.get(1)?.toDoubleOrNull()
}
