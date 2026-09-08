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
        assertEquals("Tagged Title", parsed.title)
        assertEquals(listOf("Album Artist"), parsed.artists)
        assertEquals("Tagged Album", parsed.album)
        assertEquals(12_500L, parsed.durationMs)
        assertEquals(44_100, parsed.quality.sampleRateHz)
    }
}
