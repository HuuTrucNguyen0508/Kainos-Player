package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.local.percentDecodeUtf8
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
 * Portable hearts exchange document. Favorites metadata must be URI-sanitized.
 * Wire ids on the LAN: `yt:` and `localfile:<basename>`. Spotify is not mirrored here.
 */
@Serializable
data class HeartsSyncDocument(
    val deviceId: String,
    val spotifyAccountId: String?,
    val ops: List<HeartOp> = emptyList(),
    val favoritesMetadata: List<PersistedTrack> = emptyList(),
)

const val LOCALFILE_HEART_PREFIX = "localfile:"

fun isProviderHeartCanonicalId(canonicalId: String): Boolean =
    canonicalId.startsWith("spotify:") || canonicalId.startsWith("yt:")

fun isLocalFileHeartCanonicalId(canonicalId: String): Boolean =
    canonicalId.startsWith(LOCALFILE_HEART_PREFIX)

/** Spotify, YouTube, and basename-keyed local files (portable across devices). */
fun isPortableHeartCanonicalId(canonicalId: String): Boolean =
    isProviderHeartCanonicalId(canonicalId) || isLocalFileHeartCanonicalId(canonicalId)

fun isSpotifyHeartCanonicalId(canonicalId: String): Boolean =
    canonicalId.startsWith("spotify:")

fun localFileHeartId(basename: String): String =
    LOCALFILE_HEART_PREFIX + basename.trim().lowercase()

fun localFileHeartBasename(canonicalId: String): String? =
    canonicalId.takeIf { isLocalFileHeartCanonicalId(it) }
        ?.removePrefix(LOCALFILE_HEART_PREFIX)
        ?.takeIf { it.isNotBlank() }

/**
 * Basename from a local file URL / path (query stripped, lowercased).
 *
 * Android SAF document URIs end with a percent-encoded document id such as
 * `primary%3AMusic%2Ffolder%2Fsong.flac`. Taking the last `/` segment alone leaves
 * that whole id as the "basename", which cannot rematch desktop paths. Decode and
 * take the true filename after the last path separator.
 */
fun basenameFromLocalLocation(location: String): String? {
    var name = location
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBefore('?')
        .substringBefore('#')
        .trim()
    if (name.isBlank()) return null
    name = percentDecodeLight(name)
    if ('/' in name || '\\' in name) {
        name = name.substringAfterLast('/').substringAfterLast('\\').trim()
    }
    return name.takeIf { it.isNotBlank() }?.lowercase()
}

/** True filename for a [localfile:] id, including repair of mangled SAF document-id suffixes. */
fun normalizedLocalFileHeartBasename(canonicalId: String): String? {
    val raw = localFileHeartBasename(canonicalId)?.let(::repairLatin1Mojibake) ?: return null
    return basenameFromLocalLocation(raw) ?: raw.lowercase().takeIf { it.isNotBlank() }
}

/**
 * Percent-decode a file name or SAF document id as UTF-8. `+` stays literal: it is a valid
 * file-name character, and file/SAF URIs encode spaces as `%20`. (Decoding each `%XX` byte
 * as its own char used to turn CJK names into Latin-1 mojibake that could never rematch.)
 */
internal fun percentDecodeLight(value: String): String = percentDecodeUtf8(value)

/**
 * Undo mojibake stored by older builds: a UTF-8 name whose bytes were decoded one char per
 * byte. Only applies when every char fits in a byte and those bytes form strictly valid UTF-8
 * that differs from the input; anything else is returned unchanged.
 */
internal fun repairLatin1Mojibake(value: String): String {
    if (value.none { it.code in 0x80..0xFF } || value.any { it.code > 0xFF }) return value
    val bytes = ByteArray(value.length) { value[it].code.toByte() }
    val decoded = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    return decoded?.takeIf { it != value } ?: value
}

/**
 * Strip peer-local URIs and non-provider sources so sync payloads stay portable.
 * Provider tracks keep Spotify/YouTube sources; local hearts become [localfile:] metadata.
 */
fun PersistedTrack.forHeartsSync(): PersistedTrack? {
    if (isLocalFileHeartCanonicalId(canonicalId)) {
        val basename = normalizedLocalFileHeartBasename(canonicalId) ?: return null
        val portableId = localFileHeartId(basename)
        val art = artworkUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return copy(
            canonicalId = portableId,
            artworkUrl = art,
            sources = listOf(
                PersistedSource(
                    provider = ProviderId.LOCAL.name,
                    providerTrackId = basename,
                    localLocation = null,
                ),
            ),
            cachedAtMs = 0,
        )
    }
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

/**
 * Map a device-local favorite to a portable [localfile:] heart for the wire.
 * Returns null when the track has no usable basename.
 */
fun PersistedTrack.toLocalFileHeartExport(
    deviceId: String,
    revision: Long,
): Pair<HeartOp, PersistedTrack>? {
    if (!canonicalId.startsWith("local:")) return null
    val location = sources.firstOrNull { it.provider == ProviderId.LOCAL.name }?.localLocation
        ?: return null
    val basename = basenameFromLocalLocation(location) ?: return null
    val portableId = localFileHeartId(basename)
    val portable = copy(canonicalId = portableId).forHeartsSync() ?: return null
    val op = HeartOp(
        canonicalId = portableId,
        action = HeartAction.FAVORITE,
        revision = revision,
        deviceId = deviceId,
    )
    return op to portable
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
        if (!isPortableHeartCanonicalId(op.canonicalId)) return@filter false
        if (isLocalFileHeartCanonicalId(op.canonicalId)) return@filter true
        if (!isSpotifyHeartCanonicalId(op.canonicalId)) return@filter true
        val local = localSpotifyAccountId
        val remote = remoteSpotifyAccountId
        local != null && remote != null && local == remote
    }
    // Keep all local ops (including device `local:` hearts); never accept remote `local:` ops.
    val remotePortableOnly = acceptedRemote.filter { isPortableHeartCanonicalId(it.canonicalId) }
    val byId = LinkedHashMap<String, HeartOp>()
    for (op in localOps + remotePortableOnly) {
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
