package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.AudioQuality

/**
 * The platform boundary for local media discovery.
 *
 * Android and desktop implementations own permissions, filesystem or media-store access,
 * and metadata extraction. The common provider only consumes a snapshot from that work.
 */
fun interface LocalTrackSource {
    suspend fun scan(): List<LocalTrack>
}

/** Desktop scans can reuse a previous snapshot when path, size, and mtime are unchanged. */
interface IncrementalLocalTrackSource : LocalTrackSource {
    suspend fun scanReusing(previous: List<LocalTrack>): IncrementalScan
}

data class IncrementalScan(
    val tracks: List<LocalTrack>,
    val reused: Int,
    val probed: Int,
)

data class LocalTrack(
    /** A stable platform identifier, such as a MediaStore id or normalized file path. */
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    /**
     * Stable grouping key for album identity (e.g. parent directory URI/path), not display title.
     * Same title + different keys → different albums.
     */
    val albumGroupKey: String = "",
    val durationMs: Long? = null,
    val artworkUri: String? = null,
    /** A path or content URI understood by the platform playback engine. */
    val location: String,
    /** Optional byte length for cross-source dedupe (SAF vs MediaStore). */
    val contentLength: Long? = null,
    val quality: AudioQuality? = null,
    val explicit: Boolean = false,
    val isrc: String? = null,
    /** File mtime in epoch millis, when the platform recorded it. Used to skip unchanged probes. */
    val fileModifiedEpochMs: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Local track id must not be blank" }
        require(title.isNotBlank()) { "Local track title must not be blank" }
        require(location.isNotBlank()) { "Local track location must not be blank" }
    }
}
