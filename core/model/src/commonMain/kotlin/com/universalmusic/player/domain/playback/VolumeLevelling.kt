package com.universalmusic.player.domain.playback

import kotlin.math.log10
import kotlin.math.min

/** What volume levelling does to the track that is audible now, as the engine playing it reports. */
sealed interface Levelling {
    /** The setting is off. */
    data object Off : Levelling

    /** The setting is on, but the player found no ReplayGain it can apply, so the track plays at its own level. */
    data object Untagged : Levelling

    /** The player applies this gain in dB, preamp and peak limit included. */
    data class Applied(val gainDb: Double) : Levelling
}

/** ReplayGain from a file's tags: gain in dB against the -18 LUFS reference, peak in linear full scale. */
data class ReplayGain(val gainDb: Double, val peak: Double?)

object VolumeLevelling {
    /**
     * ReplayGain targets -18 LUFS. Spotify plays at Loud (-14 LUFS plus a 3 dB pregain), so
     * 7 dB more puts levelled local files near it, before the peak limit.
     */
    const val PREAMP_DB = 7.0

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

    /** Gain plus [PREAMP_DB], never above what the peak allows or [ceilingDb]. */
    fun appliedGainDb(replayGain: ReplayGain, ceilingDb: Double = Double.POSITIVE_INFINITY): Double {
        val peakLimitDb = replayGain.peak?.let { -20 * log10(it) } ?: Double.POSITIVE_INFINITY
        return min(min(replayGain.gainDb + PREAMP_DB, peakLimitDb), ceilingDb)
    }

    private fun String.leadingNumber(): Double? =
        Regex("""^\s*([+-]?\d+(?:\.\d+)?)""").find(this)?.groupValues?.get(1)?.toDoubleOrNull()
}
