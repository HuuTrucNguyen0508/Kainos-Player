package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality

data class ResolvedYouTubeAudio(
    val url: String,
    val quality: AudioQuality? = null,
)

interface YouTubeStreamResolver {
    fun isAvailable(): Boolean
    suspend fun resolveAudioUrl(videoId: String): ResolvedYouTubeAudio?
}

object UnavailableYouTubeStreamResolver : YouTubeStreamResolver {
    override fun isAvailable(): Boolean = false
    override suspend fun resolveAudioUrl(videoId: String): ResolvedYouTubeAudio? = null
}
