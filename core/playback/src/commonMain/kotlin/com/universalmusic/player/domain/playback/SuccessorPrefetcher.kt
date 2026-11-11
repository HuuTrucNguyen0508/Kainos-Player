package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.ResolvedPlayback
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.queue.QueueController
import com.universalmusic.player.platform.PlaybackTrace
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gets the next queue entry ready before it is needed: a local file handed to the engine for a
 * gapless join, one short-lived YouTube result, or the opening seconds of a Spotify track fetched
 * by the engine near the end of the current one. Session thread only.
 */
internal class SuccessorPrefetcher(
    private val engine: PlaybackEngine,
    private val queue: QueueController,
    private val sources: TrackSources,
    private val currentPreferences: StateFlow<PlaybackPreferences>,
    private val nowPlaying: StateFlow<NowPlayingState>,
    private val turn: PlaybackTurn,
    /** True while a sleep timer may freeze playback at the end of this track. */
    private val heldAtTrackEnd: () -> Boolean,
    private val scope: CoroutineScope,
    private val sessionContext: CoroutineContext,
    private val clock: () -> Long,
) {
    private data class Prefetched(
        val itemId: String,
        val track: Track,
        val preferences: PlaybackPreferences,
        val resolved: ResolvedPlayback,
        val validUntil: Long,
    )
    private data class PrefetchTarget(val itemId: String, val track: Track)

    private var job: Job? = null
    private var target: PrefetchTarget? = null
    private var seamlessNext: Pair<PrefetchTarget, ResolvedPlayback>? = null
    private var prefetched: Prefetched? = null
    private var prefetchedStartGeneration: Long? = null
    private var warmJob: Job? = null
    /** The entry whose Spotify start went to the engine during this turn. */
    private var warmed: PrefetchTarget? = null

    // Presentation metadata cannot change which audio we resolve.
    private fun resolutionTrack(track: Track): Track = track.copy(durationMs = null, artwork = null)

    private fun nextTarget(): PrefetchTarget? {
        val next = queue.nextIndex()?.let { queue.queue.value.items.getOrNull(it) } ?: return null
        return PrefetchTarget(next.id, resolutionTrack(next.track))
    }

    /** Reschedule only when the queue edit changed what plays next. */
    fun onQueueChanged() {
        if (nextTarget() != target) schedule(turn.generation)
    }

    /** The resolved successor the engine has just crossed into, while it is still the queue's next entry. */
    fun seamlessArrival(itemId: String?): ResolvedPlayback? {
        val ready = seamlessNext ?: return null
        return ready.second.takeIf { ready.first == nextTarget() && ready.first.itemId == itemId }
    }

    fun clearSeamless() {
        seamlessNext = null
    }

    /** Hands over a usable result for [item], whose track carries the current presentation metadata. */
    fun take(item: QueueItem): ResolvedPlayback? {
        val ready = prefetched?.takeIf {
            it.itemId == item.id && resolutionTrack(it.track) == resolutionTrack(item.track) &&
                it.preferences == currentPreferences.value && clock() < it.validUntil
        }?.resolved?.let { resolved ->
            resolved.copy(track = resolved.track.copy(durationMs = item.track.durationMs, artwork = item.track.artwork))
        }
        prefetched = null
        job?.cancel()
        return ready
    }

    fun invalidate() {
        seamlessNext = null
        engine.prepareNext(null)
        job?.cancel()
        prefetched = null
        target = null
        prefetchedStartGeneration = null
        cancelWarm()
    }

    fun markSpeculativeStart(generation: Long) {
        prefetchedStartGeneration = generation
    }

    /** True once for [generation] when it started from a speculative URL the engine then rejected. */
    fun consumeRejectedStart(generation: Long): Boolean {
        if (prefetchedStartGeneration != generation) return false
        prefetchedStartGeneration = null
        return true
    }

    /** Replans the next entry's preparation. Resolved URLs are never persisted. */
    fun schedule(generation: Long) {
        job?.cancel()
        prefetched = null
        seamlessNext = null
        engine.prepareNext(null)
        val target = nextTarget()
        this.target = target
        val next = target?.let { key -> queue.queue.value.items.firstOrNull { it.id == key.itemId } }
        if (target == null || target != warmed) {
            cancelWarm()
            if (target != null && next != null && next.track.sources.any { it.provider == ProviderId.SPOTIFY }) {
                warmJob = scope.launch(sessionContext) { warmNearTrackEnd(next, target, generation) }
            }
        }
        next ?: return
        val preferences = currentPreferences.value
        if (engine.supportsGapless && preferences.gapless && !heldAtTrackEnd() && turn.completionArmed &&
            nowPlaying.value.resolved?.source?.provider == ProviderId.LOCAL &&
            next.track.sources.any { it.provider == ProviderId.LOCAL }) {
            job = scope.launch(sessionContext) {
                try {
                    val resolved = withContext(sources.workContext) {
                        val result = sources.resolve(sources.prepareTrack(next.track), preferences)
                        result.copy(source = sources.enrich(result.track, result.source))
                    }
                    val handle = resolved.source.handle as? PlaybackHandle.Url ?: return@launch
                    if (resolved.source.provider != ProviderId.LOCAL || generation != turn.generation ||
                        nextTarget() != target || preferences != currentPreferences.value || heldAtTrackEnd()) return@launch
                    seamlessNext = target to resolved
                    engine.prepareNext(PreparedPlayback(next.id, handle, resolved.source.quality, generation))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    trace("local successor unavailable: ${error.message}")
                }
            }
            return
        }
        if (next.track.sources.isEmpty() || next.track.sources.any { it.provider != ProviderId.YOUTUBE_MUSIC }) return
        job = scope.launch(sessionContext) {
            delay(1_000)
            if (generation != turn.generation || !nowPlaying.value.isPlaying) return@launch
            try {
                val resolved = withContext(sources.workContext) {
                    val track = sources.prepareTrack(next.track)
                    val result = sources.resolve(track, preferences)
                    if (result.source.provider != ProviderId.YOUTUBE_MUSIC) return@withContext null
                    result.copy(source = sources.enrich(result.track, result.source))
                } ?: return@launch
                currentCoroutineContext().ensureActive()
                if (generation != turn.generation || nextTarget() != target || preferences != currentPreferences.value) return@launch
                val url = (resolved.source.handle as? PlaybackHandle.Url)?.url ?: return@launch
                // YouTube signed URLs normally carry epoch-seconds expiry. Leave a safety margin.
                val expires = url.substringAfter('?', "").split('&')
                    .firstOrNull { it.startsWith("expire=") }?.substringAfter('=')?.toLongOrNull()
                    ?.let { it * 1_000 - 30_000 }
                val validUntil = expires ?: (clock() + 30_000)
                if (validUntil > clock()) prefetched = Prefetched(next.id, next.track, preferences, resolved, validUntil)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                trace("prefetch unavailable: ${error.message}")
            }
        }
    }

    /** Hands the engine [next]'s Spotify track once the current one has [SPOTIFY_WARM_LEAD_MS] left. */
    private suspend fun warmNearTrackEnd(next: QueueItem, target: PrefetchTarget, generation: Long) {
        val current = queue.queue.value.current ?: return
        nowPlaying.first { now ->
            now.queueItemId == current.id && now.durationMs?.let { it - now.positionMs <= SPOTIFY_WARM_LEAD_MS } == true
        }
        if (generation != turn.generation || nextTarget() != target) return
        val trackId = try {
            withContext(sources.workContext) {
                sources.resolve(sources.prepareTrack(next.track), currentPreferences.value).source.spotifyTrackId()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            trace("Spotify warm skipped: ${error.message}")
            null
        } ?: return
        if (generation != turn.generation || nextTarget() != target) return
        // The receiver shares one cache handle per audio file, so warming the playing track would close its stream.
        if (current.track.sources.any { it.spotifyTrackId() == trackId }) return
        warmed = target
        engine.warmSpotify(trackId)
    }

    private fun cancelWarm() {
        warmJob?.cancel()
        warmJob = null
        if (warmed != null) engine.warmSpotify(null)
        warmed = null
    }

    private fun PlaybackSource.spotifyTrackId(): String? =
        (handle as? PlaybackHandle.ProviderPlayback)?.takeIf { it.provider == ProviderId.SPOTIFY }?.trackId

    private fun trace(message: String) = PlaybackTrace.log("Session", message)

    private companion object {
        const val SPOTIFY_WARM_LEAD_MS = 60_000L
    }
}
