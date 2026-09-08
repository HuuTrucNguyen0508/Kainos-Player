package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.refineQualityTier
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private val probeJson = Json { ignoreUnknownKeys = true }

private data class ProbeCacheKey(
    val absolutePath: String,
    val size: Long,
    val lastModified: Long,
)

private data class ProbeCacheEntry(
    val quality: AudioQuality,
    val title: String?,
    val artists: List<String>?,
    val album: String?,
    val durationMs: Long?,
    val hasAttachedPicture: Boolean,
)

private val probeCache = ConcurrentHashMap<ProbeCacheKey, ProbeCacheEntry>()

internal data class LocalAudioProbeResult(
    val quality: AudioQuality,
    val title: String? = null,
    val artists: List<String>? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val embeddedArtworkPath: Path? = null,
)

/** Probe tags + stream quality; cache when path size/mtime are unchanged. */
internal fun probeLocalAudioMetadata(path: Path, fallback: AudioQuality): LocalAudioProbeResult {
    val absolute = path.toAbsolutePath().normalize()
    val size = runCatching { Files.size(absolute) }.getOrNull() ?: return LocalAudioProbeResult(fallback)
    val mtime = runCatching { Files.getLastModifiedTime(absolute).toMillis() }.getOrNull()
        ?: return LocalAudioProbeResult(fallback)
    val key = ProbeCacheKey(absolute.toString(), size, mtime)
    probeCache[key]?.let { cached ->
        return LocalAudioProbeResult(
            quality = cached.quality,
            title = cached.title,
            artists = cached.artists,
            album = cached.album,
            durationMs = cached.durationMs,
            embeddedArtworkPath = if (cached.hasAttachedPicture) {
                extractEmbeddedArtwork(absolute, size, mtime)
            } else {
                null
            },
        )
    }

    val output = runFfprobe(absolute) ?: return LocalAudioProbeResult(fallback)
    val parsed = parseFfprobeMetadata(output, fallback)
    probeCache[key] = ProbeCacheEntry(
        quality = parsed.quality,
        title = parsed.title,
        artists = parsed.artists,
        album = parsed.album,
        durationMs = parsed.durationMs,
        hasAttachedPicture = parsed.hasAttachedPicture,
    )
    return LocalAudioProbeResult(
        quality = parsed.quality,
        title = parsed.title,
        artists = parsed.artists,
        album = parsed.album,
        durationMs = parsed.durationMs,
        embeddedArtworkPath = if (parsed.hasAttachedPicture) {
            extractEmbeddedArtwork(absolute, size, mtime)
        } else {
            null
        },
    )
}

internal fun probeLocalAudioQuality(path: Path, fallback: AudioQuality): AudioQuality =
    probeLocalAudioMetadata(path, fallback).quality

internal fun parseFfprobeQuality(rawJson: String, fallback: AudioQuality): AudioQuality =
    parseFfprobeMetadata(rawJson, fallback).quality

internal data class ParsedFfprobeMetadata(
    val quality: AudioQuality,
    val title: String? = null,
    val artists: List<String>? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val hasAttachedPicture: Boolean = false,
)

internal fun parseFfprobeMetadata(rawJson: String, fallback: AudioQuality): ParsedFfprobeMetadata {
    val root = runCatching {
        probeJson.decodeFromString(FfprobeRoot.serializer(), rawJson)
    }.getOrNull() ?: return ParsedFfprobeMetadata(fallback)

    val stream = root.streams.firstOrNull { it.codecType != "video" } ?: root.streams.firstOrNull()
    val sampleRateHz = stream?.sampleRate.asPositiveInt()
    val bitDepth = sequenceOf(stream?.bitsPerRawSample, stream?.bitsPerSample)
        .mapNotNull { it.asPositiveInt() }
        .firstOrNull()
    val bitrateKbps = stream?.bitRate.asPositiveLong()
        ?.div(1000)
        ?.toInt()
    val codec = stream?.codecName?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.codec
    val tags = root.format?.tags
    val title = tags?.title?.trim()?.takeIf { it.isNotEmpty() }
    val album = tags?.album?.trim()?.takeIf { it.isNotEmpty() }
    val artistRaw = tags?.albumArtist?.takeIf { it.isNotBlank() } ?: tags?.artist
    val artists = artistRaw
        ?.split(';')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
    val durationMs = root.format?.duration
        ?.toDoubleOrNull()
        ?.times(1000.0)
        ?.toLong()
        ?.takeIf { it > 0 }
    val hasPicture = root.streams.any { streamItem ->
        streamItem.disposition?.attachedPic == 1 ||
            (streamItem.codecType == "video" && streamItem.codecName != null)
    }

    return ParsedFfprobeMetadata(
        quality = AudioQuality(
            tier = refineQualityTier(fallback.tier, sampleRateHz, bitDepth),
            codec = codec,
            bitrateKbps = bitrateKbps ?: fallback.bitrateKbps,
            sampleRateHz = sampleRateHz ?: fallback.sampleRateHz,
            bitDepth = bitDepth ?: fallback.bitDepth,
        ),
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
        hasAttachedPicture = hasPicture,
    )
}

