package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves YouTube / YouTube Music audio URLs with NewPipe Extractor for in-app ExoPlayer.
 * Catalog search still uses the official YouTube Data API.
 */
class AndroidYouTubeStreamResolver(
    private val ensureInitialized: () -> Boolean = ::ensureNewPipeInitialized,
    private val resolveWatchUrl: (String) -> ResolvedYouTubeAudio? = ::defaultResolveWatchUrl,
) : YouTubeStreamResolver {
    override fun isAvailable(): Boolean = ensureInitialized()

    override suspend fun resolveAudioUrl(videoId: String): ResolvedYouTubeAudio? =
        withContext(Dispatchers.IO) {
            val id = videoId.trim()
            if (id.isEmpty() || id.any { it.isWhitespace() || it == '"' || it == '\'' }) {
                return@withContext null
            }
            if (!ensureInitialized()) return@withContext null
            runCatching {
                resolveWatchUrl("https://www.youtube.com/watch?v=$id")
            }.getOrNull()
        }

    companion object {
        private val initialized = AtomicBoolean(false)
        private val initLock = Any()

        private fun ensureNewPipeInitialized(): Boolean {
            if (initialized.get()) return true
            synchronized(initLock) {
                if (initialized.get()) return true
                return runCatching {
                    NewPipe.init(AndroidNewPipeDownloader())
                    initialized.set(true)
                    true
                }.getOrDefault(false)
            }
        }

        private fun defaultResolveWatchUrl(watchUrl: String): ResolvedYouTubeAudio? {
            val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
            val stream = pickBestAudio(info.audioStreams) ?: return null
            val url = stream.content.takeIf { stream.isUrl && it.startsWith("http") }
                ?: return null
            return ResolvedYouTubeAudio(url = url, quality = stream.toAudioQuality())
        }

        private fun pickBestAudio(streams: List<AudioStream>): AudioStream? {
            fun rank(stream: AudioStream): Int {
                val deliveryBonus = when (stream.deliveryMethod) {
                    DeliveryMethod.PROGRESSIVE_HTTP -> 2_000_000
                    DeliveryMethod.HLS -> 1_000_000
                    else -> 0
                }
                val bitrate = stream.averageBitrate.takeIf { it > 0 } ?: 0
                return deliveryBonus + bitrate
            }
            return streams
                .filter { it.isUrl && it.content.isNotBlank() && it.content.startsWith("http") }
                .maxByOrNull(::rank)
        }

        private fun AudioStream.toAudioQuality(): AudioQuality {
            val bitrate = averageBitrate.takeIf { it > 0 }
            val sampleRate = itagItem?.sampleRate?.takeIf { it > 0 }
            val codec = codec
                ?.takeIf { it.isNotBlank() && it != "none" }
                ?.substringBefore('.')
                ?.lowercase()
                ?: format?.name?.lowercase()
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
}
