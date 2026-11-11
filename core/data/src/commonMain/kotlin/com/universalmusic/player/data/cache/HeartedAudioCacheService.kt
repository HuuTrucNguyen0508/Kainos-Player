package com.universalmusic.player.data.cache

import com.universalmusic.player.platform.logged
import com.universalmusic.player.domain.matching.TrackMatcher
import com.universalmusic.player.domain.model.MatchReason
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.YouTubeAudioDownloader
import com.universalmusic.player.platform.YouTubeDownloadResult
import com.universalmusic.player.platform.YouTubeFailureKind
import com.universalmusic.player.platform.classifyYouTubeFailure
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.isNetworkAvailable
import java.util.UUID
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    /** Transient failures so far; the job is failed for good once this reaches [MAX_TRANSIENT_ATTEMPTS]. */
    val attempts: Int = 0,
)

/** A download that failed with a reason the queue can act on. */
private class JobFailure(val kind: YouTubeFailureKind, reason: String, val videoId: String?) : Exception(reason)

private enum class NetworkGate { OPEN, OFFLINE, METERED }

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
    /** True on mobile data or another metered network. */
    private val meteredNetwork: () -> Boolean = { false },
    /** "Download on Wi-Fi only": hold downloads while [meteredNetwork]. */
    private val wifiOnly: () -> Boolean = { false },
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
    private val extractorIssue = MutableStateFlow<String?>(null)

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
        val entry = known[track.canonicalId] ?: return track.copy(sources = track.sources.filterNot { source ->
            val uri = (source.handle as? PlaybackHandle.Url)?.url
            source.provider == ProviderId.LOCAL && source.providerTrackId.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX) &&
                (uri == null || !cache.fileExists(uri))
        })
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
        upsertDownload(track, gate().queuedPhase())
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
                    if (cache.isBudgetEvicted(track.canonicalId) && !cache.isPinned(track.canonicalId)) return@forEach
                    // The user removed this audio; it stays off until they tap Download.
                    if (cache.isUserExcluded(track.canonicalId)) return@forEach
                    var failure = cache.failure(track.canonicalId)
                    // An outdated extractor is not this video's fault: try again once the app or yt-dlp moved on.
                    if (failure?.kind == YouTubeFailureKind.OUTDATED_EXTRACTOR.name) {
                        cache.clearFailure(track.canonicalId)
                        failure = null
                    }
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

    /** Retry a failed download now. The same as [download]. */
    fun retry(canonicalId: String) = download(canonicalId)

    /**
     * The user asked for this audio: forget any failure or earlier removal and queue it now,
     * ahead of any backoff.
     */
    fun download(canonicalId: String) {
        val track = trackMeta[canonicalId] ?: return
        val token = invalidate(canonicalId)
        scope.launch {
            mutationMutex.withLock {
            if (!current(canonicalId, token)) return@withLock
            cache.clearFailure(canonicalId)
            cache.setUserExcluded(canonicalId, false)
            pending.remove(canonicalId)
            enqueue(track)
            }
            refreshSnapshot()
        }
    }

    /** Unheart: the audio goes and nothing is remembered, so a later heart downloads it again. */
    fun cancelAndRemove(canonicalId: String) = removeAudio(canonicalId, keepOff = false)

    /**
     * Downloads → Remove: the audio goes but the heart stays, and startup leaves it alone until
     * the user taps Download.
     */
    fun removeAndKeepOff(canonicalId: String) = removeAudio(canonicalId, keepOff = true)

    private fun removeAudio(canonicalId: String, keepOff: Boolean) {
        val token = invalidate(canonicalId)
        scope.launch {
            mutationMutex.withLock {
                if (!current(canonicalId, token)) return@withLock
                cache.remove(canonicalId, reason = "user removed")
                cache.setUserExcluded(canonicalId, keepOff)
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
        return youtubeSearch(q)
    }

    /**
     * Deletes every cached file. [hearted] owners that this service downloads are kept off until the
     * user taps Download, so a clear is not undone by the next launch.
     */
    suspend fun clear(hearted: Collection<String> = emptyList()) {
        val keptOff = synchronized(youtubeQueue) {
            clearGeneration++
            active?.second?.cancel()
            pending.clear()
            known.clear()
            youtubeQueue.clear()
            spotifyQueue.clear()
            _downloads.value = emptyMap()
            hearted.filter { id ->
                id.startsWith("yt:") || id.startsWith("spotify:") || trackMeta[id]?.let(::priorityFor) != null
            }.toSet()
        }
        mutationMutex.withLock { cache.clear(keptOff) }
        extractorIssue.value = null
        refreshSnapshot()
    }

    suspend fun stats(): HeartedAudioCacheStats = cache.stats()

    /** Connectivity, metering or the Wi-Fi-only switch changed: re-check what may download. */
    fun notifyNetworkChanged() {
        if (workerStarted) wake.trySend(Unit)
    }

    private fun gate(): NetworkGate = when {
        !networkAvailable() -> NetworkGate.OFFLINE
        wifiOnly() && meteredNetwork() -> NetworkGate.METERED
        else -> NetworkGate.OPEN
    }

    private fun NetworkGate.queuedPhase(): DownloadPhase = when (this) {
        NetworkGate.OPEN -> DownloadPhase.QUEUED
        NetworkGate.OFFLINE -> DownloadPhase.WAITING_FOR_NETWORK
        NetworkGate.METERED -> DownloadPhase.WAITING_FOR_WIFI
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
        retryAtMs: Long? = null,
    ) {
        val spotifyMatch = isSpotifyIdentity(track) && phase != DownloadPhase.NOT_DOWNLOADED
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
                    retryAtMs = retryAtMs,
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
                val gate = gate()
                labelQueued(gate.queuedPhase())
                if (gate != NetworkGate.OPEN) break
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

    /** Shows every queued job as [phase]: Queued, Waiting for network or Waiting for Wi-Fi. */
    private fun labelQueued(phase: DownloadPhase) = synchronized(youtubeQueue) {
        val queued = (youtubeQueue + spotifyQueue).filter(::current).map { it.track.canonicalId }.toSet()
        if (queued.isEmpty()) return@synchronized
        _downloads.update { map ->
            map.mapValues { (id, item) ->
                if (id in queued && item.phase in QUEUED_PHASES && item.phase != phase) item.copy(phase = phase) else item
            }
        }
    }

    /** Puts [job] back at the end of its queue, unless a retry, removal or clear replaced it meanwhile. */
    private fun requeue(job: HeartedCacheJob) = synchronized(youtubeQueue) {
        if (!current(job)) return@synchronized
        when (job.priority) {
            HeartedCachePriority.YOUTUBE -> youtubeQueue.addLast(job)
            HeartedCachePriority.SPOTIFY -> spotifyQueue.addLast(job)
        }
        upsertDownload(job.track, gate().queuedPhase())
        wake.trySend(Unit)
    }

    /**
     * Decides what a failed job does next. Returns true while it stays queued (parked or waiting),
     * so its owner stays pending. Never holds up the jobs behind it.
     */
    private suspend fun handleFailure(job: HeartedCacheJob, kind: YouTubeFailureKind, reason: String, videoId: String?): Boolean =
        mutationMutex.withLock {
            if (!current(job)) return@withLock false
            val canonicalId = job.track.canonicalId
            PlaybackTrace.log(LOG_TAG, "$canonicalId${videoId?.let { " ($it)" }.orEmpty()} ${kind.name}: $reason")
            val gate = gate()
            when {
                // The connection went away mid-download: wait for it, without spending an attempt.
                gate != NetworkGate.OPEN && kind != YouTubeFailureKind.OUTDATED_EXTRACTOR -> {
                    requeue(job)
                    true
                }
                kind == YouTubeFailureKind.TRANSIENT && job.attempts + 1 < MAX_TRANSIENT_ATTEMPTS -> {
                    val wait = transientBackoffMs(job.attempts)
                    publish(job) {
                        upsertDownload(job.track, DownloadPhase.QUEUED, errorMessage = reason,
                            youtubeVideoId = videoId, retryAtMs = clock() + wait)
                    }
                    val next = job.copy(attempts = job.attempts + 1)
                    scope.launch {
                        delay(wait)
                        requeue(next)
                    }
                    true
                }
                else -> {
                    val message = if (kind == YouTubeFailureKind.TRANSIENT) {
                        "Gave up after $MAX_TRANSIENT_ATTEMPTS tries: $reason"
                    } else {
                        reason
                    }
                    if (kind == YouTubeFailureKind.OUTDATED_EXTRACTOR) extractorIssue.value = reason
                    cache.recordFailure(HeartedCacheFailure(canonicalId, message, clock(), videoId, kind.name))
                    publish(job) {
                        upsertDownload(job.track, DownloadPhase.FAILED, errorMessage = message, youtubeVideoId = videoId)
                    }
                    false
                }
            }
        }

    private suspend fun process(job: HeartedCacheJob) {
        val canonicalId = job.track.canonicalId
        val base = UUID.randomUUID().toString().replace("-", "")
        val directory = File(cache.audioDirectoryPath())
        var committed = false
        var stillQueued = false
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
            val videoId = resolveYouTubeVideoId(job.track)
                ?: throw JobFailure(YouTubeFailureKind.PERMANENT, "No suitable YouTube match found.", null)
            currentCoroutineContext().ensureActive()
            if (!current(job)) return
            val result = downloader.downloadAudio(videoId, directory.absolutePath, base) { bytes, total ->
                publish(job) {
                    upsertDownload(job.track, DownloadPhase.DOWNLOADING, youtubeVideoId = videoId,
                        progress = total?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 0.99f) })
                }
            }
            val downloaded = when (result) {
                is YouTubeDownloadResult.Downloaded -> result.audio
                is YouTubeDownloadResult.Failed -> throw JobFailure(result.kind, result.reason, videoId)
            }
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
                    putReportingEvictions(entry)
                    if (!current(job)) {
                        cache.remove(canonicalId, reason = "obsolete download")
                        return@withLock
                    }
                    committed = true
                    extractorIssue.value = null
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
        } catch (failure: JobFailure) {
            stillQueued = handleFailure(job, failure.kind, failure.message.orEmpty(), failure.videoId)
        } catch (failure: Exception) {
            val message = failure.message?.takeIf { it.isNotBlank() } ?: (failure::class.simpleName ?: "Download failed.")
            stillQueued = handleFailure(job, classifyServiceFailure(failure), message, null)
        } finally {
            withContext(NonCancellable) {
                if (!committed) {
                    val referenced = cache.allEntries().map { normalizedAudioUri(it.localUri) }.toSet()
                    directory.listFiles()?.filter { it.name.startsWith("$base.") &&
                        normalizedAudioUri(it.absolutePath) !in referenced }?.forEach { it.delete() }
                }
                synchronized(youtubeQueue) { if (current(job) && !stillQueued) pending.remove(canonicalId) }
            }
        }
    }

    /** Caller holds [mutationMutex]. Budget evictions this put causes leave the download list and library. */
    private suspend fun putReportingEvictions(entry: HeartedAudioCacheEntry) {
        val before = cache.allEntries().map { it.ownerCanonicalId }.toSet()
        try { cache.put(entry, EvictionRequest(protectedOwners() + cache.allPinnedIds())) }
        finally {
            val remaining = cache.allEntries().map { it.ownerCanonicalId }.toSet()
            (before - remaining).forEach { removed ->
                known.remove(removed)
                _downloads.update { it - removed }
                onRemoved(removed)
            }
        }
    }

    /**
     * Fails before any byte of a Home sync copy moves when [sizeBytes] cannot fit beside the pinned and
     * now-playing downloads, which the budget never evicts.
     */
    suspend fun checkRoomForCopy(ownerCanonicalId: String, sizeBytes: Long) {
        val kept = protectedOwners() + cache.allPinnedIds() - ownerCanonicalId
        val keptBytes = cache.allEntries().filter { it.ownerCanonicalId in kept }.sumOf { it.sizeBytes }
        check(keptBytes + sizeBytes <= maxBytes) {
            "Offline cache full: pinned and playing downloads leave no room for ${sizeBytes / (1024 * 1024)} MiB"
        }
    }

    /** Taken when a Home sync copy starts; a clear, unheart or match change since then makes the copy stale. */
    fun copyGeneration(ownerCanonicalId: String): Pair<Long, Long> = synchronized(youtubeQueue) {
        (generations[ownerCanonicalId] ?: 0L) to clearGeneration
    }

    enum class CopyAdoption { ADOPTED, ALREADY_CACHED, STALE }

    /**
     * Enters a file another device copied (already at [entry]'s `localUri` in the audio directory) as an
     * ordinary download, so it plays offline and shows as Cached. Refuses a copy started before a clear,
     * unheart or match change ([generation] from [copyGeneration]), and keeps the existing file when this
     * owner got cached meanwhile. Throws when the budget cannot make room. The caller deletes its copy
     * unless this returned [CopyAdoption.ADOPTED].
     */
    suspend fun adoptCopiedFile(entry: HeartedAudioCacheEntry, generation: Pair<Long, Long>): CopyAdoption {
        val owner = entry.ownerCanonicalId
        // Nothing after the commit may be cancelled, or the caller would delete a file the index names.
        return withContext(NonCancellable) {
            val adopted = mutationMutex.withLock {
                if (!current(owner, generation)) return@withLock CopyAdoption.STALE
                if (cache.get(owner) != null) return@withLock CopyAdoption.ALREADY_CACHED
                putReportingEvictions(entry)
                val pinned = cache.isPinned(owner)
                // A clear or unheart can land while the put suspends; publish only if none did, as downloads do.
                val published = synchronized(youtubeQueue) {
                    current(owner, generation).also { live ->
                        if (!live) return@also
                        known[owner] = entry
                        trackMeta[owner]?.let { track ->
                            upsertDownload(track, DownloadPhase.CACHED, youtubeVideoId = entry.youtubeVideoId,
                                sizeBytes = entry.sizeBytes, progress = 1f, pinned = pinned)
                        }
                        onCached(owner, entry)
                    }
                }
                if (!published) {
                    cache.remove(owner, reason = "obsolete copy")
                    return@withLock CopyAdoption.STALE
                }
                CopyAdoption.ADOPTED
            }
            if (adopted == CopyAdoption.ADOPTED) refreshSnapshot()
            adopted
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
        val candidates = youtubeSearch(query)
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
        val stats = runCatching { cache.stats() }.logged(LOG_TAG, "stats read failed")
            .getOrDefault(HeartedAudioCacheStats(0, 0))
        val entries = runCatching { cache.allEntries() }.logged(LOG_TAG, "entries read failed").getOrDefault(emptyList())
        val pins = runCatching { cache.allPinnedIds() }.logged(LOG_TAG, "pins read failed").getOrDefault(emptySet())
        val failures = runCatching { cache.allFailures() }.logged(LOG_TAG, "failures read failed").getOrDefault(emptyMap())
        val evictions = runCatching { cache.recentEvictions() }.logged(LOG_TAG, "evictions read failed")
            .getOrDefault(emptyList())
        val keptOff = runCatching { cache.allUserExcluded() }.logged(LOG_TAG, "removals read failed")
            .getOrDefault(emptySet())
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
        val inFlight = active.values.filter { it.phase in QUEUED_PHASES || it.phase == DownloadPhase.DOWNLOADING }
        val byId = LinkedHashMap<String, DownloadItemState>()
        (inFlight + failedItems + cachedItems).forEach { byId[it.ownerCanonicalId] = it }
        // Removed by the user but still hearted: listed last, with Download instead of Remove.
        keptOff.filter { it !in byId }.forEach { id ->
            val meta = trackMeta[id] ?: return@forEach
            byId[id] = DownloadItemState(
                ownerCanonicalId = id,
                title = meta.title,
                artistLine = meta.artistLine,
                phase = DownloadPhase.NOT_DOWNLOADED,
                isSpotifyViaYouTubeMatch = false,
            )
        }
        _snapshot.value = DownloadsSnapshot(
            items = byId.values.toList(),
            bytesUsed = stats.bytesUsed,
            maxBytes = maxBytes,
            entryCount = stats.entryCount,
            recentEvictions = evictions.asReversed(),
            extractorIssue = extractorIssue.value,
        )
    }
}

