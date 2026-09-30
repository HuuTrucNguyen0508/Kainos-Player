package com.universalmusic.player.data.playlist

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.library.withPersistedSourcesOnly
import com.universalmusic.player.data.sync.LOCALFILE_HEART_PREFIX
import com.universalmusic.player.data.sync.basenameFromLocalLocation
import com.universalmusic.player.data.sync.isLocalFileHeartCanonicalId
import com.universalmusic.player.data.sync.localFileHeartId
import com.universalmusic.player.data.sync.normalizedLocalFileHeartBasename
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.secureRandomBytes
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

const val KAINOS_PLAYLISTS_FORMAT_VERSION = 1
const val KAINOS_PLAYLIST_ID_PREFIX = "kainos:playlist:"

/**
 * Versioned on-disk Kainos playlists. Entries store stable source identity + display
 * metadata via [PersistedTrack] — never resolved streaming URLs.
 */
@Serializable
data class KainosPlaylistsSnapshot(
    val version: Int = KAINOS_PLAYLISTS_FORMAT_VERSION,
    val playlists: List<PersistedKainosPlaylist> = emptyList(),
    /**
     * Soft-deletes for Home sync. A tombstone with revision >= playlist revision
     * wins over a live playlist with the same id.
     */
    val tombstones: List<PlaylistTombstone> = emptyList(),
)

@Serializable
data class PersistedKainosPlaylist(
    val id: String,
    val title: String,
    val entries: List<PersistedPlaylistEntry> = emptyList(),
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
    /** Monotonic revision for LAN merge (usually wall-clock ms). */
    val revision: Long = 0,
    val deviceId: String = "local",
)

@Serializable
data class PersistedPlaylistEntry(
    /** Stable entry identity (reorder / remove without relying on track id alone). */
    val entryId: String,
    val track: PersistedTrack,
)

@Serializable
data class PlaylistTombstone(
    val playlistId: String,
    val revision: Long,
    val deviceId: String,
    val deletedAtMs: Long,
)

interface KainosPlaylistStore {
    suspend fun read(): KainosPlaylistsSnapshot
    suspend fun write(snapshot: KainosPlaylistsSnapshot)
}

fun isKainosPlaylistId(id: String): Boolean = id.startsWith(KAINOS_PLAYLIST_ID_PREFIX)

@OptIn(ExperimentalEncodingApi::class)
fun newKainosPlaylistId(): String =
    KAINOS_PLAYLIST_ID_PREFIX + Base64.UrlSafe.encode(secureRandomBytes(12)).trimEnd('=')

@OptIn(ExperimentalEncodingApi::class)
fun newPlaylistEntryId(): String =
    "entry:" + Base64.UrlSafe.encode(secureRandomBytes(10)).trimEnd('=')

fun KainosPlaylistsSnapshot.migrated(): KainosPlaylistsSnapshot {
    val playlists = playlists
        .filter { isKainosPlaylistId(it.id) && it.title.isNotBlank() }
        .map { playlist ->
            playlist.copy(
                title = playlist.title.trim(),
                entries = playlist.entries.filter { it.entryId.isNotBlank() && it.track.canonicalId.isNotBlank() },
                revision = playlist.revision.coerceAtLeast(0L),
                createdAtMs = playlist.createdAtMs.coerceAtLeast(0L),
                updatedAtMs = playlist.updatedAtMs.coerceAtLeast(playlist.createdAtMs),
            )
        }
        .distinctBy { it.id }
    val tombs = tombstones
        .filter { isKainosPlaylistId(it.playlistId) }
        .groupBy { it.playlistId }
        .map { (_, group) ->
            group.maxWith(compareBy<PlaylistTombstone> { it.revision }.thenBy { it.deviceId })
        }
    // Drop live playlists covered by a winning tombstone.
    val tombById = tombs.associateBy { it.playlistId }
    val live = playlists.filter { playlist ->
        val tomb = tombById[playlist.id] ?: return@filter true
        !tomb.beatsPlaylist(playlist)
    }
    return copy(
        version = KAINOS_PLAYLISTS_FORMAT_VERSION,
        playlists = live,
        tombstones = tombs.sortedWith(compareBy({ it.playlistId }, { it.revision })),
    )
}

fun PlaylistTombstone.beatsPlaylist(playlist: PersistedKainosPlaylist): Boolean =
    revision > playlist.revision ||
        (revision == playlist.revision && deviceId >= playlist.deviceId)

fun PersistedKainosPlaylist.beats(other: PersistedKainosPlaylist): Boolean =
    revision > other.revision ||
        (revision == other.revision && deviceId > other.deviceId)

fun PersistedKainosPlaylist.toDomainPlaylist(): Playlist {
    val tracks = entries.map { it.track.toDomain() }
    return Playlist(
        canonicalId = id,
        title = title,
        description = kainosPlaylistAvailabilitySummary(tracks),
        artwork = tracks.firstOrNull()?.artwork,
        ownerName = "Kainos",
        trackCount = tracks.size,
        tracks = tracks,
        source = ProviderEntityRef(ProviderId.LOCAL, id),
    )
}

