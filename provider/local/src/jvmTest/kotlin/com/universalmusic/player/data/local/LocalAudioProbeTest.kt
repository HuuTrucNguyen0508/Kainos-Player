package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalAudioProbeTest {
    @Test
    fun parsesFfprobeStreamFields() {
        val quality = parseFfprobeQuality(
            rawJson = """
                {
                  "streams": [{
                    "codec_name": "flac",
                    "sample_rate": "48000",
                    "bits_per_sample": 0,
                    "bits_per_raw_sample": "16",
                    "bit_rate": "1536000"
                  }]
                }
            """.trimIndent(),
            fallback = AudioQuality(QualityTier.LOSSLESS, codec = "flac"),
        )

        assertEquals(QualityTier.LOSSLESS, quality.tier)
        assertEquals("flac", quality.codec)
        assertEquals(48_000, quality.sampleRateHz)
        assertEquals(16, quality.bitDepth)
        assertEquals(1_536, quality.bitrateKbps)
        assertEquals(24_000, quality.nyquistHz)
    }

    @Test
    fun keepsFallbackWhenJsonIsEmpty() {
        val fallback = AudioQuality(QualityTier.STANDARD, codec = "mp3", bitrateKbps = 192)
        val quality = parseFfprobeQuality("""{"streams":[]}""", fallback)
        assertEquals(fallback, quality)
    }

    @Test
    fun promotesHiResFromProbedDepth() {
        val quality = parseFfprobeQuality(
            rawJson = """
                {"streams":[{"codec_name":"flac","sample_rate":"96000","bits_per_raw_sample":"24"}]}
            """.trimIndent(),
            fallback = AudioQuality(QualityTier.LOSSLESS, codec = "flac"),
        )
        assertEquals(QualityTier.HI_RES, quality.tier)
        assertEquals(96_000, quality.sampleRateHz)
        assertEquals(24, quality.bitDepth)
    }

    @Test
    fun parsesEmbeddedTagsFromFfprobeJson() {
        val parsed = parseFfprobeMetadata(
            rawJson = """
                {
                  "streams": [{
                    "codec_name": "flac",
                    "codec_type": "audio",
                    "sample_rate": "44100",
                    "bits_per_raw_sample": "16",
                    "bit_rate": "800000"
                  }],
                  "format": {
                    "duration": "12.5",
                    "tags": {
                      "title": "Tagged Title",
                      "artist": "Tagged Artist",
                      "album": "Tagged Album",
                      "album_artist": "Album Artist"
                    }
                  }
                }
            """.trimIndent(),
            fallback = AudioQuality(QualityTier.LOSSLESS, codec = "flac"),
        )
        assertEquals(
            LocalTags(
                title = "Tagged Title",
                artists = listOf("Tagged Artist"),
                album = "Tagged Album",
                albumArtist = "Album Artist",
                durationMs = 12_500L,
            ),
            parsed.tags,
        )
        assertEquals(44_100, parsed.quality.sampleRateHz)
    }

    /** Verbatim shape of ffprobe's answer for a Qobuz FLAC in the owner's library. */
    @Test
    fun readsUpperCaseVorbisCommentsFromFlac() {
        val parsed = parseFfprobeMetadata(
            rawJson = """
                {"streams":[{"codec_name":"flac","codec_type":"audio","disposition":{"attached_pic":0}},
                            {"codec_name":"mjpeg","codec_type":"video","disposition":{"attached_pic":1}}],
                 "format":{"duration":"187.500000","tags":{
                    "ALBUM":"TruE (Honkai Impact 3rd \"Because of You\" Animated Short Theme Song)",
                    "album_artist":"HOYO-MiX;黄龄","ARTIST":"HOYO-MiX","DATE":"2022-08-13","disc":"1",
                    "GENRE":"Électronique","TITLE":"TruE","track":"1"}}}
            """.trimIndent(),
            fallback = AudioQuality(QualityTier.LOSSLESS, codec = "flac"),
        )
        assertEquals(
            LocalTags(
                title = "TruE",
                artists = listOf("HOYO-MiX"),
                album = "TruE (Honkai Impact 3rd \"Because of You\" Animated Short Theme Song)",
                albumArtist = "HOYO-MiX;黄龄",
                trackNumber = 1,
                discNumber = 1,
                year = 2022,
                genre = "Électronique",
                durationMs = 187_500L,
            ),
            parsed.tags,
        )
        assertEquals(true, parsed.hasAttachedPicture)
    }

    @Test
    fun readsStreamTagsFromOpusAndTrackTotalsFromId3() {
        val opus = parseFfprobeMetadata(
            """{"streams":[{"codec_name":"opus","codec_type":"audio","tags":{"TITLE":"Dusk","ARTIST":"Arc North","TRACKNUMBER":"02","DISCNUMBER":"1/2","YEAR":"2019"}}],"format":{"duration":"3.0","tags":{}}}""",
            AudioQuality(QualityTier.STANDARD, codec = "opus"),
        ).tags
        assertEquals(LocalTags(title = "Dusk", artists = listOf("Arc North"), trackNumber = 2, discNumber = 1, year = 2019, durationMs = 3_000L), opus)

        val mp3 = parseFfprobeMetadata(
            """{"streams":[{"codec_name":"mp3","codec_type":"audio"}],"format":{"tags":{"title":"  Spaced   Out ","artist":"A;B","track":"3/12","disc":"2/2","date":"1998","genre":" "}}}""",
            AudioQuality(QualityTier.STANDARD, codec = "mp3"),
        ).tags
        assertEquals(LocalTags(title = "Spaced Out", artists = listOf("A", "B"), trackNumber = 3, discNumber = 2, year = 1998), mp3)
    }
}
