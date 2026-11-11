package com.universalmusic.player.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.io.RandomAccessFile
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * Downloads YouTube audio to disk with yt-dlp (does not persist stream URLs).
 */
class JvmYouTubeAudioDownloader(
    private val binaryLocator: () -> Path? = ::findYtDlpBinary,
    private val timeoutMillis: Long = TimeUnit.MINUTES.toMillis(5),
) : YouTubeAudioDownloader {
    override fun isAvailable(): Boolean = binaryLocator() != null

    override suspend fun downloadAudio(
        videoId: String,
        destinationDirectory: String,
        fileBaseName: String,
        onProgress: ((bytesCopied: Long, totalBytes: Long?) -> Unit)?,
    ): YouTubeDownloadResult = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        if (id.isEmpty() || id.any { it.isWhitespace() || it == '"' || it == '\'' }) {
            return@withContext failed(YouTubeFailureKind.PERMANENT, "Invalid YouTube video id.")
        }
        val base = fileBaseName.trim().takeIf {
            it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }
        } ?: return@withContext failed(YouTubeFailureKind.PERMANENT, "Invalid download file name.")
        val binary = binaryLocator()
            ?: return@withContext failed(YouTubeFailureKind.PERMANENT, "yt-dlp is not installed.")
        val dir = Paths.get(destinationDirectory)
        Files.createDirectories(dir)
        // Never overwrite an indexed file or another attempt's partial output.
        val occupied = Files.list(dir).use { stream ->
            stream.anyMatch { it.fileName.toString().startsWith("$base.") }
        }
        if (occupied) {
            return@withContext failed(YouTubeFailureKind.TRANSIENT, "A previous attempt's file is still in the way.")
        }
        val log = Files.createTempFile("kainos-yt-download-", ".log")
        // Outside the audio directory: every "$base.*" file there counts as this attempt's output.
        val meta = Files.createTempFile("kainos-yt-meta-", ".tsv")
        var process: Process? = null
        var completed = false
        try {
        val outputTemplate = dir.resolve("$base.%(ext)s").toAbsolutePath().normalize().toString()
        val watchUrl = "https://www.youtube.com/watch?v=$id"
        process = ProcessBuilder(
            binary.toAbsolutePath().toString(),
            "-f", "ba/bestaudio/best",
            "-o", outputTemplate,
            // The format yt-dlp actually fetched, for an honest quality label.
            "--print-to-file", "after_move:%(acodec)s\t%(abr)s\t%(asr)s", meta.toAbsolutePath().toString(),
            "--no-playlist",
            "--newline",
            "--progress",
            watchUrl,
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        // Redirecting stdout avoids an open pipe blocking before the deadline starts.
        val child = process
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val finished = runInterruptible(Dispatchers.IO) {
            RandomAccessFile(log.toFile(), "r").use { output ->
                var exited = false
                while (System.nanoTime() < deadline) {
                    val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                    exited = child.waitFor(minOf(100L, remaining), TimeUnit.MILLISECONDS)
                    // Read a bounded snapshot of a regular file, never a blocking child pipe.
                    val available = (output.length() - output.filePointer).coerceIn(0L, 65_536L).toInt()
                    if (available > 0) {
                        val bytes = ByteArray(available)
                        output.readFully(bytes)
                        Regex("""(\d{1,3}(?:\.\d+)?)%""").findAll(String(bytes)).lastOrNull()
                            ?.groupValues?.get(1)?.toFloatOrNull()?.let { percent ->
                                onProgress?.invoke((percent * 10_000).toLong(), 1_000_000L)
                            }
                    }
                    if (exited) break
                }
                exited
            }
        }
        currentCoroutineContext().ensureActive()
        if (!finished) {
            val tail = extractorErrorTail(readTail(log, LOG_TAIL_BYTES))
            val minutes = TimeUnit.MILLISECONDS.toSeconds(timeoutMillis).let { if (it >= 60) "${it / 60} min" else "$it s" }
            return@withContext failed(
                YouTubeFailureKind.TRANSIENT,
                "yt-dlp did not finish within $minutes" + if (tail.isNotEmpty()) ": $tail" else ".",
            )
        }
        val exit = process.exitValue()
        if (exit != 0) {
            val reason = extractorErrorTail(readTail(log, LOG_TAIL_BYTES))
                .ifEmpty { "yt-dlp exited with code $exit." }
            return@withContext failed(classifyYouTubeFailure(reason), reason)
        }

        val file = Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().startsWith("$base.") &&
                !it.fileName.toString().endsWith(".part") && !it.fileName.toString().endsWith(".ytdl") }
                .findFirst()
                .orElse(null)
        }
        if (file == null || !file.exists() || Files.size(file) <= 0L) {
            return@withContext failed(YouTubeFailureKind.PERMANENT, "yt-dlp finished without writing an audio file.")
        }

        val size = Files.size(file)
        onProgress?.invoke(size, size)
        val ext = file.fileName.toString().substringAfterLast('.', missingDelimiterValue = "")
        completed = true
        YouTubeDownloadResult.Downloaded(
            DownloadedYouTubeAudio(
                absolutePath = file.toAbsolutePath().normalize().toString(),
                quality = parseDownloadedQuality(readTail(meta, 1_024), ext),
                sizeBytes = size,
            ),
        )
        } finally {
            process?.let { child ->
                if (child.isAlive) {
                    child.descendants().forEach { it.destroyForcibly() }
                    child.destroyForcibly()
                    child.waitFor(2, TimeUnit.SECONDS)
                }
            }
            Files.deleteIfExists(log)
            Files.deleteIfExists(meta)
            if (!completed || !currentCoroutineContext().isActive) {
                Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().startsWith("$base.") }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    private fun failed(kind: YouTubeFailureKind, reason: String) = YouTubeDownloadResult.Failed(kind, reason)
}

private const val LOG_TAIL_BYTES = 8_192

/**
 * Quality from yt-dlp's `acodec<TAB>abr<TAB>asr` line. Without it, the container only hints
 * at the codec and the tier stays STANDARD rather than claiming HIGH.
 */
internal fun parseDownloadedQuality(metaLine: String, extension: String) =
    metaLine.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.split('\t')
        ?.takeIf { it.size >= 3 }
        ?.let { (codec, abr, asr) -> ytDlpAudioQuality(codec, abr.toDoubleOrNull(), asr.toDoubleOrNull()?.toInt()) }
        ?.takeIf { it.codec != null || it.bitrateKbps != null }
        ?: ytDlpAudioQuality(
            acodec = when (extension.lowercase()) {
                "webm", "opus" -> "opus"
                "m4a", "mp4" -> "aac"
                else -> extension.takeIf { it.isNotBlank() }
            },
            abr = null,
            asr = null,
        )
