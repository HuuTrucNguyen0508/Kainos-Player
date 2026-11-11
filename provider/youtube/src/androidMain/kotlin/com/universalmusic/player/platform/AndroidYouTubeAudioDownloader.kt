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
    ): YouTubeDownloadResult = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        if (id.isEmpty() || id.any { it.isWhitespace() || it == '"' || it == '\'' }) {
            return@withContext failed(YouTubeFailureKind.PERMANENT, "Invalid YouTube video id.")
        }
        val base = fileBaseName.trim().takeIf {
            it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }
        } ?: return@withContext failed(YouTubeFailureKind.PERMANENT, "Invalid download file name.")
        val resolved = try {
            resolver.resolveAudioUrlOrThrow(id)
                ?: return@withContext failed(YouTubeFailureKind.PERMANENT, "NewPipe found no audio stream for $id.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withContext failure.toFailed()
        }
        val dir = File(destinationDirectory)
        if (!dir.mkdirs() && !dir.isDirectory) {
            return@withContext failed(YouTubeFailureKind.PERMANENT, "Cannot create the audio cache folder.")
        }
        if (dir.listFiles()?.any { it.name.startsWith("$base.") } == true) {
            return@withContext failed(YouTubeFailureKind.TRANSIENT, "A previous attempt's file is still in the way.")
        }

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
        } catch (failure: Exception) {
            val status = (connection as? HttpURLConnection)?.let { runCatching { it.responseCode }.getOrNull() }
            return@withContext if (status == 403) {
                failed(YouTubeFailureKind.OUTDATED_EXTRACTOR, "HTTP Error 403: Forbidden while downloading the audio stream.")
            } else {
                failure.toFailed()
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
            tmp.delete()
            if (!published) target.delete()
        }
        val size = target.length()
        onProgress?.invoke(size, size)
        YouTubeDownloadResult.Downloaded(
            DownloadedYouTubeAudio(
                absolutePath = target.absolutePath,
                quality = resolved.quality ?: AudioQuality(tier = QualityTier.STANDARD, codec = ext),
                sizeBytes = size,
            ),
        )
    }

    private fun failed(kind: YouTubeFailureKind, reason: String) = YouTubeDownloadResult.Failed(kind, reason)

    /** NewPipe names the problem in its exception class (AgeRestrictedContentException, ReCaptchaException…). */
    private fun Exception.toFailed(): YouTubeDownloadResult.Failed {
        val reason = listOfNotNull(this::class.simpleName, message?.takeIf { it.isNotBlank() })
            .joinToString(": ")
            .take(600)
        return failed(classifyYouTubeFailure(reason), reason)
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
