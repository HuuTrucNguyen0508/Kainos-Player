package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.serialization.Serializable

const val LOCALKEY_HEART_PREFIX = "localkey:"
private val CONTENT_KEY_PATTERN = Regex("lc1:[0-9a-f]{64}")

fun localKeyHeartId(contentKey: String): String {
    require(CONTENT_KEY_PATTERN.matches(contentKey)) { "Invalid lc1 content key" }
    return LOCALKEY_HEART_PREFIX + contentKey
}

fun localKeyHeartContentKey(canonicalId: String): String? = canonicalId
    .takeIf { it.startsWith(LOCALKEY_HEART_PREFIX) }
    ?.removePrefix(LOCALKEY_HEART_PREFIX)
    ?.takeIf(CONTENT_KEY_PATTERN::matches)

/** Retain this identity even for unheart operations so a v1 downgrade can recover the basename. */
@Serializable
data class LocalSyncIdentity(
    val contentKey: String? = null,
    val basename: String? = null,
) {
    val portableId: String?
        get() = contentKey?.takeIf(CONTENT_KEY_PATTERN::matches)?.let(::localKeyHeartId)
            ?: basename?.takeIf(String::isNotBlank)?.let(::localFileHeartId)
}

fun Track.localSyncIdentity(): LocalSyncIdentity? {
    if (!canonicalId.startsWith("local:")) return null
    val source = sourceFor(ProviderId.LOCAL) ?: return null
    val location = (source.handle as? PlaybackHandle.Url)?.url ?: source.streamUrl ?: return null
    return LocalSyncIdentity(
        contentKey = localContentKey?.takeIf(CONTENT_KEY_PATTERN::matches),
        basename = basenameFromLocalLocation(location),
    )
}

/** A known key mismatch must never fall through to a same-named but different file. */
class LocalSyncIdentityIndex(tracks: List<Track>) {
    private val rows = tracks.mapNotNull { track -> track.localSyncIdentity()?.let { it to track } }
    private val byKey = rows.filter { it.first.contentKey != null }.groupBy { it.first.contentKey }
    private val byBasename = rows.filter { it.first.basename != null }.groupBy { it.first.basename }

    fun match(identity: LocalSyncIdentity): Track? {
        val key = identity.contentKey?.takeIf(CONTENT_KEY_PATTERN::matches)
        if (key != null) {
            byKey[key]?.minByOrNull { it.second.canonicalId }?.let { return it.second }
        }
        val name = identity.basename?.trim()?.lowercase() ?: return null
        val candidate = byBasename[name]?.singleOrNull() ?: return null
        if (key != null && candidate.first.contentKey != null) return null
        return candidate.second
    }
}

/** Resolve v1 aliases only when their basename identifies one known content key. */
fun localSyncAliases(identities: List<LocalSyncIdentity>): Map<String, String> = identities
    .mapNotNull { identity ->
        val key = identity.contentKey?.takeIf(CONTENT_KEY_PATTERN::matches) ?: return@mapNotNull null
        val name = identity.basename?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        name to key
    }
    .groupBy({ it.first }, { it.second })
    .mapNotNull { (name, keys) -> keys.distinct().singleOrNull()?.let { localFileHeartId(name) to localKeyHeartId(it) } }
    .toMap()

fun List<HeartOp>.collapseLocalSyncAliases(identities: List<LocalSyncIdentity>): List<HeartOp> {
    val aliases = localSyncAliases(identities)
    return map { op ->
        val identity = op.localSyncIdentity()
        val id = aliases[op.canonicalId] ?: op.canonicalId
        op.copy(canonicalId = id, localIdentity = if (isPortableLocalHeartCanonicalId(id)) {
            LocalSyncIdentity(localKeyHeartContentKey(id) ?: identity?.contentKey, identity?.basename)
        } else op.localIdentity)
    }.compacted()
}

/** Portable LOCAL source ids carry the basename; device-local rows carry a path instead. */
fun PersistedTrack.localSyncIdentity(): LocalSyncIdentity? {
    val source = sources.firstOrNull { it.provider == ProviderId.LOCAL.name } ?: return null
    val key = localKeyHeartContentKey(canonicalId) ?: localContentKey?.takeIf(CONTENT_KEY_PATTERN::matches)
    val name = when {
        canonicalId.startsWith("local:") -> source.localLocation?.let(::basenameFromLocalLocation)
        isLocalFileHeartCanonicalId(canonicalId) -> normalizedLocalFileHeartBasename(canonicalId)
        localKeyHeartContentKey(canonicalId) != null -> source.providerTrackId.takeIf(String::isNotBlank)?.lowercase()
        else -> return null
    }
    return LocalSyncIdentity(key, name)
}

fun LocalSyncIdentity.normalized(): LocalSyncIdentity = LocalSyncIdentity(
    contentKey = contentKey?.takeIf(CONTENT_KEY_PATTERN::matches),
    basename = basename?.let(::basenameFromLocalLocation),
)

fun HeartOp.localSyncIdentity(): LocalSyncIdentity? = when {
    localKeyHeartContentKey(canonicalId) != null -> LocalSyncIdentity(
        localKeyHeartContentKey(canonicalId), localIdentity?.normalized()?.basename,
    )
    isLocalFileHeartCanonicalId(canonicalId) -> LocalSyncIdentity(
        localIdentity?.normalized()?.contentKey, normalizedLocalFileHeartBasename(canonicalId),
    )
    canonicalId.startsWith("local:") -> localIdentity?.normalized()
    else -> null
}