/**
 * Artwork precedence (LocalArtworkPolicy): embedded extract → sidecar cover/folder.* → none.
 * Returns a file: URI suitable for Coil / MPRIS / MediaSession.
 */
internal fun resolveLocalArtworkUri(audioPath: Path, embeddedArtworkPath: Path?): String? {
    embeddedArtworkPath
        ?.takeIf { Files.isRegularFile(it) && Files.size(it) in 1..LocalArtworkPolicy.MAX_ARTWORK_BYTES }
        ?.let { return it.toUri().toASCIIString() }

    val parent = audioPath.parent ?: return null
    val sidecar = findSidecarArtwork(parent) ?: return null
    val size = runCatching { Files.size(sidecar) }.getOrNull() ?: return null
    if (size !in 1..LocalArtworkPolicy.MAX_ARTWORK_BYTES) return null
    return sidecar.toUri().toASCIIString()
}

internal fun findSidecarArtwork(directory: Path): Path? {
    if (!Files.isDirectory(directory)) return null
    val entries = runCatching {
        Files.list(directory).use { stream -> stream.toList() }
    }.getOrNull().orEmpty()
    val byLowerName = entries.associateBy { it.fileName.toString().lowercase() }
    for (name in LocalArtworkPolicy.SIDECAR_NAMES) {
        byLowerName[name]?.takeIf { Files.isRegularFile(it) }?.let { return it }
    }
    return null
}

internal fun clearLocalAudioProbeCacheForTests() {
    probeCache.clear()
}

private fun JsonElement?.asPositiveInt(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.intOrNull?.takeIf { it > 0 }
        ?: primitive.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }
}

private fun JsonElement?.asPositiveLong(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull?.takeIf { it > 0 }
        ?: primitive.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
}

private fun runFfprobe(path: Path): String? {
    val process = runCatching {
        ProcessBuilder(
            "ffprobe",
            "-v", "error",
            "-show_entries",
            "stream=codec_name,codec_type,sample_rate,bits_per_raw_sample,bits_per_sample,bit_rate:" +
                "stream_disposition=attached_pic:" +
                "format=duration:format_tags=title,artist,album,album_artist",
            "-of", "json",
            path.toAbsolutePath().normalize().toString(),
        ).redirectErrorStream(true).start()
    }.getOrNull() ?: return null

    val finished = runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)
    if (!finished) {
        process.destroyForcibly()
        return null
    }
    if (process.exitValue() != 0) return null
    return runCatching { process.inputStream.bufferedReader().readText() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

private fun artCacheDir(): Path {
    val home = System.getProperty("user.home", ".")
    val dir = Paths.get(home, ".universal-music-player", "art-cache")
    Files.createDirectories(dir)
    return dir
}

private fun extractEmbeddedArtwork(audioPath: Path, size: Long, mtime: Long): Path? {
    val keyBytes = "$audioPath|$size|$mtime".toByteArray(StandardCharsets.UTF_8)
    val id = UUID.nameUUIDFromBytes(keyBytes).toString()
    val out = artCacheDir().resolve("$id.jpg")
    if (Files.isRegularFile(out)) {
        val cachedSize = runCatching { Files.size(out) }.getOrNull() ?: 0
        if (cachedSize in 1..LocalArtworkPolicy.MAX_ARTWORK_BYTES) return out
        runCatching { Files.deleteIfExists(out) }
    }
    val process = runCatching {
        ProcessBuilder(
            "ffmpeg",
            "-y",
            "-i", audioPath.toAbsolutePath().normalize().toString(),
            "-an",
            "-vcodec", "mjpeg",
            "-frames:v", "1",
            "-f", "image2",
            out.toString(),
        ).redirectErrorStream(true).start()
    }.getOrNull() ?: return null
    val finished = runCatching { process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
    if (!finished) {
        process.destroyForcibly()
        runCatching { Files.deleteIfExists(out) }
        return null
    }
    if (process.exitValue() != 0 || !Files.isRegularFile(out)) {
        runCatching { Files.deleteIfExists(out) }
        return null
    }
    val outSize = runCatching { Files.size(out) }.getOrNull() ?: 0
    if (outSize !in 1..LocalArtworkPolicy.MAX_ARTWORK_BYTES) {
        runCatching { Files.deleteIfExists(out) }
        return null
    }
    return out
}

@Serializable
private data class FfprobeRoot(
    val streams: List<FfprobeStream> = emptyList(),
    val format: FfprobeFormat? = null,
)

@Serializable
private data class FfprobeFormat(
    val duration: String? = null,
    val tags: FfprobeTags? = null,
)

@Serializable
private data class FfprobeTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
)

@Serializable
private data class FfprobeStream(
    @SerialName("codec_name") val codecName: String? = null,
    @SerialName("codec_type") val codecType: String? = null,
    @SerialName("sample_rate") val sampleRate: JsonElement? = null,
    @SerialName("bits_per_raw_sample") val bitsPerRawSample: JsonElement? = null,
    @SerialName("bits_per_sample") val bitsPerSample: JsonElement? = null,
    @SerialName("bit_rate") val bitRate: JsonElement? = null,
    val disposition: FfprobeDisposition? = null,
)

@Serializable
private data class FfprobeDisposition(
    @SerialName("attached_pic") val attachedPic: Int? = null,
)
