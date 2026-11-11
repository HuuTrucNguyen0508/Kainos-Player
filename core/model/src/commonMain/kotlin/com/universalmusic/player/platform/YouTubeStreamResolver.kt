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

    /**
     * Like [resolveAudioUrl], but a failure throws with the extractor's own reason instead of
     * returning null, so a download can say why it failed. Null still means "no audio stream".
     */
    suspend fun resolveAudioUrlOrThrow(videoId: String): ResolvedYouTubeAudio? = resolveAudioUrl(videoId)

    /** YouTube's own Mix (`list=RD<videoId>`) for a video. Throws with the reason when it cannot be read. */
    suspend fun mix(videoId: String): List<YouTubeMixEntry>
}

object UnavailableYouTubeStreamResolver : YouTubeStreamResolver {
    override fun isAvailable(): Boolean = false
    override suspend fun resolveAudioUrl(videoId: String): ResolvedYouTubeAudio? = null
    override suspend fun mix(videoId: String): List<YouTubeMixEntry> = error("No YouTube extractor on this device")
}
