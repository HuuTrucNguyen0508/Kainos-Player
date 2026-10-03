package com.universalmusic.player.data.cache

import com.universalmusic.player.domain.matching.TrackMatcher
import com.universalmusic.player.domain.model.MatchReason
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.YouTubeAudioDownloader
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.isNetworkAvailable
import java.util.UUID
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val ownerGeneration: Long,
    val clearGeneration: Long,
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
    private val networkAvailable: () -> Boolean = ::isNetworkAvailable,
    private val protectedOwners: () -> Set<String> = { emptySet() },
    private val maxBytes: Long = HEARTED_AUDIO_CACHE_MAX_BYTES,
    private val onCached: (ownerCanonicalId: String, entry: HeartedAudioCacheEntry) -> Unit = { _, _ -> },
    private val onRemoved: (ownerCanonicalId: String) -> Unit = {},
) {
    private val wake = Channel<Unit>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val known = ConcurrentHashMap<String, HeartedAudioCacheEntry>()
    private val trackMeta = ConcurrentHashMap<String, Track>()
    private val youtubeQueue = ArrayDeque<HeartedCacheJob>()
    private val spotifyQueue = ArrayDeque<HeartedCacheJob>()
    private val mutationMutex = Mutex()
    private val generations = mutableMapOf<String, Long>()
    private var clearGeneration = 0L
    private var active: Pair<HeartedCacheJob, Deferred<Unit>>? = null

    private fun current(job: HeartedCacheJob): Boolean = synchronized(youtubeQueue) {
        job.clearGeneration == clearGeneration && job.ownerGeneration == (generations[job.track.canonicalId] ?: 0L)
    }

    private fun invalidate(canonicalId: String): Pair<Long, Long> = synchronized(youtubeQueue) {
        generations[canonicalId] = (generations[canonicalId] ?: 0L) + 1L
        pending.remove(canonicalId)
        known.remove(canonicalId)
        youtubeQueue.removeAll { it.track.canonicalId == canonicalId }
        spotifyQueue.removeAll { it.track.canonicalId == canonicalId }
        active?.takeIf { it.first.track.canonicalId == canonicalId }?.second?.cancel()
        (generations.getValue(canonicalId) to clearGeneration)
    }

    private fun current(canonicalId: String, token: Pair<Long, Long>): Boolean = synchronized(youtubeQueue) {
        token == ((generations[canonicalId] ?: 0L) to clearGeneration)
    }

    private inline fun publish(job: HeartedCacheJob, block: () -> Unit) = synchronized(youtubeQueue) {
        if (current(job)) block()
    }
    @Volatile private var workerStarted = false

    private val _downloads = MutableStateFlow<Map<String, DownloadItemState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadItemState>> = _downloads.asStateFlow()

    private val _snapshot = MutableStateFlow(
        DownloadsSnapshot(
            items = emptyList(),
            bytesUsed = 0,
            maxBytes = maxBytes,
            entryCount = 0,
            recentEvictions = emptyList(),
        ),
    )
    val snapshot: StateFlow<DownloadsSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch { refreshSnapshot() }
    }

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

    fun availabilityFor(track: Track): TrackAvailabilityInfo {
        val download = _downloads.value[track.canonicalId]
            ?: known[track.canonicalId]?.let { entry ->
                DownloadItemState(
                    ownerCanonicalId = entry.ownerCanonicalId,
                    title = track.title,
                    artistLine = track.artistLine,
                    phase = DownloadPhase.CACHED,
                    youtubeVideoId = entry.youtubeVideoId,
                    sizeBytes = entry.sizeBytes,
                    isSpotifyViaYouTubeMatch = entry.spotifyViaYouTubeMatch || isSpotifyIdentity(track),
                    pinned = false,
                    downloadedAtMs = entry.downloadedAtMs,
                )
            }
        return resolveTrackAvailability(
            track = applyCacheToTrack(track),
            download = download,
            networkAvailable = networkAvailable(),
        )
    }

    fun enqueue(track: Track) = synchronized(youtubeQueue) {
        if (!downloader.isAvailable()) {
            upsertDownload(track, DownloadPhase.FAILED,
                errorMessage = "YouTube audio downloader is not available on this device.")
            return@synchronized
        }
        val priority = priorityFor(track) ?: return@synchronized
        trackMeta[track.canonicalId] = track
        if (!pending.add(track.canonicalId)) return@synchronized
        upsertDownload(track, if (networkAvailable()) DownloadPhase.QUEUED else DownloadPhase.WAITING_FOR_NETWORK)
        val job = HeartedCacheJob(track, priority, generations[track.canonicalId] ?: 0L, clearGeneration)
        when (priority) {
            HeartedCachePriority.YOUTUBE -> youtubeQueue.addLast(job)
            HeartedCachePriority.SPOTIFY -> spotifyQueue.addLast(job)
        }
        ensureWorker()
        wake.trySend(Unit)
        Unit
    }

    fun enqueueMissing(tracks: Collection<Track>) {
        scope.launch {
            tracks.forEach { track ->
                trackMeta[track.canonicalId] = track
                val existing = cache.get(track.canonicalId)
                if (existing != null) {
                    known[track.canonicalId] = existing
                    upsertDownload(
                        track,
                        DownloadPhase.CACHED,
                        youtubeVideoId = existing.youtubeVideoId,
                        sizeBytes = existing.sizeBytes,
                        pinned = cache.isPinned(track.canonicalId),
                    )
                } else {
                    val failure = cache.failure(track.canonicalId)
                    if (failure != null) {
                        upsertDownload(
                            track,
                            DownloadPhase.FAILED,
                            errorMessage = failure.message,
                            youtubeVideoId = failure.youtubeVideoId,
                        )
                    } else {
                        enqueue(track)
                    }
                }
            }
            refreshSnapshot()
        }
    }

    fun retry(canonicalId: String) {
        val track = trackMeta[canonicalId] ?: return
        val token = invalidate(canonicalId)
        scope.launch {
            mutationMutex.withLock {
            if (!current(canonicalId, token)) return@withLock
            cache.clearFailure(canonicalId)
            pending.remove(canonicalId)
            enqueue(track)
            }
            refreshSnapshot()
        }
    }

    fun cancelAndRemove(canonicalId: String) {
        val token = invalidate(canonicalId)
        scope.launch {
            mutationMutex.withLock {
                if (!current(canonicalId, token)) return@withLock
                cache.remove(canonicalId, reason = "user removed")
                synchronized(youtubeQueue) {
                    if (current(canonicalId, token)) {
                        _downloads.update { it - canonicalId }
                        onRemoved(canonicalId)
                    }
                }
            }
            refreshSnapshot()
        }
    }

    fun setPinned(canonicalId: String, pinned: Boolean) {
        scope.launch {
            cache.setPinned(canonicalId, pinned)
            _downloads.update { map ->
                val item = map[canonicalId] ?: return@update map
                map + (canonicalId to item.copy(pinned = pinned))
            }
            refreshSnapshot()
        }
    }

    /**
     * Override the YouTube video used for offline audio. Keeps Spotify (or YT) identity and heart.
     * Removes the previous cached file and re-downloads.
     */
    fun setYouTubeMatchOverride(track: Track, youtubeVideoId: String) {
        trackMeta[track.canonicalId] = track
        val token = invalidate(track.canonicalId)
        scope.launch {
            mutationMutex.withLock {
            if (!current(track.canonicalId, token)) return@withLock
            cache.setMatchOverride(track.canonicalId, youtubeVideoId)
            cache.clearFailure(track.canonicalId)
            known.remove(track.canonicalId)
            cache.remove(track.canonicalId, reason = "match correction")
            synchronized(youtubeQueue) {
                if (current(track.canonicalId, token)) onRemoved(track.canonicalId)
            }
            if (current(track.canonicalId, token)) enqueue(track)
            }
            refreshSnapshot()
        }
    }

    fun clearYouTubeMatchOverride(track: Track) {
        trackMeta[track.canonicalId] = track
        val token = invalidate(track.canonicalId)
        scope.launch {
            mutationMutex.withLock {
            if (!current(track.canonicalId, token)) return@withLock
            cache.clearMatchOverride(track.canonicalId)
            known.remove(track.canonicalId)
            cache.remove(track.canonicalId, reason = "match cleared")
            synchronized(youtubeQueue) {
                if (current(track.canonicalId, token)) onRemoved(track.canonicalId)
            }
            if (current(track.canonicalId, token)) enqueue(track)
            }
            refreshSnapshot()
        }
    }

    suspend fun matchOverride(canonicalId: String): String? = cache.matchOverride(canonicalId)

    suspend fun searchMatchCandidates(track: Track, query: String? = null): List<Track> {
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: buildSearchQuery(track)
        if (q.isEmpty()) return emptyList()
        return runCatching { youtubeSearch(q) }.getOrDefault(emptyList())
    }

    suspend fun clear() {
        synchronized(youtubeQueue) {
            clearGeneration++
            active?.second?.cancel()
            pending.clear()
            known.clear()
            trackMeta.clear()
            youtubeQueue.clear()
            spotifyQueue.clear()
            _downloads.value = emptyMap()
        }
        mutationMutex.withLock { cache.clear() }
        refreshSnapshot()
    }

    suspend fun stats(): HeartedAudioCacheStats = cache.stats()

    fun notifyNetworkChanged() {
        if (!networkAvailable()) return
        var promoted = false
        _downloads.update { map ->
            map.mapValues { (_, item) ->
                if (item.phase == DownloadPhase.WAITING_FOR_NETWORK) {
                    promoted = true
                    item.copy(phase = DownloadPhase.QUEUED)
                } else {
                    item
                }
            }
        }
        if (promoted) wake.trySend(Unit)
    }

    private fun priorityFor(track: Track): HeartedCachePriority? = when {
        track.canonicalId.startsWith("yt:") ||
            track.sources.any { it.provider == ProviderId.YOUTUBE_MUSIC } -> HeartedCachePriority.YOUTUBE
        track.canonicalId.startsWith("spotify:") ||
            track.sources.any { it.provider == ProviderId.SPOTIFY } -> HeartedCachePriority.SPOTIFY
        else -> null
    }

    private fun upsertDownload(
        track: Track,
        phase: DownloadPhase,
        errorMessage: String? = null,
        youtubeVideoId: String? = null,
        sizeBytes: Long? = null,
        progress: Float? = null,
        pinned: Boolean = false,
    ) {
        val spotifyMatch = isSpotifyIdentity(track) && (
            phase == DownloadPhase.CACHED ||
                phase == DownloadPhase.DOWNLOADING ||
                phase == DownloadPhase.QUEUED ||
                phase == DownloadPhase.WAITING_FOR_NETWORK ||
                phase == DownloadPhase.FAILED
            )
        _downloads.update { map ->
            val previous = map[track.canonicalId]
            map + (
                track.canonicalId to DownloadItemState(
                    ownerCanonicalId = track.canonicalId,
                    title = track.title,
                    artistLine = track.artistLine,
                    phase = phase,
                    progress = progress,
                    errorMessage = errorMessage,
                    youtubeVideoId = youtubeVideoId ?: previous?.youtubeVideoId,
                    sizeBytes = sizeBytes ?: previous?.sizeBytes,
                    isSpotifyViaYouTubeMatch = spotifyMatch,
                    pinned = pinned || previous?.pinned == true,
                    downloadedAtMs = if (phase == DownloadPhase.CACHED) {
                        clock()
                    } else {
                        previous?.downloadedAtMs
                    },
                )
                )
        }
    }

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
                if (!networkAvailable()) {
                    requeueAsWaitingForNetwork()
                    break
                }
                val job = synchronized(youtubeQueue) {
                    youtubeQueue.removeFirstOrNull() ?: spotifyQueue.removeFirstOrNull()
                } ?: break
                val task = synchronized(youtubeQueue) {
                    if (!current(job)) null else scope.async(start = CoroutineStart.LAZY) { process(job) }.also {
                        active = job to it
                        it.start()
                    }
                } ?: continue
                try {
                    task.await()
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } finally {
                    synchronized(youtubeQueue) { if (active?.second === task) active = null }
                }
            }
            refreshSnapshot()
        }
    }

    private fun requeueAsWaitingForNetwork() = synchronized(youtubeQueue) {
        (youtubeQueue + spotifyQueue).filter(::current).forEach { job ->
            upsertDownload(job.track, DownloadPhase.WAITING_FOR_NETWORK)
        }
    }

    private suspend fun process(job: HeartedCacheJob) {
        val canonicalId = job.track.canonicalId
        val base = UUID.randomUUID().toString().replace("-", "")
        val directory = File(cache.audioDirectoryPath())
        var committed = false
        try {
            if (!current(job)) return
            val existing = cache.get(canonicalId)
            if (existing != null) {
                val pinned = cache.isPinned(canonicalId)
                publish(job) {
                    known[canonicalId] = existing
                    upsertDownload(job.track, DownloadPhase.CACHED, youtubeVideoId = existing.youtubeVideoId,
                        sizeBytes = existing.sizeBytes, pinned = pinned)
                }
                return
            }
            publish(job) { upsertDownload(job.track, DownloadPhase.DOWNLOADING, progress = 0f) }
            val videoId = resolveYouTubeVideoId(job.track) ?: error("No suitable YouTube match found.")
            currentCoroutineContext().ensureActive()
            if (!current(job)) return
            val downloaded = downloader.downloadAudio(videoId, directory.absolutePath, base) { bytes, total ->
                publish(job) {
                    upsertDownload(job.track, DownloadPhase.DOWNLOADING, youtubeVideoId = videoId,
                        progress = total?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 0.99f) })
                }
            } ?: error("Download failed for YouTube video $videoId.")
            currentCoroutineContext().ensureActive()
            val quality = downloaded.quality
            val entry = HeartedAudioCacheEntry(canonicalId, videoId, pathToFileUri(downloaded.absolutePath),
                qualityTier = quality?.tier?.name, qualityCodec = quality?.codec,
                qualityBitrateKbps = quality?.bitrateKbps, qualitySampleRateHz = quality?.sampleRateHz,
                sizeBytes = downloaded.sizeBytes, downloadedAtMs = clock(),
                spotifyViaYouTubeMatch = isSpotifyIdentity(job.track))
            // A cancellation during disk persistence must finish the transaction before removal runs.
            withContext(NonCancellable) {
                mutationMutex.withLock {
                    if (!current(job)) return@withLock
                    cache.put(entry, EvictionRequest(protectedOwners() + cache.allPinnedIds()))
                    if (!current(job)) {
                        cache.remove(canonicalId, reason = "obsolete download")
                        return@withLock
                    }
                    committed = true
                    val pinned = cache.isPinned(canonicalId)
                    publish(job) {
                        known[canonicalId] = entry
                        upsertDownload(job.track, DownloadPhase.CACHED, youtubeVideoId = videoId,
                            sizeBytes = downloaded.sizeBytes, progress = 1f, pinned = pinned)
                        onCached(canonicalId, entry)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutationMutex.withLock {
                if (current(job)) {
                    val message = failure.message?.takeIf { it.isNotBlank() } ?: "Download failed."
                    cache.recordFailure(HeartedCacheFailure(canonicalId, message, clock()))
                    publish(job) { upsertDownload(job.track, DownloadPhase.FAILED, errorMessage = message) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (!committed) {
                    val referenced = cache.allEntries().map { normalizedAudioUri(it.localUri) }.toSet()
                    directory.listFiles()?.filter { it.name.startsWith("$base.") &&
                        normalizedAudioUri(it.absolutePath) !in referenced }?.forEach { it.delete() }
                }
                synchronized(youtubeQueue) { if (current(job)) pending.remove(canonicalId) }
            }
        }
    }

    private suspend fun resolveYouTubeVideoId(track: Track): String? {
        cache.matchOverride(track.canonicalId)?.takeIf { it.isNotBlank() }?.let { return it }
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
        val query = buildSearchQuery(track)
        if (query.isEmpty()) return null
        val candidates = try { youtubeSearch(query) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
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

    private fun buildSearchQuery(track: Track): String = buildString {
        append(track.title)
        val artists = track.artists.joinToString(" ") { it.name }.trim()
        if (artists.isNotEmpty()) {
            append(' ')
            append(artists)
        }
    }.trim()

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

    private suspend fun refreshSnapshot() = mutationMutex.withLock {
        val stats = runCatching { cache.stats() }.getOrDefault(HeartedAudioCacheStats(0, 0))
        val entries = runCatching { cache.allEntries() }.getOrDefault(emptyList())
        val pins = runCatching { cache.allPinnedIds() }.getOrDefault(emptySet())
        val failures = runCatching { cache.allFailures() }.getOrDefault(emptyMap())
        val evictions = runCatching { cache.recentEvictions() }.getOrDefault(emptyList())
        val active = _downloads.value
        val cachedItems = entries.map { entry ->
            val meta = trackMeta[entry.ownerCanonicalId]
            active[entry.ownerCanonicalId]?.copy(
                phase = DownloadPhase.CACHED,
                youtubeVideoId = entry.youtubeVideoId,
                sizeBytes = entry.sizeBytes,
                pinned = entry.ownerCanonicalId in pins,
                isSpotifyViaYouTubeMatch = entry.spotifyViaYouTubeMatch ||
                    entry.ownerCanonicalId.startsWith("spotify:"),
                downloadedAtMs = entry.downloadedAtMs,
            ) ?: DownloadItemState(
                ownerCanonicalId = entry.ownerCanonicalId,
                title = meta?.title ?: entry.ownerCanonicalId,
                artistLine = meta?.artistLine.orEmpty(),
                phase = DownloadPhase.CACHED,
                youtubeVideoId = entry.youtubeVideoId,
                sizeBytes = entry.sizeBytes,
                isSpotifyViaYouTubeMatch = entry.spotifyViaYouTubeMatch ||
                    entry.ownerCanonicalId.startsWith("spotify:"),
                pinned = entry.ownerCanonicalId in pins,
                downloadedAtMs = entry.downloadedAtMs,
            )
        }
        val failedItems = failures.values
            .filter { it.ownerCanonicalId !in entries.map { e -> e.ownerCanonicalId }.toSet() }
            .map { failure ->
                val meta = trackMeta[failure.ownerCanonicalId]
                active[failure.ownerCanonicalId] ?: DownloadItemState(
                    ownerCanonicalId = failure.ownerCanonicalId,
                    title = meta?.title ?: failure.ownerCanonicalId,
                    artistLine = meta?.artistLine.orEmpty(),
                    phase = DownloadPhase.FAILED,
                    errorMessage = failure.message,
                    youtubeVideoId = failure.youtubeVideoId,
                    isSpotifyViaYouTubeMatch = failure.ownerCanonicalId.startsWith("spotify:"),
                )
            }
        val inFlight = active.values.filter {
            it.phase == DownloadPhase.QUEUED ||
                it.phase == DownloadPhase.WAITING_FOR_NETWORK ||
                it.phase == DownloadPhase.DOWNLOADING
        }
        val byId = LinkedHashMap<String, DownloadItemState>()
        (inFlight + failedItems + cachedItems).forEach { byId[it.ownerCanonicalId] = it }
        _snapshot.value = DownloadsSnapshot(
            items = byId.values.toList(),
            bytesUsed = stats.bytesUsed,
            maxBytes = maxBytes,
            entryCount = stats.entryCount,
            recentEvictions = evictions.asReversed(),
        )
    }
}

internal fun pathToFileUri(absolutePath: String): String =
    if (absolutePath.startsWith("file:")) absolutePath else File(absolutePath).toURI().toASCIIString()
