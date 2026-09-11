package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads YouTube audio to disk with yt-dlp (does not persist stream URLs).
 */
class JvmYouTubeAudioDownloader(
    private val binaryLocator: () -> Path? = ::findYtDlpBinary,
) : YouTubeAudioDownloader {
    override fun isAvailable(): Boolean = binaryLocator() != null

    override suspend fun downloadAudio(
        videoId: String,
        destinationDirectory: String,
        fileBaseName: String,
    ): DownloadedYouTubeAudio? = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        if (id.isEmpty() || id.any { it.isWhitespace() || it == '"' || it == '\'' }) return@withContext null
        val base = fileBaseName.trim().takeIf {
            it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }
        } ?: return@withContext null
        val binary = binaryLocator() ?: return@withContext null
        val dir = Paths.get(destinationDirectory)
        Files.createDirectories(dir)
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("$base.") }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
        val outputTemplate = dir.resolve("$base.%(ext)s").toAbsolutePath().normalize().toString()
        val watchUrl = "https://www.youtube.com/watch?v=$id"
        val process = ProcessBuilder(
            binary.toAbsolutePath().toString(),
            "-f", "ba/bestaudio/best",
            "-o", outputTemplate,
            "--no-playlist",
            "--no-warnings",
            "--newline",
            watchUrl,
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            return@withContext null
        }
        if (process.exitValue() != 0) return@withContext null

        val file = Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().startsWith("$base.") }
                .findFirst()
                .orElse(null)
        } ?: return@withContext null
        if (!file.exists() || Files.size(file) <= 0L) return@withContext null

        val ext = file.fileName.toString().substringAfterLast('.', missingDelimiterValue = "")
        DownloadedYouTubeAudio(
            absolutePath = file.toAbsolutePath().normalize().toString(),
            quality = AudioQuality(
                tier = QualityTier.HIGH,
                codec = ext.takeIf { it.isNotBlank() }?.lowercase(),
                bitrateKbps = null,
                sampleRateHz = null,
                bitDepth = null,
            ),
            sizeBytes = Files.size(file),
        )
    }
}
