package com.universalmusic.player.data.loudness

import com.universalmusic.player.data.sync.LoudnessSyncDocument
import com.universalmusic.player.data.sync.LoudnessSyncEntry
import com.universalmusic.player.data.sync.exportLoudness
import com.universalmusic.player.data.sync.importLoudness
import com.universalmusic.player.domain.playback.MeasuredLoudness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoudnessModelsTest {
    @Test fun albumLoudnessIsTheDurationWeightedEnergyMeanAndTheHighestPeak() {
        val album = albumLoudness(listOf(MeasuredLoudness(-10.0, -1.0) to 60_000L, MeasuredLoudness(-20.0, -5.0) to 60_000L))!!
        // Mean of 0.1 and 0.01 in energy: 0.055 -> -12.596 LUFS, far closer to the loud track than the -15 average.
        assertEquals(-12.596, album.integratedLufs, 1e-3)
        assertEquals(-1.0, album.truePeakDbtp, 1e-9)
        assertEquals(-10.0, albumLoudness(listOf(MeasuredLoudness(-10.0, -1.0) to 1L, MeasuredLoudness(-10.0, -2.0) to 9L))!!.integratedLufs, 1e-9)
        assertNull(albumLoudness(listOf(MeasuredLoudness(-10.0, -1.0) to 60_000L, null to 60_000L)), "every track must be measured")
        assertNull(albumLoudness(listOf(MeasuredLoudness(-10.0, -1.0) to null)))
    }

    @Test fun aggregatesGoOnlyToAlbumsWithSeveralFiles() {
        fun row(location: String, album: String?) = StoredLoudness(location, 1, 1, MeasuredLoudness(-10.0, -1.0), 1_000, album)
        val rows = withAlbumAggregates(listOf(row("a", "x"), row("b", "x"), row("c", "y"), row("d", null)))
        assertEquals(MeasuredLoudness(-10.0, -1.0), rows[0].album)
        assertEquals(rows[0].album, rows[1].album)
        assertNull(rows[2].album, "a single-file album has nothing to hold level across")
        assertNull(rows[3].album)
    }

    @Test fun cacheFileUrisAndPathsShareOneKey() {
        assertEquals("/home/u/audio-cache/a b.m4a", loudnessLocationKey("file:/home/u/audio-cache/a%20b.m4a"))
        assertEquals("/home/u/x.flac", loudnessLocationKey("file:///home/u/x.flac"))
        assertEquals("/home/u/x.flac", loudnessLocationKey("/home/u/x.flac"))
        assertEquals("content://tree/x%20y", loudnessLocationKey("content://tree/x%20y"))
    }

    private val pcFile = LoudnessSubject("/pc/a.flac", 10, 1, 1_000, "album", "lc1:" + "a".repeat(64), "a.flac")

    @Test fun exportCarriesOnlyThisDevicesOwnUpToDateMeasurements() {
        val rows = mapOf(
            "/pc/a.flac" to StoredLoudness("/pc/a.flac", 10, 1, MeasuredLoudness(-9.0, -1.0), 1_000, "album", MeasuredLoudness(-10.0, 0.0)),
            "/pc/stale.flac" to StoredLoudness("/pc/stale.flac", 10, 1, MeasuredLoudness(-9.0, -1.0)),
            "/pc/relayed.flac" to StoredLoudness("/pc/relayed.flac", 10, 1, MeasuredLoudness(-9.0, -1.0), origin = LoudnessOrigin.SYNCED),
        )
        val subjects = listOf(pcFile, LoudnessSubject("/pc/stale.flac", 11, 1, fileName = "stale.flac"),
            LoudnessSubject("/pc/relayed.flac", 10, 1, fileName = "relayed.flac"))
        val document = exportLoudness("pc", rows::get, subjects)
        assertEquals(listOf(LoudnessSyncEntry(pcFile.contentKey, "a.flac", -9.0, -1.0, 1_000, "album", -10.0, 0.0)), document.entries)
    }

    @Test fun importNeverOverwritesWhatThisDeviceMeasuredItself() {
        val remote = LoudnessSyncDocument("pc", listOf(LoudnessSyncEntry(null, "a.flac", -9.0, -1.0)))
        val own = StoredLoudness("/here/a.flac", 10, 1, MeasuredLoudness(-12.0, -3.0))
        val here = LoudnessSubject("/here/a.flac", 10, 1, fileName = "a.flac")
        assertEquals(emptyList(), importLoudness(remote, listOf(here), mapOf(here.location to own)::get))
        val changed = here.copy(contentLength = 11)
        assertEquals(LoudnessOrigin.SYNCED, importLoudness(remote, listOf(changed), mapOf(here.location to own)::get).single().origin,
            "a file changed since this device measured it takes the peer's value until it is measured again")
    }
}
