package com.universalmusic.player.domain.playback

import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VolumeLevellingTest {
    private fun parse(vararg tags: Pair<String, String>) = VolumeLevelling.parse(tags.toMap()::get)

    @Test fun readsTrackGainAndPeakAsTaggersWriteThem() {
        assertEquals(ReplayGain(-6.5, 0.5), parse("REPLAYGAIN_TRACK_GAIN" to "-6.50 dB", "REPLAYGAIN_TRACK_PEAK" to "0.500000"))
        assertEquals(ReplayGain(2.0, null), parse("REPLAYGAIN_TRACK_GAIN" to "+2 dB"))
    }

    @Test fun fallsBackToAlbumGainLikeFfmpeg() {
        assertEquals(ReplayGain(-3.0, 0.9), parse("REPLAYGAIN_ALBUM_GAIN" to "-3.00 dB", "REPLAYGAIN_ALBUM_PEAK" to "0.9"))
    }

    @Test fun ignoresMissingOrUnreadableGain() {
        assertNull(parse("TITLE" to "Song"))
        assertNull(parse("REPLAYGAIN_TRACK_GAIN" to "loud"))
    }

    @Test fun preampThenPeakLimitThenCeiling() {
        assertEquals(-6.0, VolumeLevelling.appliedGainDb(ReplayGain(-13.0, 0.1)), 1e-9)
        assertEquals(6.0206, VolumeLevelling.appliedGainDb(ReplayGain(3.0, 0.5)), 1e-4)
        assertEquals(9.0, VolumeLevelling.appliedGainDb(ReplayGain(2.0, null)), 1e-9)
        assertEquals(0.0, VolumeLevelling.appliedGainDb(ReplayGain(-2.0, 0.2), ceilingDb = 0.0), 1e-9)
    }

    private val loud = MeasuredLoudness(-9.0, -3.0)
    private val albumLevel = MeasuredLoudness(-14.0, -2.0)
    private val analysed = TrackLoudness(loud, albumLevel, "album-1")

    @Test fun tagsWinOverAnalysisWhichWinsOverNothing() {
        val tags = ReplayGain(-6.0, 0.5)
        assertEquals(tags, VolumeLevelling.choose(tags, analysed))
        assertEquals(ReplayGain(-9.0, 10.0.pow(-3.0 / 20), GainSource.ANALYSED), VolumeLevelling.choose(null, analysed))
        assertNull(VolumeLevelling.choose(null, null))
    }

    @Test fun albumGainOnlyAfterAFileOfTheSameAlbum() {
        val track = VolumeLevelling.choose(null, analysed, previousAlbumKey = null)!!
        val other = VolumeLevelling.choose(null, analysed, previousAlbumKey = "album-2")!!
        val inAlbum = VolumeLevelling.choose(null, analysed, previousAlbumKey = "album-1")!!
        assertEquals(-9.0, track.gainDb, 1e-9)
        assertEquals(-9.0, other.gainDb, 1e-9)
        assertEquals(-4.0, inAlbum.gainDb, 1e-9)
        assertEquals(-2.0, 20 * log10(inAlbum.peak!!), 1e-9, "the album's peak bounds the album gain")
        assertEquals(-9.0, VolumeLevelling.choose(null, analysed.copy(album = null), "album-1")!!.gainDb, 1e-9,
            "an album without an aggregate falls back to the track gain")
    }

    @Test fun analysedBoostStopsOneDecibelUnderTheTruePeak() {
        // -30 LUFS asks for +12 + 7 = +19 dB; a -10 dBTP peak allows +9 dB.
        assertEquals(9.0, VolumeLevelling.appliedGainDb(VolumeLevelling.analysed(MeasuredLoudness(-30.0, -10.0))), 1e-9)
        // A loud master: -12.6 LUFS wants +1.6 dB, but a +1.0 dBTP peak brings it to -2 dB.
        assertEquals(-2.0, VolumeLevelling.appliedGainDb(VolumeLevelling.analysed(MeasuredLoudness(-12.6, 1.0))), 1e-9)
        // Unbounded by the peak, the target is Spotify Loud: -11 LUFS.
        assertEquals(3.0, VolumeLevelling.appliedGainDb(VolumeLevelling.analysed(MeasuredLoudness(-14.0, -10.0))), 1e-9)
    }
}
