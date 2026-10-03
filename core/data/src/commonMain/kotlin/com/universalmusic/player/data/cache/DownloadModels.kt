package com.universalmusic.player.data.cache

import kotlinx.serialization.Serializable

/** In-memory / UI state for one hearted-audio download job. Not a stream URL. */
enum class DownloadPhase {
    QUEUED,
    WAITING_FOR_NETWORK,
    DOWNLOADING,
    FAILED,
    CACHED,
}

data class DownloadItemState(
    val ownerCanonicalId: String,
    val title: String,
    val artistLine: String,
    val phase: DownloadPhase,
    val progress: Float? = null,
    val errorMessage: String? = null,
    val youtubeVideoId: String? = null,
    val sizeBytes: Long? = null,
    val isSpotifyViaYouTubeMatch: Boolean = false,
    val pinned: Boolean = false,
    val downloadedAtMs: Long? = null,
)

@Serializable
data class HeartedCacheFailure(
    val ownerCanonicalId: String,
    val message: String,
    val failedAtMs: Long,
    val youtubeVideoId: String? = null,
)

@Serializable
data class CacheEvictionRecord(
    val ownerCanonicalId: String,
    val youtubeVideoId: String,
    val sizeBytes: Long,
    val reason: String,
    val evictedAtMs: Long,
)

/**
 * Snapshot for the Downloads view: active jobs, cached entries, storage, recent evictions.
 */
data class DownloadsSnapshot(
    val items: List<DownloadItemState>,
    val bytesUsed: Long,
    val maxBytes: Long,
    val entryCount: Int,
    val recentEvictions: List<CacheEvictionRecord>,
)
