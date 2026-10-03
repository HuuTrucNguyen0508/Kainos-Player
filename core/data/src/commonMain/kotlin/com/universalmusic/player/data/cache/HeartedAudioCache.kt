package com.universalmusic.player.data.cache

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI

const val HEARTED_AUDIO_CACHE_FORMAT_VERSION = 2

/** Soft cap on hearted audio bytes (YouTube downloads only; never Spotify DRM audio). */
const val HEARTED_AUDIO_CACHE_MAX_BYTES: Long = 2L * 1024L * 1024L * 1024L

const val HEARTED_AUDIO_CACHE_PROVIDER_PREFIX = "hearted-yt:"

@Serializable
data class HeartedAudioCacheIndex(
    val version: Int = HEARTED_AUDIO_CACHE_FORMAT_VERSION,
    /** Keyed by the hearted track's canonicalId (`yt:…` or `spotify:…`). */
    val entries: Map<String, HeartedAudioCacheEntry> = emptyMap(),
    /**
     * Manual Spotify→YouTube (or YouTube re-pick) video overrides.
     * Preserves the owner canonical identity / heart; only changes the cached audio file.
     */
    val matchOverrides: Map<String, String> = emptyMap(),
    /** Owners protected from silent budget eviction. */
    val pinnedIds: List<String> = emptyList(),
    val failures: Map<String, HeartedCacheFailure> = emptyMap(),
    /** Recent budget/manual evictions for the Downloads UI (newest last, capped). */
    val recentEvictions: List<CacheEvictionRecord> = emptyList(),
)

@Serializable
data class HeartedAudioCacheEntry(
    val ownerCanonicalId: String,
    val youtubeVideoId: String,
    /** Stable file URI/path for LOCAL playback. Never a CDN stream URL. */
    val localUri: String,
    val qualityTier: String? = null,
    val qualityCodec: String? = null,
    val qualityBitrateKbps: Int? = null,
    val qualitySampleRateHz: Int? = null,
    val sizeBytes: Long = 0,
    val downloadedAtMs: Long = 0,
    /** True when this file was chosen as a YouTube match for a Spotify identity. */
    val spotifyViaYouTubeMatch: Boolean = false,
)

data class HeartedAudioCacheStats(
    val entryCount: Int,
    val bytesUsed: Long,
)

data class EvictionRequest(
    /** Owners that must not be removed for budget pressure (pinned + currently playing). */
    val protectedOwners: Set<String> = emptySet(),
)

/**
 * Disk cache of downloaded YouTube audio for hearted tracks.
 * Does not store Spotify protected streams or ephemeral stream URLs.
 */
interface HeartedAudioCache {
    suspend fun get(ownerCanonicalId: String): HeartedAudioCacheEntry?
    suspend fun put(
        entry: HeartedAudioCacheEntry,
        eviction: EvictionRequest = EvictionRequest(),
    ): HeartedAudioCacheEntry
    suspend fun remove(ownerCanonicalId: String, reason: String = "removed")
    suspend fun clear()
    suspend fun stats(): HeartedAudioCacheStats
    fun fileExists(uri: String): Boolean
    /** Absolute directory path where downloaders should write audio files. */
    fun audioDirectoryPath(): String

    suspend fun matchOverride(ownerCanonicalId: String): String?
    suspend fun setMatchOverride(ownerCanonicalId: String, youtubeVideoId: String)
    suspend fun clearMatchOverride(ownerCanonicalId: String)

    suspend fun isPinned(ownerCanonicalId: String): Boolean
    suspend fun setPinned(ownerCanonicalId: String, pinned: Boolean)

    suspend fun recordFailure(failure: HeartedCacheFailure)
    suspend fun clearFailure(ownerCanonicalId: String)
    suspend fun failure(ownerCanonicalId: String): HeartedCacheFailure?

    suspend fun recentEvictions(): List<CacheEvictionRecord>
    suspend fun allEntries(): List<HeartedAudioCacheEntry>
    suspend fun allPinnedIds(): Set<String>
    suspend fun allMatchOverrides(): Map<String, String>
    suspend fun allFailures(): Map<String, HeartedCacheFailure>
}

interface HeartedAudioCacheDisk {
    suspend fun loadIndex(): HeartedAudioCacheIndex
    suspend fun saveIndex(index: HeartedAudioCacheIndex)
    suspend fun deleteFile(uri: String)
    suspend fun clearAll()
    fun fileExists(uri: String): Boolean
    fun audioDirectoryPath(): String
    suspend fun bytesUsed(): Long
    suspend fun reconcile(referencedUris: Set<String>) {}
}

