package com.universalmusic.player.domain.model

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
                sampleRateHz?.takeIf { it > 0 }?.let { add("${it / 1000} kHz") }
            }
        }.joinToString(" · ")
}
