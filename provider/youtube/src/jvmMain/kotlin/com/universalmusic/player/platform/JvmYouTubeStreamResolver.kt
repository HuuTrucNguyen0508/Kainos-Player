package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun resolveAudioUrl(videoId: String): ResolvedYouTubeAudio? = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        if (id.isEmpty() || id.any { it.isWhitespace() || it == '"' || it == '\'' }) return@withContext null
        val binary = binaryLocator() ?: return@withContext null
        val watchUrl = "https://www.youtube.com/watch?v=$id"
        val process = ProcessBuilder(
            binary.toAbsolutePath().toString(),
            "-f", "ba/bestaudio/best",
            "-j",
            "--no-playlist",
            "--no-warnings",
            watchUrl,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@withContext null
        }
        if (process.exitValue() != 0) return@withContext null
        val payload = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") && it.endsWith("}") }
            ?: return@withContext null
        val info = runCatching { json.decodeFromString(YtDlpInfo.serializer(), payload) }.getOrNull()
            ?: return@withContext null
        val url = info.url?.takeIf { it.startsWith("http") } ?: return@withContext null
        ResolvedYouTubeAudio(url = url, quality = info.toAudioQuality())
    }

    override suspend fun mix(videoId: String): List<YouTubeMixEntry> = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        require(id.isNotEmpty() && id.none { it.isWhitespace() || it == '"' || it == '\'' || it == '&' }) {
            "unsafe video id"
        }
        val binary = binaryLocator() ?: error("yt-dlp not found")
        val process = ProcessBuilder(
            binary.toAbsolutePath().toString(),
            "--flat-playlist",
            "-J",
            "--playlist-end", MIX_LENGTH.toString(),
            "--no-warnings",
            "https://www.youtube.com/watch?v=$id&list=RD$id",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("yt-dlp timed out reading the Mix")
        }
        check(process.exitValue() == 0) { "yt-dlp exited ${process.exitValue()} reading the Mix" }
        parseYtDlpMix(output)
    }

    @Serializable
    private data class YtDlpInfo(
        val url: String? = null,
        val abr: Double? = null,
        val asr: Int? = null,
        val acodec: String? = null,
    )

    private fun YtDlpInfo.toAudioQuality(): AudioQuality {
        val bitrate = abr?.toInt()?.takeIf { it > 0 }
        val sampleRate = asr?.takeIf { it > 0 }
        val codec = acodec
            ?.takeIf { it.isNotBlank() && it != "none" }
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
        val process = ProcessBuilder("which", "yt-dlp").redirectErrorStream(true).start()
        val path = process.inputStream.bufferedReader().readLine()?.trim().orEmpty()
        val ok = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        if (ok && path.isNotEmpty()) Paths.get(path).takeIf { it.isExecutableFile() } else null
    }.getOrNull()
    return which
}

private fun Path.isExecutableFile(): Boolean =
    exists() && Files.isRegularFile(this) && isExecutable()
