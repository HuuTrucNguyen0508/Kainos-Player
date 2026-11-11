package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ResolvedPlayback
import com.universalmusic.player.domain.model.SourceFallbackEvent
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.PlaybackTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Where a starting track must begin. The latest accepted seek overrides it until the engine has started. */
internal class StartupTarget(var positionMs: Long?)

internal fun Track?.traceLabel(): String =
    if (this == null) "<none>" else "'$title' [${sources.firstOrNull()?.provider}] $canonicalId"

/**
 * Walks one queue entry to engine playback: prepare, resolve, enrich, play, then each fallback
 * source. Runs on the session thread, leaves it only through [TrackSources.workContext], and
 * re-checks the generation after every step that left.
 */
internal class TrackStarter(
    private val engine: PlaybackEngine,
    private val sources: TrackSources,
    private val currentPreferences: StateFlow<PlaybackPreferences>,
    private val engineVolume: () -> Float,
    private val nowPlaying: MutableStateFlow<NowPlayingState>,
    private val turn: PlaybackTurn,
    private val prefetcher: SuccessorPrefetcher,
    private val isFavorite: (canonicalId: String) -> Boolean,
    private val onEngineStarted: (StartupTarget?) -> Unit,
    /** The engine refused a speculative URL; the entry must start again from this position. */
    private val onSpeculativeUrlRejected: (resumeAtMs: Long?) -> Unit,
    private val onTerminalFailure: (generation: Long) -> Unit,
    /** The last source failed on a provider rate limit: hold the entry instead of moving on. */
    private val onRateLimited: (generation: Long, target: StartupTarget?) -> Unit,
) {
    suspend fun start(
        track: Track,
        queueItemId: String,
        generation: Long,
        target: StartupTarget,
        ready: ResolvedPlayback? = null,
    ) {
        currentCoroutineContext().ensureActive()
        if (generation != turn.generation) return
        if (ready != null) {
            startResolved(ready, fallback = null, generation = generation, target = target, alreadyEnriched = true)
            return
        }
        val playable = withContext(sources.workContext) { sources.prepareTrack(track) }
        if (generation != turn.generation) return
        nowPlaying.update {
            it.copy(
                track = playable,
                queueItemId = queueItemId,
                resolved = null,
                isPlaying = false,
                positionMs = target.positionMs?.coerceAtLeast(0L) ?: 0,
                durationMs = playable.durationMs,
                buffering = true,
                error = null,
                fallback = null,
                favorite = isFavorite(playable.canonicalId),
                syncWarning = null,
            )
        }
        val preferences = currentPreferences.value
        val resolved = runCatching { withContext(sources.workContext) { sources.resolve(playable, preferences) } }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                if (generation != turn.generation) return
                currentCoroutineContext().ensureActive()
                trace("resolve failed for ${playable.traceLabel()} gen=$generation: ${error.message}")
                nowPlaying.update { it.copy(buffering = false, error = error.message, isPlaying = false) }
                val rateLimit = error.rateLimitCause()
                if (rateLimit != null) {
                    nowPlaying.update { it.copy(error = "${rateLimit.message} Playback paused; press Play to try again.") }
                    onRateLimited(generation, target)
                } else onTerminalFailure(generation)
                return
            }
        if (generation != turn.generation) return
        currentCoroutineContext().ensureActive()
        startResolved(resolved, fallback = null, generation = generation, target = target)
    }

    private suspend fun startResolved(
        resolved: ResolvedPlayback,
        fallback: SourceFallbackEvent?,
        generation: Long,
        target: StartupTarget?,
        alreadyEnriched: Boolean = false,
    ) {
        if (generation != turn.generation) return
        currentCoroutineContext().ensureActive()
        val enrichedSource = runCatching { withContext(sources.workContext) { if (alreadyEnriched) resolved.source else sources.enrich(resolved.track, resolved.source) } }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                if (generation != turn.generation) return
                currentCoroutineContext().ensureActive()
                trace("enrich failed for ${resolved.track.traceLabel()} via ${resolved.source.provider}: ${error.message}")
                tryFallback(resolved, error.message, generation, target, rateLimit = error.rateLimitCause())
                return
            }
        if (generation != turn.generation) {
            trace("startResolved dropped after enrich: gen=$generation current=${turn.generation}")
            return
        }
        val playable = resolved.copy(source = enrichedSource)
        currentCoroutineContext().ensureActive()
        nowPlaying.update {
            it.copy(
                track = playable.track,
                resolved = playable,
                durationMs = playable.track.durationMs ?: it.durationMs,
                buffering = true,
                fallback = fallback,
                error = null,
                favorite = isFavorite(playable.track.canonicalId),
                levelling = null,
            )
        }
        trace("engine.play ${playable.track.traceLabel()} via ${playable.source.provider} gen=$generation")
        runCatching {
            withContext(sources.workContext) {
                val handle = playable.source.handle.let { handle ->
                    if (handle is PlaybackHandle.Url)
                        handle.copy(startPositionMs = target?.positionMs ?: 0, durationMs = playable.track.durationMs) else handle
                }
                engine.configureGapless(currentPreferences.value.gapless && playable.source.provider == ProviderId.LOCAL)
                engine.configureLevelling(currentPreferences.value.volumeLevelling)
                engine.play(handle, playable.source.quality, playGeneration = generation)
            }
            if (generation == turn.generation) {
                engine.setVolume(engineVolume())
                target?.positionMs?.let(engine::seekTo)
                onEngineStarted(target)
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                trace("engine.play cancelled gen=$generation (current=${turn.generation})")
                throw error
            }
            if (generation != turn.generation) return
            currentCoroutineContext().ensureActive()
            trace("engine.play failed gen=$generation: ${error.message}")
            tryFallback(playable, error.message, generation, target, rateLimit = error.rateLimitCause())
            return
        }
        if (generation != turn.generation) {
            trace("engine.play returned for superseded gen=$generation current=${turn.generation}; not arming")
            return
        }
        turn.arm()
        trace("engine.play ok gen=$generation; completion armed")
        prefetcher.schedule(generation)
    }

    suspend fun tryFallback(
        current: ResolvedPlayback,
        reason: String?,
        generation: Long,
        target: StartupTarget?,
        rateLimit: ProviderRateLimitedException? = null,
    ) {
        if (generation != turn.generation) return
        if (prefetcher.consumeRejectedStart(generation)) {
            // A speculative URL can be rejected early. Resolve this same entry normally once.
            onSpeculativeUrlRejected(target?.positionMs ?: nowPlaying.value.positionMs.takeIf { it > 0 })
            return
        }
        trace(
            "tryFallback from ${current.source.provider} reason=$reason remaining=" +
                current.fallbacks.map { it.provider },
        )
        val next = current.fallbacks.firstOrNull() ?: run {
            nowPlaying.update {
                it.copy(
                    buffering = false,
                    isPlaying = false,
                    error = when {
                        rateLimit != null -> "${rateLimit.message} Playback paused; press Play to try again."
                        else -> reason ?: "Playback failed and no fallback is available"
                    },
                )
            }
            if (rateLimit != null) onRateLimited(generation, target) else onTerminalFailure(generation)
            return
        }
        val remaining = current.fallbacks.drop(1)
        val event = SourceFallbackEvent(
            from = current.source.provider,
            to = next.provider,
            message = "${current.source.provider.displayName} unavailable → ${next.provider.displayName}",
        )
        startResolved(
            resolved = current.copy(source = next, fallbacks = remaining, reason = event.message),
            fallback = event,
            generation = generation,
            target = target,
        )
    }

    private fun trace(message: String) = PlaybackTrace.log("Session", message)
}