class DefaultHeartedAudioCache(
    private val disk: HeartedAudioCacheDisk,
    private val maxBytes: Long = HEARTED_AUDIO_CACHE_MAX_BYTES,
    private val clock: () -> Long = ::currentTimeMillis,
    private val maxEvictionHistory: Int = 40,
) : HeartedAudioCache {
    private val mutex = Mutex()
    private var index: HeartedAudioCacheIndex? = null

    private suspend fun ensureIndex(additionalReferences: Set<String> = emptySet()): HeartedAudioCacheIndex {
        index?.let { return it }
        val loaded = disk.loadIndex().let {
            if (it.version < HEARTED_AUDIO_CACHE_FORMAT_VERSION) {
                it.copy(version = HEARTED_AUDIO_CACHE_FORMAT_VERSION)
            } else {
                it
            }
        }
        disk.reconcile(loaded.entries.values.map { it.localUri }.toSet() + additionalReferences)
        index = loaded
        return loaded
    }

    private suspend fun commit(next: HeartedAudioCacheIndex) {
        disk.saveIndex(next)
        index = next
    }

    override suspend fun get(ownerCanonicalId: String): HeartedAudioCacheEntry? = mutex.withLock {
        val entry = ensureIndex().entries[ownerCanonicalId] ?: return@withLock null
        if (!disk.fileExists(entry.localUri)) {
            removeLocked(ownerCanonicalId, reason = "missing file")
            return@withLock null
        }
        return@withLock entry
    }

    override suspend fun put(
        entry: HeartedAudioCacheEntry,
        eviction: EvictionRequest,
    ): HeartedAudioCacheEntry = mutex.withLock {
        ensureIndex(setOf(entry.localUri))
        require(disk.fileExists(entry.localUri)) { "Cached audio file missing: ${entry.localUri}" }
        enforceBudget(entry.sizeBytes, keepOwner = entry.ownerCanonicalId, eviction = eviction)
        val current = ensureIndex()
        val next = current.copy(
            entries = current.entries + (entry.ownerCanonicalId to entry),
            failures = current.failures - entry.ownerCanonicalId,
        )
        commit(next)
        current.entries[entry.ownerCanonicalId]?.let { previous ->
            if (next.entries.values.none { normalizedAudioUri(it.localUri) == normalizedAudioUri(previous.localUri) }) {
                disk.deleteFile(previous.localUri)
            }
        }
        return@withLock entry
    }

    override suspend fun remove(ownerCanonicalId: String, reason: String) = mutex.withLock {
        removeLocked(ownerCanonicalId, reason)
    }

    private suspend fun removeLocked(ownerCanonicalId: String, reason: String) {
        val current = ensureIndex()
        val removed = current.entries[ownerCanonicalId] ?: run {
            if (ownerCanonicalId in current.failures || ownerCanonicalId in current.matchOverrides) {
                commit(
                    current.copy(
                        failures = current.failures - ownerCanonicalId,
                        // Keep match override unless clearing intentionally elsewhere.
                    ),
                )
            }
            return
        }
        val nextEntries = current.entries - ownerCanonicalId
        val stillReferenced = nextEntries.values.any { normalizedAudioUri(it.localUri) == normalizedAudioUri(removed.localUri) }
        val evictionRecord = CacheEvictionRecord(
            ownerCanonicalId = ownerCanonicalId,
            youtubeVideoId = removed.youtubeVideoId,
            sizeBytes = removed.sizeBytes,
            reason = reason,
            evictedAtMs = clock(),
        )
        val history = (current.recentEvictions + evictionRecord).takeLast(maxEvictionHistory)
        commit(
            current.copy(
                entries = nextEntries,
                recentEvictions = history,
                failures = current.failures - ownerCanonicalId,
            ),
        )
        if (!stillReferenced) disk.deleteFile(removed.localUri)
    }

    override suspend fun clear() = mutex.withLock {
        disk.clearAll()
        index = HeartedAudioCacheIndex()
        commit(HeartedAudioCacheIndex())
    }

    override suspend fun stats(): HeartedAudioCacheStats = mutex.withLock {
        ensureIndex()
        HeartedAudioCacheStats(
            entryCount = ensureIndex().entries.size,
            bytesUsed = disk.bytesUsed(),
        )
    }

    override fun fileExists(uri: String): Boolean = disk.fileExists(uri)

    override fun audioDirectoryPath(): String = disk.audioDirectoryPath()

    override suspend fun matchOverride(ownerCanonicalId: String): String? =
        mutex.withLock { ensureIndex().matchOverrides[ownerCanonicalId] }

    override suspend fun setMatchOverride(ownerCanonicalId: String, youtubeVideoId: String) = mutex.withLock {
        val id = youtubeVideoId.trim()
        require(id.isNotEmpty()) { "YouTube video id required" }
        val current = ensureIndex()
        commit(current.copy(matchOverrides = current.matchOverrides + (ownerCanonicalId to id)))
    }

    override suspend fun clearMatchOverride(ownerCanonicalId: String) = mutex.withLock {
        val current = ensureIndex()
        if (ownerCanonicalId !in current.matchOverrides) return@withLock
        commit(current.copy(matchOverrides = current.matchOverrides - ownerCanonicalId))
    }

    override suspend fun isPinned(ownerCanonicalId: String): Boolean =
        mutex.withLock { ownerCanonicalId in ensureIndex().pinnedIds }

    override suspend fun setPinned(ownerCanonicalId: String, pinned: Boolean) = mutex.withLock {
        val current = ensureIndex()
        val nextPins = if (pinned) {
            (current.pinnedIds + ownerCanonicalId).distinct()
        } else {
            current.pinnedIds - ownerCanonicalId
        }
        if (nextPins == current.pinnedIds) return@withLock
        commit(current.copy(pinnedIds = nextPins))
    }

    override suspend fun recordFailure(failure: HeartedCacheFailure) = mutex.withLock {
        val current = ensureIndex()
        commit(current.copy(failures = current.failures + (failure.ownerCanonicalId to failure)))
    }

    override suspend fun clearFailure(ownerCanonicalId: String) = mutex.withLock {
        val current = ensureIndex()
        if (ownerCanonicalId !in current.failures) return@withLock
        commit(current.copy(failures = current.failures - ownerCanonicalId))
    }

    override suspend fun failure(ownerCanonicalId: String): HeartedCacheFailure? =
        mutex.withLock { ensureIndex().failures[ownerCanonicalId] }

    override suspend fun recentEvictions(): List<CacheEvictionRecord> =
        mutex.withLock { ensureIndex().recentEvictions }

    override suspend fun allEntries(): List<HeartedAudioCacheEntry> =
        mutex.withLock { ensureIndex().entries.values.toList() }

    override suspend fun allPinnedIds(): Set<String> =
        mutex.withLock { ensureIndex().pinnedIds.toSet() }

    override suspend fun allMatchOverrides(): Map<String, String> =
        mutex.withLock { ensureIndex().matchOverrides }

    override suspend fun allFailures(): Map<String, HeartedCacheFailure> =
        mutex.withLock { ensureIndex().failures }

    private suspend fun enforceBudget(
        incomingBytes: Long,
        keepOwner: String,
        eviction: EvictionRequest,
    ) {
        // put() is called after the downloader wrote the file, so bytesUsed already
        // includes incomingBytes. Compare against the cap directly (do not add again).
        var used = disk.bytesUsed()
        if (used <= maxBytes) return
        val protected = eviction.protectedOwners + keepOwner + ensureIndex().pinnedIds
        val victims = ensureIndex().entries.values
            .filter { it.ownerCanonicalId !in protected }
            .sortedBy { it.downloadedAtMs }
        for (victim in victims) {
            if (used <= maxBytes) break
            removeLocked(victim.ownerCanonicalId, reason = "storage budget")
            used = disk.bytesUsed()
        }
        used = disk.bytesUsed()
        if (used > maxBytes) {
            error(
                "Hearted audio cache full: protected or pinned items prevent freeing " +
                    "space (used=$used, incoming=$incomingBytes, max=$maxBytes)",
            )
        }
    }
}

