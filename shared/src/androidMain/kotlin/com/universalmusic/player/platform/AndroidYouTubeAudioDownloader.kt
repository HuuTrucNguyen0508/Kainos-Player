package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads YouTube audio via NewPipe URL resolve + HTTP to app storage.
 * Does not persist the ephemeral stream URL beyond the download.
 */
class AndroidYouTubeAudioDownloader(
    private val resolver: YouTubeStreamResolver,
) : YouTubeAudioDownloader {
    override fun isAvailable(): Boolean = resolver.isAvailable()

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
        val resolved = resolver.resolveAudioUrl(id) ?: return@withContext null
        val dir = File(destinationDirectory)
        if (!dir.mkdirs() && !dir.isDirectory) return@withContext null
        dir.listFiles()?.filter { it.name.startsWith("$base.") }?.forEach { it.delete() }

        val ext = guessExtension(resolved.url, resolved.quality)
        val target = File(dir, "$base.$ext")
        val tmp = File(dir, "$base.$ext.part")
        tmp.delete()
        val ok = runCatching {
            URI(resolved.url).toURL().openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
        if (!ok || !tmp.isFile || tmp.length() <= 0L) {
            tmp.delete()
            return@withContext null
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        DownloadedYouTubeAudio(
            absolutePath = target.absolutePath,
            quality = resolved.quality ?: AudioQuality(tier = QualityTier.HIGH, codec = ext),
            sizeBytes = target.length(),
        )
    }

    private fun guessExtension(url: String, quality: AudioQuality?): String {
        quality?.codec?.lowercase()?.let { codec ->
            when {
                "mp4" in codec || "aac" in codec || "m4a" in codec -> return "m4a"
                "webm" in codec || "opus" in codec -> return "webm"
                "mp3" in codec -> return "mp3"
            }
        }
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m4a") || path.contains("/m4a") -> "m4a"
            path.endsWith(".webm") -> "webm"
            path.endsWith(".mp3") -> "mp3"
            else -> "m4a"
        }
    }
}
