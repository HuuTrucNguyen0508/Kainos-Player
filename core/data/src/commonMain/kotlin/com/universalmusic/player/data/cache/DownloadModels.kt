package com.universalmusic.player.data.cache

import kotlinx.serialization.Serializable

/** In-memory / UI state for one hearted-audio download job. Not a stream URL. */
enum class DownloadPhase {
    QUEUED,
    WAITING_FOR_NETWORK,
    /** Online, but only on a metered network while "Download on Wi-Fi only" is on. */
    WAITING_FOR_WIFI,
    DOWNLOADING,
    FAILED,
    CACHED,
    /** Still hearted, but the user removed its audio; it stays off until they tap Download. */
    NOT_DOWNLOADED,
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
    /** Set while a transient failure waits out its backoff; [errorMessage] says what failed. */
    val retryAtMs: Long? = null,
)

@Serializable
data class HeartedCacheFailure(
    val ownerCanonicalId: String,
    val message: String,
    val failedAtMs: Long,
    val youtubeVideoId: String? = null,
    /** [com.universalmusic.player.platform.YouTubeFailureKind] name; null in indexes written before it. */
    val kind: String? = null,
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
    /** The extractor's words when a download failed in a way that looks like an outdated extractor. */
    val extractorIssue: String? = null,
)
