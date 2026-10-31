package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality

data class ResolvedYouTubeAudio(
    val url: String,
    val quality: AudioQuality? = null,
)

/** One video of a YouTube Mix. */
data class YouTubeMixEntry(
    val videoId: String,
    val title: String,
    val channel: String? = null,
    val durationSeconds: Long? = null,
)

interface YouTubeStreamResolver {
    fun isAvailable(): Boolean
    suspend fun resolveAudioUrl(videoId: String): ResolvedYouTubeAudio?

    /** YouTube's own Mix (`list=RD<videoId>`) for a video. Throws with the reason when it cannot be read. */
    suspend fun mix(videoId: String): List<YouTubeMixEntry>
}

object UnavailableYouTubeStreamResolver : YouTubeStreamResolver {
    override fun isAvailable(): Boolean = false
    override suspend fun resolveAudioUrl(videoId: String): ResolvedYouTubeAudio? = null
    override suspend fun mix(videoId: String): List<YouTubeMixEntry> = error("No YouTube extractor on this device")
}
