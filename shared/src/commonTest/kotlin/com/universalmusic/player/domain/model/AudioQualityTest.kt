package com.universalmusic.player.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioQualityTest {
    @Test
    fun formatsNyquistAndTheoreticalDynamicRange() {
        val quality = AudioQuality(
            tier = QualityTier.LOSSLESS,
            codec = "flac",
            sampleRateHz = 48_000,
            bitDepth = 16,
        )

        assertEquals(24_000, quality.nyquistHz)
        assertEquals(98.08, quality.theoreticalDynamicRangeDb!!, absoluteTolerance = 0.01)
        assertEquals("16-bit · 48 kHz · Nyquist 24 kHz · ~98 dB DR (theoretical)", quality.technicalDetail)
        assertEquals("Lossless · FLAC · 16-bit · 48 kHz", quality.label)
    }

    @Test
    fun formatsFractionalSampleRates() {
        assertEquals("44.1 kHz", formatAudioRate(44_100))
        assertEquals(22_050, AudioQuality(QualityTier.LOSSLESS, sampleRateHz = 44_100).nyquistHz)
    }

    @Test
    fun marksHiResFromRateOrDepth() {
        assertEquals(
            QualityTier.HI_RES,
            refineQualityTier(QualityTier.LOSSLESS, sampleRateHz = 96_000, bitDepth = 24),
        )
        assertEquals(
            QualityTier.LOSSLESS,
            refineQualityTier(QualityTier.LOSSLESS, sampleRateHz = 48_000, bitDepth = 16),
        )
        assertNull(AudioQuality(QualityTier.STANDARD).technicalDetail)
    }
}
