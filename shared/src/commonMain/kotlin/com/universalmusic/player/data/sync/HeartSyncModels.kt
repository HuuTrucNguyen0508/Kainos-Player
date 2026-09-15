package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.domain.model.ProviderId
import kotlinx.serialization.Serializable

/** Wire / persist format for heart mutations. Never use [PersistedTrack.cachedAtMs] for conflicts. */
@Serializable
enum class HeartAction {
    FAVORITE,
    UNFAVORITE,
}

@Serializable
data class HeartOp(
    val canonicalId: String,
    val action: HeartAction,
    /** Monotonic revision for this device (usually wall-clock ms; ties broken by deviceId). */
    val revision: Long,
    val deviceId: String,
    /** Required for Spotify-scoped ops; ignored for YouTube/other. */
    val spotifyAccountId: String? = null,
)

/**
 * Portable hearts exchange document. Favorites metadata must be URI-sanitized and
 * provider-only (`spotify:` / `yt:`). Recents and non-favorite remembered stay local.
 */
@Serializable
data class HeartsSyncDocument(
    val deviceId: String,
    val spotifyAccountId: String?,
    val ops: List<HeartOp> = emptyList(),
    val favoritesMetadata: List<PersistedTrack> = emptyList(),
)

fun isProviderHeartCanonicalId(canonicalId: String): Boolean =
    canonicalId.startsWith("spotify:") || canonicalId.startsWith("yt:")

fun isSpotifyHeartCanonicalId(canonicalId: String): Boolean =
    canonicalId.startsWith("spotify:")

/**
 * Strip peer-local URIs and non-provider sources so sync payloads stay portable.
 * Drops local-only tracks entirely.
 */
fun PersistedTrack.forHeartsSync(): PersistedTrack? {
    if (!isProviderHeartCanonicalId(canonicalId)) return null
    val portableSources = sources.mapNotNull { source ->
        val provider = runCatching { ProviderId.valueOf(source.provider) }.getOrNull() ?: return@mapNotNull null
        when (provider) {
            ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC -> source.copy(localLocation = null)
            ProviderId.LOCAL, ProviderId.SAMPLE -> null
        }
    }
    if (portableSources.isEmpty()) return null
    // Drop local artwork cache / content URIs; keep https remote art only.
    val art = artworkUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    return copy(
        artworkUrl = art,
        sources = portableSources,
        cachedAtMs = 0,
    )
}

fun List<PersistedTrack>.portableFavoriteMetadata(): List<PersistedTrack> =
    mapNotNull { it.forHeartsSync() }
        .distinctBy { it.canonicalId }
        .sortedBy { it.canonicalId }

/**
 * Merge heart ops by canonicalId. Higher [HeartOp.revision] wins; ties use lexicographic deviceId.
 * Spotify ops from a mismatched (or null) peer account are ignored when [localSpotifyAccountId] is set,
 * and remote Spotify ops are ignored when local account is null.
 */
fun mergeHeartOps(
    localOps: List<HeartOp>,
    remoteOps: List<HeartOp>,
    localSpotifyAccountId: String?,
    remoteSpotifyAccountId: String?,
): List<HeartOp> {
    val acceptedRemote = remoteOps.filter { op ->
        if (!isProviderHeartCanonicalId(op.canonicalId)) return@filter false
        if (!isSpotifyHeartCanonicalId(op.canonicalId)) return@filter true
        val local = localSpotifyAccountId
        val remote = remoteSpotifyAccountId
        local != null && remote != null && local == remote
    }
    // Keep all local ops (including local-file hearts); never accept remote local: ops.
    val remoteProviderOnly = acceptedRemote.filter { isProviderHeartCanonicalId(it.canonicalId) }
    val byId = LinkedHashMap<String, HeartOp>()
    for (op in localOps + remoteProviderOnly) {
        val existing = byId[op.canonicalId]
        if (existing == null || op.beats(existing)) {
            byId[op.canonicalId] = op
        }
    }
    return byId.values.sortedWith(compareBy({ it.canonicalId }, { it.revision }, { it.deviceId }))
}

fun HeartOp.beats(other: HeartOp): Boolean =
    revision > other.revision || (revision == other.revision && deviceId > other.deviceId)

fun List<HeartOp>.favoriteIdsFromOps(): Set<String> =
    asSequence()
        .filter { it.action == HeartAction.FAVORITE }
        .map { it.canonicalId }
        .toSet()

fun mergeFavoriteMetadata(
    local: List<PersistedTrack>,
    remote: List<PersistedTrack>,
    favoriteIds: Set<String>,
): List<PersistedTrack> {
    val byId = LinkedHashMap<String, PersistedTrack>()
    for (track in local) {
        if (track.canonicalId in favoriteIds) byId[track.canonicalId] = track
    }
    for (track in remote.mapNotNull { it.forHeartsSync() }) {
        if (track.canonicalId !in favoriteIds) continue
        val existing = byId[track.canonicalId]
        if (existing == null) {
            byId[track.canonicalId] = track
        } else {
            byId[track.canonicalId] = existing.mergePortablePreferringRemote(track)
        }
    }
    return byId.values.sortedBy { it.canonicalId }
}

private fun PersistedTrack.mergePortablePreferringRemote(remote: PersistedTrack): PersistedTrack {
    // Prefer non-blank remote title/artists/art when local missing; keep local sources that are richer.
    return copy(
        title = remote.title.ifBlank { title },
        artists = remote.artists.ifEmpty { artists },
        albumCanonicalId = remote.albumCanonicalId ?: albumCanonicalId,
        albumTitle = remote.albumTitle ?: albumTitle,
        durationMs = remote.durationMs ?: durationMs,
        artworkUrl = remote.artworkUrl ?: artworkUrl?.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        },
        explicit = remote.explicit || explicit,
        isrc = remote.isrc ?: isrc,
        sources = when {
            sources.isEmpty() -> remote.sources
            remote.sources.isEmpty() -> sources
            else -> (remote.sources + sources).distinctBy { it.provider to it.providerTrackId }
        },
        cachedAtMs = 0,
    )
}

/** Rebuild a compact op list: one winning op per canonicalId (for persistence). */
fun List<HeartOp>.compacted(): List<HeartOp> {
    val byId = LinkedHashMap<String, HeartOp>()
    for (op in this) {
        val existing = byId[op.canonicalId]
        if (existing == null || op.beats(existing)) byId[op.canonicalId] = op
    }
    return byId.values.sortedWith(compareBy({ it.canonicalId }, { it.revision }, { it.deviceId }))
}

fun emptyPortableTrack(canonicalId: String): PersistedTrack = PersistedTrack(
    canonicalId = canonicalId,
    title = canonicalId.substringAfter(':').ifBlank { canonicalId },
    artists = listOf(PersistedArtist("unknown", "Unknown")),
    sources = listOf(
        when {
            canonicalId.startsWith("spotify:") ->
                PersistedSource(ProviderId.SPOTIFY.name, canonicalId.removePrefix("spotify:"))
            canonicalId.startsWith("yt:") ->
                PersistedSource(ProviderId.YOUTUBE_MUSIC.name, canonicalId.removePrefix("yt:"))
            else -> PersistedSource(ProviderId.SAMPLE.name, canonicalId)
        },
    ),
)
