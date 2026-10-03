package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityConfidence
import com.universalmusic.player.domain.model.QualityTier
import kotlinx.serialization.Serializable

/** On-disk shape of a local library row. Shared by the JSON caches and the database. */
@Serializable
data class StoredLocalTrack(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumGroupKey: String = "",
    val durationMs: Long? = null,
    val artworkUri: String? = null,
    val location: String,
    val contentLength: Long? = null,
    val qualityTier: String? = null,
    val qualityCodec: String? = null,
    val qualityBitrateKbps: Int? = null,
    val qualitySampleRateHz: Int? = null,
    val qualityBitDepth: Int? = null,
    val qualityConfidence: String? = null,
    val explicit: Boolean = false,
    val isrc: String? = null,
    val fileModifiedEpochMs: Long? = null,
    val contentKey: String? = null,
)

fun LocalTrack.toStored() = StoredLocalTrack(
    id = id,
    title = title,
    artists = artists,
    album = album,
    albumGroupKey = albumGroupKey,
    durationMs = durationMs,
    artworkUri = artworkUri,
    location = location,
    contentLength = contentLength,
    qualityTier = quality?.tier?.name,
    qualityCodec = quality?.codec,
    qualityBitrateKbps = quality?.bitrateKbps,
    qualitySampleRateHz = quality?.sampleRateHz,
    qualityBitDepth = quality?.bitDepth,
    qualityConfidence = quality?.confidence?.name,
    explicit = explicit,
    isrc = isrc,
    fileModifiedEpochMs = fileModifiedEpochMs,
    contentKey = contentKey,
)

fun StoredLocalTrack.toLocalTrackOrNull(): LocalTrack? {
    if (id.isBlank() || title.isBlank() || location.isBlank()) return null
    val quality = qualityTier?.let { tierName ->
        val tier = runCatching { QualityTier.valueOf(tierName) }.getOrNull() ?: return@let null
        AudioQuality(
            tier = tier,
            codec = qualityCodec,
            bitrateKbps = qualityBitrateKbps,
            sampleRateHz = qualitySampleRateHz,
            bitDepth = qualityBitDepth,
            confidence = qualityConfidence
                ?.let { runCatching { QualityConfidence.valueOf(it) }.getOrNull() }
                ?: QualityConfidence.VERIFIED,
        )
    }
    return LocalTrack(
        id = id,
        title = title,
        artists = artists,
        album = album,
        albumGroupKey = albumGroupKey,
        durationMs = durationMs,
        artworkUri = artworkUri,
        location = location,
        contentLength = contentLength,
        quality = quality,
        explicit = explicit,
        isrc = isrc,
        fileModifiedEpochMs = fileModifiedEpochMs,
        contentKey = contentKey,
    )
}
