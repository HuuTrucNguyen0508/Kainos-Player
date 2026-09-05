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
        get() {
            val bitrate = bitrateKbps?.let { "$it kbps" }
            val lossless = if (tier == QualityTier.LOSSLESS || tier == QualityTier.HI_RES) {
                listOfNotNull(bitDepth?.let { "${it}-bit" }, sampleRateHz?.let { "${it / 1000} kHz" })
                    .joinToString(" ")
                    .ifBlank { null }
            } else {
                null
            }
            return listOfNotNull(bitrate, lossless, codec).joinToString(" · ").ifBlank { tier.name }
        }
}
