package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Resolves YouTube / YouTube Music audio URLs with yt-dlp for in-app mpv playback.
 * Catalog search still uses the official YouTube Data API.
 */
class JvmYouTubeStreamResolver(
    private val binaryLocator: () -> Path? = ::findYtDlpBinary,
) : YouTubeStreamResolver {
    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean = binaryLocator() != null

    override suspend fun resolveAudioUrl(videoId: String): ResolvedYouTubeAudio? =
        runCatching { resolveAudioUrlOrThrow(videoId) }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            null
        }

    override suspend fun resolveAudioUrlOrThrow(videoId: String): ResolvedYouTubeAudio? = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        if (id.isEmpty() || id.any { it.isWhitespace() || it == '"' || it == '\'' }) return@withContext null
        val binary = binaryLocator() ?: return@withContext null
        val watchUrl = "https://www.youtube.com/watch?v=$id"
        val command = ProcessBuilder(
            binary.toAbsolutePath().toString(),
            "-f", "ba/bestaudio/best",
            "-j",
            "--no-playlist",
            watchUrl,
        )
        val run = runInterruptible { command.runWithin(90_000) }
        if (!run.succeeded) {
            val reason = run.failureReason("resolving")
            PlaybackTrace.log("YouTube", "yt-dlp could not resolve $id: $reason")
            error(reason)
        }
        val payload = run.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") && it.endsWith("}") }
            ?: return@withContext null
        val info = runCatching { json.decodeFromString(YtDlpInfo.serializer(), payload) }.getOrNull()
            ?: return@withContext null
        val url = info.url?.takeIf { it.startsWith("http") } ?: return@withContext null
        ResolvedYouTubeAudio(url = url, quality = ytDlpAudioQuality(info.acodec, info.abr, info.asr))
    }

    override suspend fun mix(videoId: String): List<YouTubeMixEntry> = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        require(id.isNotEmpty() && id.none { it.isWhitespace() || it == '"' || it == '\'' || it == '&' }) {
            "unsafe video id"
        }
        val binary = binaryLocator() ?: error("yt-dlp not found")
        val command = ProcessBuilder(
            binary.toAbsolutePath().toString(),
            "--flat-playlist",
            "-J",
            "--playlist-end", MIX_LENGTH.toString(),
            "https://www.youtube.com/watch?v=$id&list=RD$id",
        )
        val run = runInterruptible { command.runWithin(90_000) }
        if (!run.succeeded) error("yt-dlp failed reading the Mix: ${run.failureReason("reading the Mix")}")
        parseYtDlpMix(run.stdout)
    }

    @Serializable
    private data class YtDlpInfo(
        val url: String? = null,
        val abr: Double? = null,
        val asr: Int? = null,
        val acodec: String? = null,
    )
}

/** Why a yt-dlp run failed, in its own words when it gave any. */
internal fun ProcessRun.failureReason(action: String): String {
    val tail = extractorErrorTail(stderrTail)
    return when {
        exitCode == null -> "yt-dlp timed out $action" + if (tail.isNotEmpty()) ": $tail" else ""
        tail.isNotEmpty() -> tail
        else -> "yt-dlp exited with code $exitCode while $action"
    }
}

/** Real codec, bitrate and sample rate from yt-dlp's format fields, tiered like every other source. */
internal fun ytDlpAudioQuality(acodec: String?, abr: Double?, asr: Int?): AudioQuality {
    val bitrate = abr?.toInt()?.takeIf { it > 0 }
    val sampleRate = asr?.takeIf { it > 0 }
    val codec = acodec
        ?.takeIf { it.isNotBlank() && it != "none" && it != "NA" }
        ?.substringBefore('.')
        ?.lowercase()
    val tier = when {
        bitrate != null && bitrate >= 256 -> QualityTier.HIGH
        bitrate != null && bitrate >= 128 -> QualityTier.STANDARD
        bitrate != null -> QualityTier.LOW
        else -> QualityTier.STANDARD
    }
    return AudioQuality(
        tier = tier,
        codec = codec,
        bitrateKbps = bitrate,
        sampleRateHz = sampleRate,
        bitDepth = null,
    )
}

private const val MIX_LENGTH = 50

private val mixJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class YtDlpPlaylist(val entries: List<YtDlpEntry?> = emptyList())

@Serializable
private data class YtDlpEntry(
    val id: String? = null,
    val title: String? = null,
    val channel: String? = null,
    val uploader: String? = null,
    val duration: Double? = null,
)

/** Videos of a `yt-dlp --flat-playlist -J` playlist dump, in order. */
internal fun parseYtDlpMix(output: String): List<YouTubeMixEntry> =
    mixJson.decodeFromString(YtDlpPlaylist.serializer(), output).entries.mapNotNull { entry ->
        val id = entry?.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        YouTubeMixEntry(
            videoId = id,
            title = entry.title.orEmpty(),
            channel = entry.channel ?: entry.uploader,
            durationSeconds = entry.duration?.toLong(),
        )
    }

internal fun findYtDlpBinary(): Path? {
    System.getenv("KAINOS_YT_DLP")?.trim()?.takeIf { it.isNotEmpty() }?.let { env ->
        Paths.get(env).takeIf { it.isExecutableFile() }?.let { return it }
    }
    val home = Paths.get(System.getProperty("user.home", "."), ".local", "bin", "yt-dlp")
    if (home.isExecutableFile()) return home
    val fromCwd = Paths.get(System.getProperty("user.dir", ".")).resolve("tools").resolve("yt-dlp")
    if (fromCwd.isExecutableFile()) return fromCwd
    val which = runCatching {
        ProcessBuilder("which", "yt-dlp").readOutputWithin(5_000)?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { Paths.get(it).takeIf { path -> path.isExecutableFile() } }
    }.getOrNull()
    return which
}

private fun Path.isExecutableFile(): Boolean =
    exists() && Files.isRegularFile(this) && isExecutable()