fun HeartedAudioCacheEntry.toLocalPlaybackSource(): PlaybackSource {
    val quality = qualityTier?.let { tierName ->
        val tier = runCatching { QualityTier.valueOf(tierName) }.getOrNull() ?: QualityTier.STANDARD
        AudioQuality(
            tier = tier,
            codec = qualityCodec,
            bitrateKbps = qualityBitrateKbps,
            sampleRateHz = qualitySampleRateHz,
            bitDepth = null,
        )
    } ?: AudioQuality(tier = QualityTier.HIGH, codec = qualityCodec, bitrateKbps = qualityBitrateKbps)
    return PlaybackSource(
        provider = ProviderId.LOCAL,
        providerTrackId = "$HEARTED_AUDIO_CACHE_PROVIDER_PREFIX$youtubeVideoId",
        streamUrl = localUri,
        quality = quality,
        isPlayable = true,
        handle = PlaybackHandle.Url(localUri),
    )
}

fun Track.withHeartedAudioCache(entry: HeartedAudioCacheEntry): Track {
    val local = entry.toLocalPlaybackSource()
    val withoutOldCache = sources.filterNot {
        it.provider == ProviderId.LOCAL && it.providerTrackId.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX)
    }
    return copy(sources = listOf(local) + withoutOldCache)
}

fun Track.withoutHeartedAudioCache(): Track = copy(
    sources = sources.filterNot {
        it.provider == ProviderId.LOCAL && it.providerTrackId.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX)
    },
)

internal fun normalizedAudioUri(uri: String): String = runCatching {
    val file = if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
    file.canonicalPath
}.getOrDefault(uri)

internal fun validateHeartedAudioLength(copied: Long, expected: Long?) {
    require(copied > 0L && (expected == null || copied == expected)) {
        "Incomplete audio download: received $copied bytes, expected $expected"
    }
}

/** Shared HTTP copy validation used by the Android downloader. Call off the UI thread. */
fun copyHeartedAudio(
    connection: java.net.URLConnection,
    output: java.io.OutputStream,
    checkActive: () -> Unit,
    onProgress: ((Long, Long?) -> Unit)?,
): Long {
    connection.connectTimeout = 30_000
    connection.readTimeout = 30_000
    val expected = connection.contentLengthLong.takeIf { it >= 0 }
    var copied = 0L
    connection.getInputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            onProgress?.invoke(copied, expected)
        }
    }
    validateHeartedAudioLength(copied, expected)
    return copied
}
