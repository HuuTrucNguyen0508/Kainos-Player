package com.universalmusic.player.domain.playback

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
}
