package com.universalmusic.player.data.cache

import com.universalmusic.player.data.library.isLocallyPlayable
import com.universalmusic.player.data.sync.LOCALFILE_HEART_PREFIX
import com.universalmusic.player.data.sync.isPortableLocalHeartCanonicalId
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track

/**
 * Offline / download readiness for a track. Independent of favorite (heart) state.
 * Protected Spotify DRM audio is never [CACHED] or [DOWNLOADING].
 */
enum class TrackAvailability {
    AVAILABLE_LOCALLY,
    CACHED,
    DOWNLOADING,
    WAITING_FOR_NETWORK,
    UNAVAILABLE,
    FAILED,
}

/**
 * How audio will actually play right now (honest about YouTube-match offline mirrors).
 */
enum class PlaybackSourceKind {
    LOCAL_FILE,
    HEARTED_YOUTUBE_CACHE,
    SPOTIFY_VIA_YOUTUBE_CACHE,
    SPOTIFY_STREAM,
    YOUTUBE_STREAM,
    SAMPLE,
    UNKNOWN,
}

data class TrackAvailabilityInfo(
    val status: TrackAvailability,
    val label: String,
    val playbackSource: PlaybackSourceKind,
    val playbackSourceLabel: String,
    /** YouTube video id when offline audio is a YouTube file (direct heart or Spotify match). */
    val youtubeVideoId: String? = null,
    val progress: Float? = null,
    val errorMessage: String? = null,
    val sizeBytes: Long? = null,
    val isSpotifyViaYouTubeMatch: Boolean = false,
)

fun heartedCacheSource(track: Track) = track.sources.firstOrNull {
    it.provider == ProviderId.LOCAL &&
        it.providerTrackId.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX)
}

fun libraryLocalSource(track: Track) = track.sources.firstOrNull {
    it.provider == ProviderId.LOCAL &&
        it.handle is PlaybackHandle.Url &&
        !it.providerTrackId.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX)
}

fun isSpotifyIdentity(track: Track): Boolean =
    track.canonicalId.startsWith("spotify:") ||
        track.sources.any { it.provider == ProviderId.SPOTIFY }

fun isYouTubeIdentity(track: Track): Boolean =
    track.canonicalId.startsWith("yt:") ||
        track.sources.any { it.provider == ProviderId.YOUTUBE_MUSIC }

