package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import java.io.File
import java.net.URI
import java.net.HttpURLConnection
import com.universalmusic.player.data.cache.copyHeartedAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
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
        onProgress: ((bytesCopied: Long, totalBytes: Long?) -> Unit)?,
    ): DownloadedYouTubeAudio? = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        if (id.isEmpty() || id.any { it.isWhitespace() || it == '"' || it == '\'' }) return@withContext null
        val base = fileBaseName.trim().takeIf {
            it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }
        } ?: return@withContext null
        val resolved = resolver.resolveAudioUrl(id) ?: return@withContext null
        val dir = File(destinationDirectory)
        if (!dir.mkdirs() && !dir.isDirectory) return@withContext null
        if (dir.listFiles()?.any { it.name.startsWith("$base.") } == true) return@withContext null

        val ext = guessExtension(resolved.url, resolved.quality)
        val target = File(dir, "$base.$ext")
        val tmp = File(dir, "$base.$ext.part")
        val connection = URI(resolved.url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        var published = false
        try {
            val context = currentCoroutineContext()
            runInterruptible(Dispatchers.IO) {
                tmp.outputStream().use { output ->
                    copyHeartedAudio(connection, output, { context.ensureActive() }, onProgress)
                }
            }
            context.ensureActive()
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = false)
            published = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext null
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
            tmp.delete()
            if (!published) target.delete()
        }
        val size = target.length()
        onProgress?.invoke(size, size)
        DownloadedYouTubeAudio(
            absolutePath = target.absolutePath,
            quality = resolved.quality ?: AudioQuality(tier = QualityTier.HIGH, codec = ext),
            sizeBytes = size,
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