internal fun pathToFileUri(absolutePath: String): String =
    if (absolutePath.startsWith("file:")) absolutePath else File(absolutePath).toURI().toASCIIString()

/**
 * A failure outside the extractor (the Data API search, the cache budget). Only the extractor can
 * be outdated, so this is transient or permanent.
 */
internal fun classifyServiceFailure(failure: Exception): YouTubeFailureKind {
    if (failure is java.io.IOException) return YouTubeFailureKind.TRANSIENT
    val message = failure.message.orEmpty()
    if ("cache full" in message.lowercase()) return YouTubeFailureKind.PERMANENT
    return when (classifyYouTubeFailure(message)) {
        YouTubeFailureKind.TRANSIENT -> YouTubeFailureKind.TRANSIENT
        else -> YouTubeFailureKind.PERMANENT
    }
}

private val QUEUED_PHASES = setOf(DownloadPhase.QUEUED, DownloadPhase.WAITING_FOR_NETWORK, DownloadPhase.WAITING_FOR_WIFI)

/** One try plus three retries, 30 s, 2 min and 8 min apart. */
internal const val MAX_TRANSIENT_ATTEMPTS = 4

internal fun transientBackoffMs(attemptsSoFar: Int): Long = 30_000L shl (2 * attemptsSoFar.coerceIn(0, 4))

private const val LOG_TAG = "Downloads"
