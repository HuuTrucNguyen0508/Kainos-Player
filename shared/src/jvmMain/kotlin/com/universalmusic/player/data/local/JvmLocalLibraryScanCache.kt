package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityConfidence
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.platform.homeLanConfigDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Disk snapshot of the last desktop library scan so the next launch can show tracks
 * immediately and skip ffprobe for files whose path, size, and mtime are unchanged.
 */
internal class JvmLocalLibraryScanCache(
    private val file: Path = homeLanConfigDir().resolve("local-library-cache.json"),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : LocalLibraryScanCache {
    override suspend fun read(configKey: String): List<LocalTrack>? = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext null
        runCatching {
            val snapshot = json.decodeFromString<JvmLibraryCacheSnapshot>(Files.readString(file))
            if (snapshot.configKey != configKey) return@withContext null
            snapshot.tracks.mapNotNull { it.toLocalTrackOrNull() }
        }.getOrNull()
    }

    override suspend fun write(configKey: String, tracks: List<LocalTrack>) {
        withContext(Dispatchers.IO) {
            val snapshot = JvmLibraryCacheSnapshot(
                configKey = configKey,
                tracks = tracks.map { it.toCached() },
            )
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(snapshot))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}

@Serializable
private data class JvmLibraryCacheSnapshot(
    val configKey: String,
    val tracks: List<JvmCachedLocalTrack> = emptyList(),
)

@Serializable
private data class JvmCachedLocalTrack(
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
)

private fun LocalTrack.toCached() = JvmCachedLocalTrack(
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
)

private fun JvmCachedLocalTrack.toLocalTrackOrNull(): LocalTrack? {
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
    )
}
