package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality

data class DownloadedYouTubeAudio(
    /** Absolute filesystem path to the downloaded audio file. */
    val absolutePath: String,
    val quality: AudioQuality? = null,
    val sizeBytes: Long,
)

/** How a YouTube download failed, which decides whether the queue retries it. */
enum class YouTubeFailureKind {
    /** No network, a timeout or a server error: worth trying again later. */
    TRANSIENT,

    /** This video cannot be had: unavailable, private, removed, age-gated, region-blocked. */
    PERMANENT,

    /** The extractor itself looks out of date (403 on formats, bot checks, signature errors). */
    OUTDATED_EXTRACTOR,
}

sealed interface YouTubeDownloadResult {
    data class Downloaded(val audio: DownloadedYouTubeAudio) : YouTubeDownloadResult

    /** [reason] is the extractor's own words (a bounded stderr tail or exception message). */
    data class Failed(val kind: YouTubeFailureKind, val reason: String) : YouTubeDownloadResult
}

/**
 * Downloads YouTube audio to a local file for hearted-track caching.
 * Distinct from [YouTubeStreamResolver], which only resolves ephemeral stream URLs.
 */
interface YouTubeAudioDownloader {
    fun isAvailable(): Boolean

    /**
     * Download best audio for [videoId] into [destinationDirectory] using [fileBaseName]
     * as the filename stem (extension chosen by the downloader).
     *
     * [onProgress] receives bytes copied and optional total size when known.
     */
    suspend fun downloadAudio(
        videoId: String,
        destinationDirectory: String,
        fileBaseName: String,
        onProgress: ((bytesCopied: Long, totalBytes: Long?) -> Unit)? = null,
    ): YouTubeDownloadResult
}

object UnavailableYouTubeAudioDownloader : YouTubeAudioDownloader {
    override fun isAvailable(): Boolean = false
    override suspend fun downloadAudio(
        videoId: String,
        destinationDirectory: String,
        fileBaseName: String,
        onProgress: ((bytesCopied: Long, totalBytes: Long?) -> Unit)?,
    ): YouTubeDownloadResult =
        YouTubeDownloadResult.Failed(YouTubeFailureKind.PERMANENT, "No YouTube downloader on this device.")
}

private val PERMANENT_MARKERS = listOf(
    "confirm your age", "age-restricted", "age restricted", "agerestricted", "inappropriate for some users",
    "private video", "privatecontent", "video unavailable", "this video is unavailable",
    "this video is not available",
    "contentnotavailable", "has been removed", "removed by the uploader", "has been terminated",
    "members-only", "members only", "join this channel", "paidcontent", "not available in your country",
    "in your country", "georestricted", "geographicrestriction", "premiumcontent", "accountterminated",
    "copyright", "premieres in", "live event will begin",
    "unsupported url", "incomplete youtube id", "no suitable",
)

private val OUTDATED_MARKERS = listOf(
    "sign in to confirm", "not a bot", "signinconfirmnotbot", "recaptcha", "http error 403", "403: forbidden", "403 forbidden",
    "nsig", "signature", "deobfuscat", "throttling parameter", "unable to extract",
    "please report this issue", "requested format is not available", "parsingexception",
    "could not get stream", "could not parse", "extractionexception",
)

private val TRANSIENT_MARKERS = listOf(
    "timed out", "timeout", "did not finish", "temporary failure", "name resolution", "network is unreachable",
    "no route to host", "connection", "unknownhost", "errno", "reset by peer", "remote end closed",
    "http error 429", "too many requests", "http error 5", "internal server error", "bad gateway",
    "service unavailable", "gateway timeout",
    "quota", "ioexception", "sslexception", "eof occurred",
)

/**
 * Sorts an extractor's error text. Specific video problems win over extractor health, which
 * wins over network trouble; anything unrecognised is treated as this video's problem so one
 * broken entry can never hold up the rest of the queue.
 */
fun classifyYouTubeFailure(detail: String): YouTubeFailureKind {
    val text = detail.lowercase()
    return when {
        PERMANENT_MARKERS.any { it in text } -> YouTubeFailureKind.PERMANENT
        OUTDATED_MARKERS.any { it in text } -> YouTubeFailureKind.OUTDATED_EXTRACTOR
        TRANSIENT_MARKERS.any { it in text } -> YouTubeFailureKind.TRANSIENT
        else -> YouTubeFailureKind.PERMANENT
    }
}

/** The most telling part of an extractor's output: its ERROR lines, else its last lines. Bounded. */
fun extractorErrorTail(output: String, maxChars: Int = 600): String {
    val lines = output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("[download]") }
        .toList()
    val errors = lines.filter { it.startsWith("ERROR:") }
    val chosen = errors.ifEmpty { lines.takeLast(4) }
    return chosen.joinToString("\n").takeLast(maxChars)
}
