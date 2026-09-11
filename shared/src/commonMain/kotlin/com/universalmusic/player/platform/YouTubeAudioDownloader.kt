package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality

data class DownloadedYouTubeAudio(
    /** Absolute filesystem path to the downloaded audio file. */
    val absolutePath: String,
    val quality: AudioQuality? = null,
    val sizeBytes: Long,
)

/**
 * Downloads YouTube audio to a local file for hearted-track caching.
 * Distinct from [YouTubeStreamResolver], which only resolves ephemeral stream URLs.
 */
interface YouTubeAudioDownloader {
    fun isAvailable(): Boolean

    /**
     * Download best audio for [videoId] into [destinationDirectory] using [fileBaseName]
     * as the filename stem (extension chosen by the downloader).
     */
    suspend fun downloadAudio(
        videoId: String,
        destinationDirectory: String,
        fileBaseName: String,
    ): DownloadedYouTubeAudio?
}

object UnavailableYouTubeAudioDownloader : YouTubeAudioDownloader {
    override fun isAvailable(): Boolean = false
    override suspend fun downloadAudio(
        videoId: String,
        destinationDirectory: String,
        fileBaseName: String,
    ): DownloadedYouTubeAudio? = null
}
