package com.universalmusic.player.data.local

import com.universalmusic.player.data.loudness.LoudnessCatalog
import com.universalmusic.player.data.loudness.LoudnessSubject
import com.universalmusic.player.data.loudness.StoredLoudness
import com.universalmusic.player.domain.playback.MeasuredLoudness
import com.universalmusic.player.domain.playback.TrackLoudness
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalLoudnessTest {
    /** The tail of a real `ffmpeg -af ebur128=peak=true` run over a 5-minute FLAC (ffmpeg 9.0). */
    private val realSummary = """
        [Parsed_ebur128_0 @ 0x7fc5c0003d00] Summary:

          Integrated loudness:
            I:         -12.6 LUFS
            Threshold: -22.7 LUFS

          Loudness range:
            LRA:         7.1 LU
            Threshold: -32.7 LUFS
            LRA low:   -16.9 LUFS
            LRA high:   -9.8 LUFS

          True peak:
            Peak:        1.0 dBFS
        [out#0/null @ 0x560874851ac0] video:0KiB audio:51698KiB subtitle:0KiB other streams:0KiB
    """.trimIndent()

    @Test fun parsesIntegratedLoudnessAndTruePeakFromTheSummary() {
        assertEquals(MeasuredLoudness(-12.6, 1.0), parseEbur128Summary(realSummary))
        val framesThenSummary = "[Parsed_ebur128_0] t: 0.1 TARGET:-23 LUFS M: -20.1 S:-120.7 I: -19.0 LUFS\n$realSummary"
        assertEquals(MeasuredLoudness(-12.6, 1.0), parseEbur128Summary(framesThenSummary), "only the summary counts")
    }

    @Test fun silenceAndBrokenOutputAreNotMeasurements() {
        assertNull(parseEbur128Summary(realSummary.replace("-12.6 LUFS", "-70.0 LUFS")))
        assertNull(parseEbur128Summary(realSummary.replace("1.0 dBFS", "-inf dBFS")))
        assertNull(parseEbur128Summary("Error opening input file"))
    }

    private class MemoryCatalog : LoudnessCatalog {
        val rows = mutableMapOf<String, StoredLoudness>()
        var writes = 0
        override fun stored(location: String) = rows[location]
        override fun loudnessOf(location: String) = rows[location]?.track?.let { TrackLoudness(it, rows[location]?.album, rows[location]?.albumKey) }
        override suspend fun record(rows: List<StoredLoudness>) {
            if (rows.isNotEmpty()) writes++
            rows.forEach { this.rows[it.location] = it }
        }
    }

    @Test fun measuresOnlyNewOrChangedFilesAndKeepsAlbumsCurrent() = runTest {
        val opened = mutableListOf<String>()
        val catalog = MemoryCatalog()
        catalog.rows["/m/1.flac"] = StoredLoudness("/m/1.flac", 100, 5, MeasuredLoudness(-10.0, -2.0), 60_000, null)
        val pass = LocalLoudnessPass({ location -> opened += location; MeasuredLoudness(-20.0, -2.0) }, catalog)
        val files = listOf(
            LoudnessSubject("/m/1.flac", 100, 5, 60_000, "album"),
            LoudnessSubject("/m/2.flac", 200, 5, 60_000, "album"),
        )
        assertEquals(1, pass.run(files))
        assertEquals(listOf("/m/2.flac"), opened, "an unchanged file is never opened again")
        val album = assertNotNull(catalog.rows["/m/1.flac"]?.album)
        assertEquals(album, catalog.rows["/m/2.flac"]?.album)
        assertEquals("album", catalog.rows["/m/1.flac"]?.albumKey, "tags read after the first measurement still join the album")
        assertEquals(0, pass.run(files))
        assertEquals(1, opened.size)
        assertEquals(1, pass.run(listOf(files[0].copy(fileModifiedMs = 6))), "a changed file is measured again")
    }

    @Test fun onlyTaggedAlbumTracksGetAnAlbumKey() {
        val track = LocalTrack(id = "1", title = "Song", artists = listOf("A"), album = "Music", albumGroupKey = "/m", location = "/m/Song.flac")
        assertNull(track.loudnessSubject().albumKey, "a folder-name album from a filename guess")
        val tagged = track.copy(tagsVersion = LOCAL_TAGS_VERSION, trackNumber = 3)
        assertEquals(localAlbumCanonicalId("Music", tagged.albumArtists, "/m"), tagged.loudnessSubject().albumKey)
        assertNull(track.copy(tagsVersion = LOCAL_TAGS_VERSION).loudnessSubject().albumKey, "no track number: a single, not an album track")
        assertEquals("song.flac", track.loudnessSubject().fileName, "the sync basename, as hearts match it")
    }
}
