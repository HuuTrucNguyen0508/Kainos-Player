package com.universalmusic.player.data.cache

import com.universalmusic.player.domain.matching.TrackMatcher
import com.universalmusic.player.domain.model.MatchReason
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.YouTubeAudioDownloader
import com.universalmusic.player.platform.currentTimeMillis
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private enum class HeartedCachePriority {
    YOUTUBE,
    SPOTIFY,
}

private data class HeartedCacheJob(
    val track: Track,
    val priority: HeartedCachePriority,
)

/**
 * Background queue that downloads YouTube audio for hearted tracks.
 * YouTube hearts run first; Spotify hearts search for a YouTube match and cache that file.
 * Never caches Spotify DRM audio.
 */
class HeartedAudioCacheService(
    private val scope: CoroutineScope,
    private val cache: HeartedAudioCache,
    private val downloader: YouTubeAudioDownloader,
    private val youtubeSearch: suspend (query: String) -> List<Track>,
    private val matcher: TrackMatcher = TrackMatcher(),
    private val clock: () -> Long = ::currentTimeMillis,
    private val onCached: (ownerCanonicalId: String, entry: HeartedAudioCacheEntry) -> Unit = { _, _ -> },
    private val onRemoved: (ownerCanonicalId: String) -> Unit = {},
) {
    private val wake = Channel<Unit>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val known = ConcurrentHashMap<String, HeartedAudioCacheEntry>()
    private val youtubeQueue = ArrayDeque<HeartedCacheJob>()
    private val spotifyQueue = ArrayDeque<HeartedCacheJob>()
    private val workerMutex = Mutex()
    @Volatile private var workerStarted = false

    /** Fast path for play/library: attach LOCAL source when an entry is already known. */
    fun applyCacheToTrack(track: Track): Track {
        val entry = known[track.canonicalId] ?: return track
        if (!cache.fileExists(entry.localUri)) {
            known.remove(track.canonicalId)
            return track.withoutHeartedAudioCache()
        }
        return track.withHeartedAudioCache(entry)
    }

    suspend fun hydrate(track: Track): Track {
        val entry = cache.get(track.canonicalId)
        if (entry == null) {
            known.remove(track.canonicalId)
            return track.withoutHeartedAudioCache()
        }
        known[track.canonicalId] = entry
        return track.withHeartedAudioCache(entry)
    }

    fun rememberEntry(entry: HeartedAudioCacheEntry) {
        known[entry.ownerCanonicalId] = entry
    }

    fun enqueue(track: Track) {
        if (!downloader.isAvailable()) return
        val priority = when {
            track.canonicalId.startsWith("yt:") ||
                track.sources.any { it.provider == ProviderId.YOUTUBE_MUSIC } -> HeartedCachePriority.YOUTUBE
            track.canonicalId.startsWith("spotify:") ||
                track.sources.any { it.provider == ProviderId.SPOTIFY } -> HeartedCachePriority.SPOTIFY
            else -> return
        }
        if (!pending.add(track.canonicalId)) return
        ensureWorker()
        val job = HeartedCacheJob(track, priority)
        synchronized(youtubeQueue) {
            when (priority) {
                HeartedCachePriority.YOUTUBE -> youtubeQueue.addLast(job)
                HeartedCachePriority.SPOTIFY -> spotifyQueue.addLast(job)
            }
        }
        wake.trySend(Unit)
    }

    fun enqueueMissing(tracks: Collection<Track>) {
        scope.launch {
            tracks.forEach { track ->
                val existing = cache.get(track.canonicalId)
                if (existing != null) {
                    known[track.canonicalId] = existing
                } else {
                    enqueue(track)
                }
            }
        }
    }

    fun cancelAndRemove(canonicalId: String) {
        pending.remove(canonicalId)
        known.remove(canonicalId)
        scope.launch {
            synchronized(youtubeQueue) {
                youtubeQueue.removeAll { it.track.canonicalId == canonicalId }
                spotifyQueue.removeAll { it.track.canonicalId == canonicalId }
            }
            cache.remove(canonicalId)
            onRemoved(canonicalId)
        }
    }

    suspend fun clear() {
        pending.clear()
        known.clear()
        synchronized(youtubeQueue) {
            youtubeQueue.clear()
            spotifyQueue.clear()
        }
        cache.clear()
    }

    suspend fun stats(): HeartedAudioCacheStats = cache.stats()

    private fun ensureWorker() {
        if (workerStarted) return
        synchronized(this) {
            if (workerStarted) return
            workerStarted = true
            scope.launch { drain() }
        }
    }

    private suspend fun drain() {
        for (unit in wake) {
            while (true) {
                val job = synchronized(youtubeQueue) {
                    youtubeQueue.removeFirstOrNull() ?: spotifyQueue.removeFirstOrNull()
                } ?: break
                process(job)
            }
        }
    }

    private suspend fun process(job: HeartedCacheJob) {
        workerMutex.withLock {
            val canonicalId = job.track.canonicalId
            try {
                cache.get(canonicalId)?.let {
                    known[canonicalId] = it
                    return@withLock
                }
                val videoId = resolveYouTubeVideoId(job.track) ?: return@withLock
                val safeName = UUID.nameUUIDFromBytes(canonicalId.toByteArray()).toString().replace("-", "")
                val downloaded = downloader.downloadAudio(
                    videoId = videoId,
                    destinationDirectory = cache.audioDirectoryPath(),
                    fileBaseName = safeName,
                ) ?: return@withLock
                val quality = downloaded.quality
                val entry = HeartedAudioCacheEntry(
                    ownerCanonicalId = canonicalId,
                    youtubeVideoId = videoId,
                    localUri = pathToFileUri(downloaded.absolutePath),
                    qualityTier = quality?.tier?.name,
                    qualityCodec = quality?.codec,
                    qualityBitrateKbps = quality?.bitrateKbps,
                    qualitySampleRateHz = quality?.sampleRateHz,
                    sizeBytes = downloaded.sizeBytes,
                    downloadedAtMs = clock(),
                )
                cache.put(entry)
                known[canonicalId] = entry
                onCached(canonicalId, entry)
            } finally {
                pending.remove(canonicalId)
            }
        }
    }

    private suspend fun resolveYouTubeVideoId(track: Track): String? {
        track.sources.firstOrNull { it.provider == ProviderId.YOUTUBE_MUSIC }?.providerTrackId
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        if (track.canonicalId.startsWith("yt:")) {
            return track.canonicalId.removePrefix("yt:").takeIf { it.isNotBlank() }
        }
        if (!track.canonicalId.startsWith("spotify:") &&
            track.sources.none { it.provider == ProviderId.SPOTIFY }
        ) {
            return null
        }
        val query = buildString {
            append(track.title)
            val artists = track.artists.joinToString(" ") { it.name }.trim()
            if (artists.isNotEmpty()) {
                append(' ')
                append(artists)
            }
        }.trim()
        if (query.isEmpty()) return null
        val candidates = runCatching { youtubeSearch(query) }.getOrDefault(emptyList())
        val best = candidates
            .asSequence()
            .map { candidate -> matchConfidence(track, candidate) to candidate }
            .filter { (confidence, _) -> confidence >= 0.70f }
            .maxByOrNull { (confidence, _) -> confidence }
            ?.second
            ?: return null
        return best.sources.firstOrNull { it.provider == ProviderId.YOUTUBE_MUSIC }?.providerTrackId
            ?: best.canonicalId.removePrefix("yt:").takeIf { best.canonicalId.startsWith("yt:") }
    }

    /**
     * Prefer [TrackMatcher] scores; when both sides lack duration (common for thin search stubs),
     * accept exact title+artist as a hearted-cache mirror.
     */
    private fun matchConfidence(seed: Track, candidate: Track): Float {
        val match = matcher.match(seed, candidate)
        if (match.reason != MatchReason.NONE) return match.confidence
        val seedTitle = seed.title.trim().lowercase()
        val candidateTitle = candidate.title.trim().lowercase()
        if (seedTitle.isEmpty() || seedTitle != candidateTitle) return 0f
        val seedArtists = seed.artists.map { it.name.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val candidateArtists = candidate.artists.map { it.name.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (seedArtists.isEmpty() || seedArtists != candidateArtists) return 0f
        return 0.80f
    }
}

internal fun pathToFileUri(absolutePath: String): String {
    if (absolutePath.startsWith("file:")) return absolutePath
    val normalized = absolutePath.replace('\\', '/')
    return if (normalized.startsWith("/")) "file://$normalized" else "file:///$normalized"
}
