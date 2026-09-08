package com.universalmusic.player.data.cache

import com.universalmusic.player.data.library.PersistedTrack
import kotlinx.serialization.Serializable

const val METADATA_CACHE_FORMAT_VERSION = 1

/** Default metadata/artwork TTL (30 days). */
const val METADATA_CACHE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L

/** Soft cap on cached track metadata entries. */
const val METADATA_CACHE_MAX_ENTRIES: Int = 500

/** Soft cap on downloaded artwork bytes under the cache directory. */
const val METADATA_CACHE_MAX_ARTWORK_BYTES: Long = 50L * 1024L * 1024L

@Serializable
data class MetadataCacheIndex(
    val version: Int = METADATA_CACHE_FORMAT_VERSION,
    val entries: Map<String, MetadataCacheEntry> = emptyMap(),
)

@Serializable
data class MetadataCacheEntry(
    val track: PersistedTrack,
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    /** Platform path or file URI for a downloaded artwork image, if any. */
    val artworkLocalUri: String? = null,
)

data class MetadataCacheStats(
    val entryCount: Int,
    val artworkFileCount: Int,
    val artworkBytes: Long,
)

/**
 * Bounded metadata + artwork cache. Never stores protected audio streams.
 * Failed refresh must leave the previous successful entry intact.
 */
interface MetadataArtworkCache {
    suspend fun get(canonicalId: String): MetadataCacheEntry?

    /**
     * Upsert metadata. Downloads remote artwork when possible.
     * Returns the entry that should be used for display (preferring local artwork URI).
     */
    suspend fun put(track: PersistedTrack, nowMs: Long): MetadataCacheEntry

    /** Prefer local cached artwork when present and unexpired; otherwise [remoteUrl]. */
    suspend fun resolveArtworkUrl(canonicalId: String, remoteUrl: String?): String?

    suspend fun evictExpired(nowMs: Long)

    suspend fun clear()

    suspend fun stats(): MetadataCacheStats
}

interface MetadataCacheDisk {
    suspend fun loadIndex(): MetadataCacheIndex
    suspend fun saveIndex(index: MetadataCacheIndex)
    /** Writes image bytes; returns a URI/path usable by Coil and system controls. */
    suspend fun saveArtwork(canonicalId: String, bytes: ByteArray): String?
    suspend fun deleteArtwork(canonicalId: String)
    suspend fun clearAll()
    fun artworkExists(uri: String): Boolean
    suspend fun artworkBytesUsed(): Long
    suspend fun artworkFileCount(): Int
}

