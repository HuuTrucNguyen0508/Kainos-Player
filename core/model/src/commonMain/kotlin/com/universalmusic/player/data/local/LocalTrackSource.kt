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
    suspend fun scanResult(previous: List<LocalTrack>): LocalScanResult = LocalScanResult(scan())
}

/** Platform scans can reuse unchanged files and retain rows from unavailable configured roots. */
interface IncrementalLocalTrackSource : LocalTrackSource {
    suspend fun scanReusing(previous: List<LocalTrack>): LocalScanResult
    override suspend fun scanResult(previous: List<LocalTrack>): LocalScanResult = scanReusing(previous)
}

/** Tracks include cached rows only from unavailable, still-configured roots. Root names are paths or tree URIs. */
data class LocalScanResult(
    val tracks: List<LocalTrack>,
    val reused: Int = 0,
    val probed: Int = tracks.size,
    val unavailableRoots: List<String> = emptyList(),
    val readableRootCount: Int = 1,
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
    val contentKey: String? = null,
    /** Album artist tag; groups an album whose tracks credit different artists. */
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    /**
     * [LOCAL_TAGS_VERSION] once the file's own tags were read (even if it had none); 0 while
     * title, artists and album are still filename and folder guesses.
     */
    val tagsVersion: Int = 0,
    val previousAlbumIds: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Local track id must not be blank" }
        require(title.isNotBlank()) { "Local track title must not be blank" }
        require(location.isNotBlank()) { "Local track location must not be blank" }
    }
}

/** Bump to make every platform re-read tags from files it already scanned. */
const val LOCAL_TAGS_VERSION = 1

val LocalTrack.tagsRead: Boolean
    get() = tagsVersion >= LOCAL_TAGS_VERSION

/** Tags read from inside an audio file. Blank strings and non-positive numbers are dropped by the readers. */
data class LocalTags(
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
)

/**
 * The file's own tags win; anything a file leaves out keeps the filename or folder guess. Track
 * artists fall back to the album artist. [LocalTrack.id] and [LocalTrack.location] never change,
 * so the `local:` id and the portable sync identities (basename, content key) stay the same.
 */
fun LocalTrack.withTags(tags: LocalTags): LocalTrack {
    val albumArtists = splitTagArtists(tags.albumArtist)
    return copy(
        title = tags.title?.takeIf(String::isNotBlank) ?: title,
        artists = tags.artists.ifEmpty { albumArtists }.ifEmpty { artists },
        album = tags.album?.takeIf(String::isNotBlank) ?: album,
        albumArtist = tags.albumArtist?.takeIf(String::isNotBlank),
        trackNumber = tags.trackNumber,
        discNumber = tags.discNumber,
        year = tags.year,
        genre = tags.genre?.takeIf(String::isNotBlank),
        durationMs = tags.durationMs?.takeIf { it > 0 } ?: durationMs,
        tagsVersion = LOCAL_TAGS_VERSION,
    ).withAlbumIdentityFrom(this)
}

/** Keep tags already read for the same unchanged file across a rescan that only saw its name. */
fun LocalTrack.withTagsCarriedFrom(previous: LocalTrack?): LocalTrack {
    if (tagsRead || previous == null || !previous.tagsRead) return this
    val sameFile = previous.id == id && previous.location == location &&
        contentLength != null && fileModifiedEpochMs != null &&
        previous.contentLength == contentLength && previous.fileModifiedEpochMs == fileModifiedEpochMs
    if (!sameFile) return this
    return copy(
        title = previous.title,
        artists = previous.artists,
        album = previous.album,
        albumArtist = previous.albumArtist,
        trackNumber = previous.trackNumber,
        discNumber = previous.discNumber,
        year = previous.year,
        genre = previous.genre,
        durationMs = previous.durationMs ?: durationMs,
        tagsVersion = previous.tagsVersion,
    )
}

/** Multi-artist tags are written as `A;B` (Vorbis comments, Qobuz, ffprobe's ID3 join). */
fun splitTagArtists(raw: String?): List<String> = raw
    ?.split(';', '\u0000')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.distinct()
    .orEmpty()

/** The album's artists for grouping: album artist when tagged, else the track artists. */
val LocalTrack.albumArtists: List<String>
    get() = splitTagArtists(albumArtist).ifEmpty { artists }

/** Retain aliases so persisted Home pins can follow tag corrections after a restart. */
fun LocalTrack.withAlbumIdentityFrom(previous: LocalTrack?): LocalTrack {
    if (previous == null) return this
    val oldId = previous.album?.let { localAlbumCanonicalId(it, previous.albumArtists, previous.albumGroupKey) }
    val currentId = album?.let { localAlbumCanonicalId(it, albumArtists, albumGroupKey) }
    return copy(previousAlbumIds = (previousAlbumIds + previous.previousAlbumIds + listOfNotNull(oldId)).distinct().filter { it != currentId })
}
