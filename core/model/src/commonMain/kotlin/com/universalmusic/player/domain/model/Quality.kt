package com.universalmusic.player.domain.model

import kotlin.math.roundToInt

enum class QualityTier {
    LOW,
    STANDARD,
    HIGH,
    LOSSLESS,
    HI_RES,
}

/**
 * How much of [AudioQuality] was actually measured.
 * Output PCM (for example a Connect decode at 16-bit / 44.1 kHz) is not source quality.
 */
enum class QualityConfidence {
    /** Measured from the file or stream. */
    VERIFIED,
    /** Inferred from the container or extension, not measured. */
    ASSUMED,
    /** The provider does not report source quality. */
    UNKNOWN,
}

data class AudioQuality(
    val tier: QualityTier,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val confidence: QualityConfidence = QualityConfidence.VERIFIED,
) {
    /** Highest reproducible frequency for band-limited PCM: sample rate / 2. */
    val nyquistHz: Int?
        get() = sampleRateHz?.takeIf { it > 0 }?.div(2)

    /**
     * Theoretical PCM dynamic range from bit depth (≈ 6.02·N + 1.76 dB).
     * This is not a measured TT Dynamic Range score; that needs full-file analysis.
     */
    val theoreticalDynamicRangeDb: Double?
        get() = bitDepth?.takeIf { it > 0 }?.let { bits -> 6.02 * bits + 1.76 }

    /** Compact source-quality badge. Unknown means the provider did not report a format. */
    val label: String
        get() = when (confidence) {
            QualityConfidence.UNKNOWN -> "Unknown"
            QualityConfidence.ASSUMED, QualityConfidence.VERIFIED -> buildList {
                add(
                    when (tier) {
                        QualityTier.LOW -> "Low"
                        QualityTier.STANDARD -> "Standard"
                        QualityTier.HIGH -> "High"
                        QualityTier.LOSSLESS -> "Lossless"
                        QualityTier.HI_RES -> "Hi-Res"
                    },
                )
                if (confidence == QualityConfidence.ASSUMED) add("assumed")
                codec?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.uppercase()) }
                bitrateKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
                if (tier == QualityTier.LOSSLESS || tier == QualityTier.HI_RES) {
                    bitDepth?.let { add("${it}-bit") }
                    sampleRateHz?.takeIf { it > 0 }?.let { add(formatAudioRate(it)) }
                }
            }.joinToString(" · ")
        }

    /** Extra technical line for the Audio details panel. */
    val technicalDetail: String?
        get() {
            if (confidence == QualityConfidence.UNKNOWN) {
                val output = buildList {
                    bitDepth?.takeIf { it > 0 }?.let { add("${it}-bit") }
                    sampleRateHz?.takeIf { it > 0 }?.let { add(formatAudioRate(it)) }
                }.joinToString(" · ")
                return if (output.isEmpty()) {
                    "Source quality is not reported by this provider."
                } else {
                    "Source quality is not reported. Playback output is typically $output, which is not the source format."
                }
            }
            val parts = buildList {
                if (confidence == QualityConfidence.ASSUMED) add("Format assumed from the file type")
                bitDepth?.takeIf { it > 0 }?.let { add("${it}-bit") }
                sampleRateHz?.takeIf { it > 0 }?.let { add(formatAudioRate(it)) }
                nyquistHz?.takeIf { it > 0 }?.let { add("Nyquist ${formatAudioRate(it)}") }
                theoreticalDynamicRangeDb?.let { add("~${it.roundToInt()} dB DR (theoretical)") }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

    /** Rank key for automatic selection. Unverified source quality does not outrank a measured tier. */
    fun tierRank(): Int = when (confidence) {
        QualityConfidence.UNKNOWN -> 0
        QualityConfidence.ASSUMED, QualityConfidence.VERIFIED -> when (tier) {
            QualityTier.HI_RES -> 5
            QualityTier.LOSSLESS -> 4
            QualityTier.HIGH -> 3
            QualityTier.STANDARD -> 2
            QualityTier.LOW -> 1
        }
    }
}

fun formatAudioRate(hz: Int): String = when {
    hz % 1000 == 0 -> "${hz / 1000} kHz"
    else -> {
        val khz = hz / 1000.0
        val text = ((khz * 10).roundToInt() / 10.0).toString().removeSuffix(".0")
        "$text kHz"
    }
}

fun refineQualityTier(
    base: QualityTier,
    sampleRateHz: Int?,
    bitDepth: Int?,
): QualityTier {
    val hiRes = (sampleRateHz != null && sampleRateHz > 48_000) ||
        (bitDepth != null && bitDepth > 16)
    return when {
        hiRes && (base == QualityTier.LOSSLESS || base == QualityTier.HI_RES || base == QualityTier.HIGH) ->
            QualityTier.HI_RES
        else -> base
    }
}
