package com.universalmusic.player.domain.model

import kotlin.math.roundToInt

enum class QualityTier {
    LOW,
    STANDARD,
    HIGH,
    LOSSLESS,
    HI_RES,
}

data class AudioQuality(
    val tier: QualityTier,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
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

    val label: String
        get() = buildList {
            add(
                when (tier) {
                    QualityTier.LOW -> "Low"
                    QualityTier.STANDARD -> "Standard"
                    QualityTier.HIGH -> "High"
                    QualityTier.LOSSLESS -> "Lossless"
                    QualityTier.HI_RES -> "Hi-Res"
                },
            )
            codec?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.uppercase()) }
            bitrateKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
            if (tier == QualityTier.LOSSLESS || tier == QualityTier.HI_RES) {
                bitDepth?.let { add("${it}-bit") }
                sampleRateHz?.takeIf { it > 0 }?.let { add(formatAudioRate(it)) }
            }
        }.joinToString(" · ")

    /** Extra technical line for Now Playing / inspectors. */
    val technicalDetail: String?
        get() {
            val parts = buildList {
                bitDepth?.takeIf { it > 0 }?.let { add("${it}-bit") }
                sampleRateHz?.takeIf { it > 0 }?.let { add(formatAudioRate(it)) }
                nyquistHz?.takeIf { it > 0 }?.let { add("Nyquist ${formatAudioRate(it)}") }
                theoreticalDynamicRangeDb?.let { add("~${it.roundToInt()} dB DR (theoretical)") }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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