fun Track.toPlaylistPersisted(nowMs: Long): PersistedTrack =
    withPersistedSourcesOnly().toPersisted(nowMs)

/**
 * Portable form for Home sync: provider ids stay; device `local:` becomes
 * [localfile:]basename (no path/URI). Spotify DRM paths stay identity-only.
 */
fun PersistedTrack.forPlaylistSync(): PersistedTrack? {
    if (isLocalFileHeartCanonicalId(canonicalId)) {
        val basename = normalizedLocalFileHeartBasename(canonicalId) ?: return null
        return copy(
            canonicalId = localFileHeartId(basename),
            artworkUrl = artworkUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            sources = listOf(
                com.universalmusic.player.data.library.PersistedSource(
                    provider = ProviderId.LOCAL.name,
                    providerTrackId = basename,
                    localLocation = null,
                ),
            ),
            cachedAtMs = 0,
        )
    }
    if (canonicalId.startsWith("local:")) {
        val location = sources.firstOrNull { it.provider == ProviderId.LOCAL.name }?.localLocation
            ?: return null
        val basename = basenameFromLocalLocation(location) ?: return null
        return copy(canonicalId = localFileHeartId(basename)).forPlaylistSync()
    }
    if (canonicalId.startsWith("spotify:") || canonicalId.startsWith("yt:")) {
        val portableSources = sources.mapNotNull { source ->
            val provider = runCatching { ProviderId.valueOf(source.provider) }.getOrNull() ?: return@mapNotNull null
            when (provider) {
                ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC -> source.copy(localLocation = null)
                ProviderId.LOCAL, ProviderId.SAMPLE -> null
            }
        }
        if (portableSources.isEmpty()) return null
        return copy(
            artworkUrl = artworkUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            sources = portableSources,
            cachedAtMs = 0,
        )
    }
    return null
}

fun PersistedKainosPlaylist.forPlaylistSync(): PersistedKainosPlaylist {
    val portableEntries = entries.mapNotNull { entry ->
        val track = entry.track.forPlaylistSync() ?: return@mapNotNull null
        entry.copy(track = track)
    }
    return copy(entries = portableEntries)
}

fun kainosPlaylistAvailabilitySummary(tracks: List<Track>): String {
    if (tracks.isEmpty()) return "Kainos playlist · empty"
    var local = 0
    var missingLocal = 0
    var spotify = 0
    var youtube = 0
    for (track in tracks) {
        when (playlistEntryAvailability(track)) {
            PlaylistEntryAvailability.LOCAL -> local += 1
            PlaylistEntryAvailability.MISSING_LOCAL -> missingLocal += 1
            PlaylistEntryAvailability.SPOTIFY -> spotify += 1
            PlaylistEntryAvailability.YOUTUBE -> youtube += 1
            PlaylistEntryAvailability.UNKNOWN -> Unit
        }
    }
    val parts = buildList {
        add("Kainos")
        add(countNoun(tracks.size, "track", "tracks"))
        if (local > 0) add("$local local")
        if (spotify > 0) add("$spotify Spotify")
        if (youtube > 0) add("$youtube YouTube")
        if (missingLocal > 0) add("$missingLocal missing")
    }
    return parts.joinToString(" · ")
}

enum class PlaylistEntryAvailability {
    LOCAL,
    MISSING_LOCAL,
    SPOTIFY,
    YOUTUBE,
    UNKNOWN,
}

fun playlistEntryAvailability(track: Track): PlaylistEntryAvailability {
    if (isLocalFileHeartCanonicalId(track.canonicalId) ||
        track.canonicalId.startsWith(LOCALFILE_HEART_PREFIX)
    ) {
        return PlaylistEntryAvailability.MISSING_LOCAL
    }
    val hasLocalUrl = track.sources.any {
        it.provider == ProviderId.LOCAL && it.handle is PlaybackHandle.Url
    }
    if (hasLocalUrl) return PlaylistEntryAvailability.LOCAL
    if (track.sources.any { it.provider == ProviderId.SPOTIFY } ||
        track.canonicalId.startsWith("spotify:")
    ) {
        return PlaylistEntryAvailability.SPOTIFY
    }
    if (track.sources.any { it.provider == ProviderId.YOUTUBE_MUSIC } ||
        track.canonicalId.startsWith("yt:")
    ) {
        return PlaylistEntryAvailability.YOUTUBE
    }
    return PlaylistEntryAvailability.UNKNOWN
}

fun playlistEntryAvailabilityLabel(track: Track): String = when (playlistEntryAvailability(track)) {
    PlaylistEntryAvailability.LOCAL -> "Local"
    PlaylistEntryAvailability.MISSING_LOCAL -> "Missing file"
    PlaylistEntryAvailability.SPOTIFY -> "Spotify · Needs connection"
    PlaylistEntryAvailability.YOUTUBE -> "YouTube · Needs connection"
    PlaylistEntryAvailability.UNKNOWN -> "Unavailable"
}

private fun countNoun(n: Int, one: String, many: String): String =
    if (n == 1) "1 $one" else "$n $many"
