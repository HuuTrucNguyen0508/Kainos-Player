package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
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
    ): DownloadedYouTubeAudio? = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        if (id.isEmpty() || id.any { it.isWhitespace() || it == '"' || it == '\'' }) return@withContext null
        val base = fileBaseName.trim().takeIf {
            it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }
        } ?: return@withContext null
        val binary = binaryLocator() ?: return@withContext null
        val dir = Paths.get(destinationDirectory)
        Files.createDirectories(dir)
        // Never overwrite an indexed file or another attempt's partial output.
        val occupied = Files.list(dir).use { stream ->
            stream.anyMatch { it.fileName.toString().startsWith("$base.") }
        }
        if (occupied) return@withContext null
        val log = Files.createTempFile("kainos-yt-download-", ".log")
        var process: Process? = null
        var completed = false
        try {
        val outputTemplate = dir.resolve("$base.%(ext)s").toAbsolutePath().normalize().toString()
        val watchUrl = "https://www.youtube.com/watch?v=$id"
        process = ProcessBuilder(
            binary.toAbsolutePath().toString(),
            "-f", "ba/bestaudio/best",
            "-o", outputTemplate,
            "--no-playlist",
            "--no-warnings",
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
        if (!finished) return@withContext null
        if (process.exitValue() != 0) return@withContext null

        val file = Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().startsWith("$base.") &&
                !it.fileName.toString().endsWith(".part") && !it.fileName.toString().endsWith(".ytdl") }
                .findFirst()
                .orElse(null)
        } ?: return@withContext null
        if (!file.exists() || Files.size(file) <= 0L) return@withContext null

        val size = Files.size(file)
        onProgress?.invoke(size, size)
        val ext = file.fileName.toString().substringAfterLast('.', missingDelimiterValue = "")
        completed = true
        DownloadedYouTubeAudio(
            absolutePath = file.toAbsolutePath().normalize().toString(),
            quality = AudioQuality(
                tier = QualityTier.HIGH,
                codec = ext.takeIf { it.isNotBlank() }?.lowercase(),
                bitrateKbps = null,
                sampleRateHz = null,
                bitDepth = null,
            ),
            sizeBytes = size,
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
            if (!completed || !currentCoroutineContext().isActive) {
                Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().startsWith("$base.") }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }
}