class DefaultMetadataArtworkCache(
    private val disk: MetadataCacheDisk,
    private val downloadArtwork: suspend (url: String) -> ByteArray?,
    private val maxEntries: Int = METADATA_CACHE_MAX_ENTRIES,
    private val maxArtworkBytes: Long = METADATA_CACHE_MAX_ARTWORK_BYTES,
    private val ttlMs: Long = METADATA_CACHE_TTL_MS,
) : MetadataArtworkCache {
    private var index: MetadataCacheIndex? = null

    private suspend fun ensureIndex(): MetadataCacheIndex {
        index?.let { return it }
        val loaded = disk.loadIndex().let {
            if (it.version < METADATA_CACHE_FORMAT_VERSION) {
                it.copy(version = METADATA_CACHE_FORMAT_VERSION)
            } else {
                it
            }
        }
        index = loaded
        return loaded
    }

    private suspend fun commit(next: MetadataCacheIndex) {
        disk.saveIndex(next)
        index = next
    }

    override suspend fun get(canonicalId: String): MetadataCacheEntry? =
        ensureIndex().entries[canonicalId]

    override suspend fun put(track: PersistedTrack, nowMs: Long): MetadataCacheEntry {
        val previous = get(track.canonicalId)
        val expires = nowMs + ttlMs
        var artworkLocal = previous?.artworkLocalUri?.takeIf { disk.artworkExists(it) }
        val remote = track.artworkUrl
        if (artworkLocal == null && !remote.isNullOrBlank() && isRemoteUrl(remote)) {
            runCatching {
                val bytes = downloadArtwork(remote) ?: return@runCatching
                if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return@runCatching
                enforceArtworkBudget(bytes.size.toLong())
                artworkLocal = disk.saveArtwork(track.canonicalId, bytes)
            }
        } else if (artworkLocal == null && !remote.isNullOrBlank() && !isRemoteUrl(remote)) {
            artworkLocal = remote.takeIf { disk.artworkExists(it) || it.startsWith("file:") || it.startsWith("content:") }
        }

        val displayUrl = artworkLocal ?: track.artworkUrl
        val entry = MetadataCacheEntry(
            track = track.copy(artworkUrl = displayUrl, cachedAtMs = nowMs),
            fetchedAtMs = nowMs,
            expiresAtMs = expires,
            artworkLocalUri = artworkLocal,
        )
        val current = ensureIndex()
        val pruned = pruneEntries(current.entries + (track.canonicalId to entry), nowMs)
        commit(current.copy(version = METADATA_CACHE_FORMAT_VERSION, entries = pruned))
        return entry
    }

    override suspend fun resolveArtworkUrl(canonicalId: String, remoteUrl: String?): String? {
        val entry = get(canonicalId) ?: return remoteUrl
        val local = entry.artworkLocalUri?.takeIf { disk.artworkExists(it) }
        if (local != null) return local
        return entry.track.artworkUrl ?: remoteUrl
    }

    override suspend fun evictExpired(nowMs: Long) {
        val current = ensureIndex()
        val kept = current.entries.filterValues { it.expiresAtMs >= nowMs }
        val removed = current.entries.keys - kept.keys
        removed.forEach { disk.deleteArtwork(it) }
        commit(current.copy(entries = kept))
    }

    override suspend fun clear() {
        disk.clearAll()
        index = MetadataCacheIndex(version = METADATA_CACHE_FORMAT_VERSION)
        commit(index!!)
    }

    override suspend fun stats(): MetadataCacheStats {
        ensureIndex()
        return MetadataCacheStats(
            entryCount = index?.entries?.size ?: 0,
            artworkFileCount = disk.artworkFileCount(),
            artworkBytes = disk.artworkBytesUsed(),
        )
    }

    private suspend fun enforceArtworkBudget(incoming: Long) {
        var used = disk.artworkBytesUsed()
        if (used + incoming <= maxArtworkBytes) return
        val current = ensureIndex()
        val oldestFirst = current.entries.entries.sortedBy { it.value.fetchedAtMs }
        val mutable = current.entries.toMutableMap()
        for (entry in oldestFirst) {
            if (used + incoming <= maxArtworkBytes) break
            disk.deleteArtwork(entry.key)
            mutable.remove(entry.key)
            used = disk.artworkBytesUsed()
        }
        commit(current.copy(entries = mutable))
    }

    private fun pruneEntries(
        entries: Map<String, MetadataCacheEntry>,
        nowMs: Long,
    ): Map<String, MetadataCacheEntry> {
        val unexpired = entries.filterValues { it.expiresAtMs >= nowMs }
        if (unexpired.size <= maxEntries) return unexpired
        return unexpired.entries
            .sortedByDescending { it.value.fetchedAtMs }
            .take(maxEntries)
            .associate { it.key to it.value }
    }

    private fun isRemoteUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
}

/** Keep previous entry when [block] fails (partial refresh safety). */
suspend fun MetadataArtworkCache.putPreservingOnFailure(
    track: PersistedTrack,
    nowMs: Long,
): MetadataCacheEntry {
    val previous = get(track.canonicalId)
    return try {
        put(track, nowMs)
    } catch (_: Throwable) {
        previous ?: MetadataCacheEntry(
            track = track,
            fetchedAtMs = nowMs,
            expiresAtMs = nowMs + METADATA_CACHE_TTL_MS,
            artworkLocalUri = null,
        )
    }
}
