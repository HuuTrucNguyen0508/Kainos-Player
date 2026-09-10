package com.universalmusic.player.data.local

import android.content.Context
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the last successful Android local library scan so launch can show tracks
 * immediately while a background rescan runs.
 */
internal class AndroidLocalLibraryScanCache(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : LocalLibraryScanCache {
    private val file = File(context.applicationContext.filesDir, "local-library-cache.json")

    override suspend fun read(configKey: String): List<LocalTrack>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            val snapshot = json.decodeFromString<LocalLibraryCacheSnapshot>(file.readText())
            if (snapshot.configKey != configKey) return@withContext null
            snapshot.tracks.mapNotNull { it.toLocalTrackOrNull() }
        }.getOrNull()
    }

    override suspend fun write(configKey: String, tracks: List<LocalTrack>) = withContext(Dispatchers.IO) {
        val snapshot = LocalLibraryCacheSnapshot(
            configKey = configKey,
            tracks = tracks.map { it.toCached() },
        )
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(snapshot))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
    }
}

@Serializable
private data class LocalLibraryCacheSnapshot(
    val configKey: String,
    val tracks: List<CachedLocalTrack> = emptyList(),
)

@Serializable
private data class CachedLocalTrack(
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
    val explicit: Boolean = false,
    val isrc: String? = null,
)

private fun LocalTrack.toCached() = CachedLocalTrack(
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
    explicit = explicit,
    isrc = isrc,
)

private fun CachedLocalTrack.toLocalTrackOrNull(): LocalTrack? {
    if (id.isBlank() || title.isBlank() || location.isBlank()) return null
    val quality = qualityTier?.let { tierName ->
        val tier = runCatching { QualityTier.valueOf(tierName) }.getOrNull() ?: return@let null
        AudioQuality(
            tier = tier,
            codec = qualityCodec,
            bitrateKbps = qualityBitrateKbps,
            sampleRateHz = qualitySampleRateHz,
            bitDepth = qualityBitDepth,
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
    )
}