fun resolveTrackAvailability(
    track: Track,
    download: DownloadItemState? = null,
    networkAvailable: Boolean = true,
): TrackAvailabilityInfo {
    val libraryLocal = libraryLocalSource(track)
    if (libraryLocal != null) {
        return TrackAvailabilityInfo(
            status = TrackAvailability.AVAILABLE_LOCALLY,
            label = "Available locally",
            playbackSource = PlaybackSourceKind.LOCAL_FILE,
            playbackSourceLabel = "Local file",
        )
    }

    val missingLocalIdentity = isPortableLocalHeartCanonicalId(track.canonicalId) ||
        track.canonicalId.startsWith(LOCALFILE_HEART_PREFIX)
    if (missingLocalIdentity) {
        return TrackAvailabilityInfo(
            status = TrackAvailability.UNAVAILABLE,
            label = "Local file missing",
            playbackSource = PlaybackSourceKind.UNKNOWN,
            playbackSourceLabel = "Unavailable",
            errorMessage = "The local file is not on this device.",
        )
    }

    val cacheSource = heartedCacheSource(track)
    val cachedVideoId = cacheSource?.providerTrackId
        ?.removePrefix(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX)
        ?.takeIf { it.isNotBlank() }
        ?: download?.youtubeVideoId
    val spotifyViaYt = isSpotifyIdentity(track) && (cacheSource != null || download?.isSpotifyViaYouTubeMatch == true)

    if (cacheSource != null) {
        val sourceKind = if (spotifyViaYt) {
            PlaybackSourceKind.SPOTIFY_VIA_YOUTUBE_CACHE
        } else {
            PlaybackSourceKind.HEARTED_YOUTUBE_CACHE
        }
        return TrackAvailabilityInfo(
            status = TrackAvailability.CACHED,
            label = if (spotifyViaYt) "Cached · YouTube match" else "Cached",
            playbackSource = sourceKind,
            playbackSourceLabel = playbackSourceKindLabel(sourceKind),
            youtubeVideoId = cachedVideoId,
            sizeBytes = download?.sizeBytes,
            isSpotifyViaYouTubeMatch = spotifyViaYt,
        )
    }

    when (download?.phase) {
        DownloadPhase.DOWNLOADING -> {
            return TrackAvailabilityInfo(
                status = TrackAvailability.DOWNLOADING,
                label = "Downloading",
                playbackSource = streamingKind(track),
                playbackSourceLabel = streamingLabel(track, networkAvailable),
                youtubeVideoId = download.youtubeVideoId,
                progress = download.progress,
                isSpotifyViaYouTubeMatch = download.isSpotifyViaYouTubeMatch,
            )
        }
        DownloadPhase.QUEUED, DownloadPhase.WAITING_FOR_NETWORK -> {
            return TrackAvailabilityInfo(
                status = TrackAvailability.WAITING_FOR_NETWORK,
                label = if (download.phase == DownloadPhase.QUEUED && networkAvailable) {
                    "Waiting to download"
                } else {
                    "Waiting for network"
                },
                playbackSource = streamingKind(track),
                playbackSourceLabel = streamingLabel(track, networkAvailable),
                youtubeVideoId = download.youtubeVideoId,
                isSpotifyViaYouTubeMatch = download.isSpotifyViaYouTubeMatch,
            )
        }
        DownloadPhase.FAILED -> {
            return TrackAvailabilityInfo(
                status = TrackAvailability.FAILED,
                label = "Download failed",
                playbackSource = streamingKind(track),
                playbackSourceLabel = streamingLabel(track, networkAvailable),
                youtubeVideoId = download.youtubeVideoId,
                errorMessage = download.errorMessage,
                isSpotifyViaYouTubeMatch = download.isSpotifyViaYouTubeMatch,
            )
        }
        DownloadPhase.CACHED, null -> Unit
    }

    if (!networkAvailable && track.requiresNetworkForStreaming()) {
        return TrackAvailabilityInfo(
            status = TrackAvailability.WAITING_FOR_NETWORK,
            label = "Waiting for network",
            playbackSource = streamingKind(track),
            playbackSourceLabel = streamingLabel(track, networkAvailable = false),
            isSpotifyViaYouTubeMatch = false,
        )
    }

    if (track.sources.isEmpty()) {
        return TrackAvailabilityInfo(
            status = TrackAvailability.UNAVAILABLE,
            label = "Unavailable",
            playbackSource = PlaybackSourceKind.UNKNOWN,
            playbackSourceLabel = "Unavailable",
        )
    }

    // Online, streamable, not downloading: no offline package; still report honest stream source.
    return TrackAvailabilityInfo(
        status = if (track.isLocallyPlayable()) {
            TrackAvailability.AVAILABLE_LOCALLY
        } else {
            TrackAvailability.WAITING_FOR_NETWORK
        },
        label = if (track.isLocallyPlayable()) "Available locally" else "Needs connection",
        playbackSource = streamingKind(track),
        playbackSourceLabel = streamingLabel(track, networkAvailable),
    )
}

fun playbackSourceKindLabel(kind: PlaybackSourceKind): String = when (kind) {
    PlaybackSourceKind.LOCAL_FILE -> "Local file"
    PlaybackSourceKind.HEARTED_YOUTUBE_CACHE -> "YouTube cache"
    PlaybackSourceKind.SPOTIFY_VIA_YOUTUBE_CACHE -> "YouTube match (Spotify identity)"
    PlaybackSourceKind.SPOTIFY_STREAM -> "Spotify stream"
    PlaybackSourceKind.YOUTUBE_STREAM -> "YouTube stream"
    PlaybackSourceKind.SAMPLE -> "Sample"
    PlaybackSourceKind.UNKNOWN -> "Unknown"
}

fun TrackAvailability.shortLabel(): String = when (this) {
    TrackAvailability.AVAILABLE_LOCALLY -> "Local"
    TrackAvailability.CACHED -> "Cached"
    TrackAvailability.DOWNLOADING -> "Downloading"
    TrackAvailability.WAITING_FOR_NETWORK -> "Needs network"
    TrackAvailability.UNAVAILABLE -> "Unavailable"
    TrackAvailability.FAILED -> "Failed"
}

private fun Track.requiresNetworkForStreaming(): Boolean =
    !isLocallyPlayable() && sources.any {
        it.provider == ProviderId.SPOTIFY ||
            it.provider == ProviderId.YOUTUBE_MUSIC ||
            it.provider == ProviderId.SAMPLE
    }

private fun streamingKind(track: Track): PlaybackSourceKind = when {
    isSpotifyIdentity(track) -> PlaybackSourceKind.SPOTIFY_STREAM
    isYouTubeIdentity(track) -> PlaybackSourceKind.YOUTUBE_STREAM
    track.sources.any { it.provider == ProviderId.SAMPLE } -> PlaybackSourceKind.SAMPLE
    else -> PlaybackSourceKind.UNKNOWN
}

private fun streamingLabel(track: Track, networkAvailable: Boolean): String {
    val kind = streamingKind(track)
    val base = playbackSourceKindLabel(kind)
    return if (!networkAvailable && kind != PlaybackSourceKind.UNKNOWN) "$base · offline" else base
}
