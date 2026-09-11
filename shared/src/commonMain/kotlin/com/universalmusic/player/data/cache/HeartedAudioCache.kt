package com.universalmusic.player.data.cache

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track
import kotlinx.serialization.Serializable

const val HEARTED_AUDIO_CACHE_FORMAT_VERSION = 1

/** Soft cap on hearted audio bytes (YouTube downloads only; never Spotify DRM audio). */
const val HEARTED_AUDIO_CACHE_MAX_BYTES: Long = 2L * 1024L * 1024L * 1024L

const val HEARTED_AUDIO_CACHE_PROVIDER_PREFIX = "hearted-yt:"

@Serializable
data class HeartedAudioCacheIndex(
    val version: Int = HEARTED_AUDIO_CACHE_FORMAT_VERSION,
    /** Keyed by the hearted track's canonicalId (`yt:…` or `spotify:…`). */
    val entries: Map<String, HeartedAudioCacheEntry> = emptyMap(),
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
)

data class HeartedAudioCacheStats(
    val entryCount: Int,
    val bytesUsed: Long,
)

/**
 * Disk cache of downloaded YouTube audio for app-hearted tracks.
 * Does not store Spotify protected streams or ephemeral stream URLs.
 */
interface HeartedAudioCache {
    suspend fun get(ownerCanonicalId: String): HeartedAudioCacheEntry?
    suspend fun put(entry: HeartedAudioCacheEntry): HeartedAudioCacheEntry
    suspend fun remove(ownerCanonicalId: String)
    suspend fun clear()
    suspend fun stats(): HeartedAudioCacheStats
    fun fileExists(uri: String): Boolean
    /** Absolute directory path where downloaders should write audio files. */
    fun audioDirectoryPath(): String
}

interface HeartedAudioCacheDisk {
    suspend fun loadIndex(): HeartedAudioCacheIndex
    suspend fun saveIndex(index: HeartedAudioCacheIndex)
    suspend fun deleteFile(uri: String)
    suspend fun clearAll()
    fun fileExists(uri: String): Boolean
    fun audioDirectoryPath(): String
    suspend fun bytesUsed(): Long
}

class DefaultHeartedAudioCache(
    private val disk: HeartedAudioCacheDisk,
    private val maxBytes: Long = HEARTED_AUDIO_CACHE_MAX_BYTES,
) : HeartedAudioCache {
    private var index: HeartedAudioCacheIndex? = null

    private suspend fun ensureIndex(): HeartedAudioCacheIndex {
        index?.let { return it }
        val loaded = disk.loadIndex().let {
            if (it.version < HEARTED_AUDIO_CACHE_FORMAT_VERSION) {
                it.copy(version = HEARTED_AUDIO_CACHE_FORMAT_VERSION)
            } else {
                it
            }
        }
        index = loaded
        return loaded
    }

    private suspend fun commit(next: HeartedAudioCacheIndex) {
        disk.saveIndex(next)
        index = next
    }

    override suspend fun get(ownerCanonicalId: String): HeartedAudioCacheEntry? {
        val entry = ensureIndex().entries[ownerCanonicalId] ?: return null
        if (!disk.fileExists(entry.localUri)) {
            remove(ownerCanonicalId)
            return null
        }
        return entry
    }

    override suspend fun put(entry: HeartedAudioCacheEntry): HeartedAudioCacheEntry {
        require(disk.fileExists(entry.localUri)) { "Cached audio file missing: ${entry.localUri}" }
        enforceBudget(entry.sizeBytes, keepOwner = entry.ownerCanonicalId)
        val next = ensureIndex().copy(
            entries = ensureIndex().entries + (entry.ownerCanonicalId to entry),
        )
        commit(next)
        return entry
    }

    override suspend fun remove(ownerCanonicalId: String) {
        val current = ensureIndex()
        val removed = current.entries[ownerCanonicalId] ?: return
        val nextEntries = current.entries - ownerCanonicalId
        val stillReferenced = nextEntries.values.any { it.youtubeVideoId == removed.youtubeVideoId }
        if (!stillReferenced) {
            disk.deleteFile(removed.localUri)
        }
        commit(current.copy(entries = nextEntries))
    }

    override suspend fun clear() {
        disk.clearAll()
        index = HeartedAudioCacheIndex()
        commit(HeartedAudioCacheIndex())
    }

    override suspend fun stats(): HeartedAudioCacheStats {
        ensureIndex()
        return HeartedAudioCacheStats(
            entryCount = ensureIndex().entries.size,
            bytesUsed = disk.bytesUsed(),
        )
    }

    override fun fileExists(uri: String): Boolean = disk.fileExists(uri)

    override fun audioDirectoryPath(): String = disk.audioDirectoryPath()

    private suspend fun enforceBudget(incomingBytes: Long, keepOwner: String) {
        var used = disk.bytesUsed()
        if (used + incomingBytes <= maxBytes) return
        val victims = ensureIndex().entries.values
            .filter { it.ownerCanonicalId != keepOwner }
            .sortedBy { it.downloadedAtMs }
        for (victim in victims) {
            if (used + incomingBytes <= maxBytes) break
            remove(victim.ownerCanonicalId)
            used = disk.bytesUsed()
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
