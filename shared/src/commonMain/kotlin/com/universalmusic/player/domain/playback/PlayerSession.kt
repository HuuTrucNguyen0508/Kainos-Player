package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.ResolvedPlayback
import com.universalmusic.player.domain.model.SourceFallbackEvent
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.queue.QueueController
import com.universalmusic.player.platform.PlaybackTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NowPlayingState(
    val track: Track? = null,
    val queueItemId: String? = null,
    val resolved: ResolvedPlayback? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val buffering: Boolean = false,
    val favorite: Boolean = false,
    val fallback: SourceFallbackEvent? = null,
    val error: String? = null,
)

/**
 * Owns queue mutations and playback transitions. Engines report status; this session decides
 * when to advance, stop, or ignore stale completion from a superseded play request.
 */
class PlayerSession(
    private val engine: PlaybackEngine,
    private val resolver: SourceResolver,
    private val scope: CoroutineScope,
    val queue: QueueController = QueueController(),
    initialPreferences: PlaybackPreferences = PlaybackPreferences.Default,
    private val enrichSource: suspend (Track, PlaybackSource) -> PlaybackSource = { _, source -> source },
    private val isFavorite: (canonicalId: String) -> Boolean = { false },
    /** Merge hearted audio cache / other offline sources before resolution. */
    private val prepareTrack: (Track) -> Track = { it },
    /**
     * Natural completion at queue end may request one continuation batch.
     * Empty / null means stop cleanly; callers must append only at the tail.
     */
    private val onQueueExhausted: (suspend (excludeCanonicalIds: Set<String>) -> List<Track>)? = null,
) {
    private val _nowPlaying = MutableStateFlow(NowPlayingState())
    val nowPlaying: StateFlow<NowPlayingState> = _nowPlaying.asStateFlow()

    private val _preferences = MutableStateFlow(initialPreferences)
    val preferences: StateFlow<PlaybackPreferences> = _preferences.asStateFlow()

    private var playJob: Job? = null
    /** Bumped on every play/stop transition so late ENDED/FAILED from a prior track are ignored. */
    private var playGeneration: Long = 0L
    /** True only after the current generation has successfully started engine playback. */
    private var completionArmed: Boolean = false
    /** Last engine status seen by the collector; trace only on change, never per position tick. */
    private var lastTracedStatus: EngineStatus? = null

    private fun trace(message: String) = PlaybackTrace.log("Session", message)

    private fun Track?.label(): String =
        if (this == null) "<none>" else "'$title' [${sources.firstOrNull()?.provider}] $canonicalId"

    init {
        scope.launch {
            engine.state.collectLatest { engineState ->
                val eventGeneration = engineState.playGeneration
                if (engineState.status != lastTracedStatus) {
                    lastTracedStatus = engineState.status
                    trace(
                        "engine -> ${engineState.status} gen=$eventGeneration (session gen=$playGeneration " +
                            "armed=$completionArmed) pos=${engineState.positionMs} dur=${engineState.durationMs}" +
                            (engineState.error?.let { " error=$it" } ?: ""),
                    )
                }
                // Reject stale engine events before touching nowPlaying.
                if (eventGeneration != playGeneration) {
                    if (engineState.status == EngineStatus.ENDED || engineState.status == EngineStatus.FAILED) {
                        trace("dropped stale ${engineState.status} gen=$eventGeneration current=$playGeneration")
                    }
                    return@collectLatest
                }

                _nowPlaying.update { current ->
                    current.copy(
                        isPlaying = engineState.status == EngineStatus.PLAYING,
                        positionMs = engineState.positionMs,
                        durationMs = engineState.durationMs ?: current.track?.durationMs ?: current.durationMs,
                        buffering = engineState.status == EngineStatus.BUFFERING ||
                            (current.buffering && current.resolved == null &&
                                engineState.status == EngineStatus.IDLE),
                        error = if (engineState.status == EngineStatus.FAILED) {
                            engineState.error
                        } else {
                            current.error
                        },
                    )
                }

                if (eventGeneration != playGeneration) return@collectLatest
                when (engineState.status) {
                    EngineStatus.ENDED -> {
                        if (!completionArmed) {
                            trace("ENDED ignored: completion not armed gen=$eventGeneration")
                            return@collectLatest
                        }
                        if (eventGeneration != playGeneration) return@collectLatest
                        completionArmed = false
                        trace("natural completion of ${_nowPlaying.value.track.label()}")
                        handleNaturalCompletion(eventGeneration)
                    }
                    EngineStatus.FAILED -> {
                        if (eventGeneration != playGeneration) return@collectLatest
                        if (!completionArmed && _nowPlaying.value.resolved == null) {
                            trace("FAILED ignored: nothing resolved gen=$eventGeneration error=${engineState.error}")
                            return@collectLatest
                        }
                        completionArmed = false
                        val current = _nowPlaying.value.resolved ?: return@collectLatest
                        val reason = engineState.error
                        trace("engine FAILED for ${current.track.label()}: $reason -> fallback")
                        playJob?.cancel()
                        playJob = scope.launch { tryFallback(current, reason, eventGeneration) }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun updatePreferences(preferences: PlaybackPreferences) {
        _preferences.value = preferences
    }

    fun play(track: Track) {
        queue.playNow(track)
        startCurrent()
    }

    suspend fun playAwait(track: Track) {
        queue.playNow(track)
        startCurrent()?.join()
    }

    fun play(tracks: List<Track>, startIndex: Int = 0) {
        queue.playNow(tracks, startIndex)
        startCurrent()
    }

    /** Play the already-queued item at [index] without rebuilding the queue. */
    fun playQueueIndex(index: Int) {
        queue.jumpTo(index)
        startCurrent()
    }

    fun addToQueue(track: Track) = queue.addToQueue(track)

    fun playNext(track: Track) = queue.playNext(track)

    fun clearQueue() {
        trace("clearQueue")
        beginTransition()
        playJob?.cancel()
        playJob = null
        queue.clear()
        engine.stop()
        _nowPlaying.value = NowPlayingState()
    }

    fun removeFromQueue(itemId: String) {
        val before = queue.queue.value
        val removingCurrent = before.current?.id == itemId
        queue.remove(itemId)
        val after = queue.queue.value
        when {
            after.items.isEmpty() -> clearQueue()
            removingCurrent -> startCurrent()
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        queue.move(from, to)
    }

    fun moveInPlaybackOrder(fromOrderPos: Int, toOrderPos: Int) {
        queue.moveInPlaybackOrder(fromOrderPos, toOrderPos)
    }

    fun togglePlayPause() {
        val now = _nowPlaying.value
        trace("togglePlayPause buffering=${now.buffering} isPlaying=${now.isPlaying}")
        when {
            now.buffering || now.isPlaying -> pauseTransport()
            else -> playTransport()
        }
    }

    /** Idempotent pause; cancels in-flight resolve/buffering. */
    fun pauseTransport() {
        val engineStatus = engine.state.value.status
        if (_nowPlaying.value.buffering) {
            trace(
                "pauseTransport while buffering -> cancel start of ${_nowPlaying.value.track.label()} " +
                    "(engine=$engineStatus gen=$playGeneration)",
            )
            beginTransition()
            playJob?.cancel()
            playJob = null
            engine.stop()
            _nowPlaying.update { it.copy(buffering = false, isPlaying = false) }
            return
        }
        trace("pauseTransport engine=$engineStatus gen=$playGeneration track=${_nowPlaying.value.track.label()}")
        when (engineStatus) {
            EngineStatus.PLAYING, EngineStatus.BUFFERING -> engine.pause()
            else -> Unit
        }
    }

    /** Idempotent play/resume; no-op when already playing or buffering. */
    fun playTransport() {
        val now = _nowPlaying.value
        trace("playTransport buffering=${now.buffering} isPlaying=${now.isPlaying} engine=${engine.state.value.status}")
        if (now.buffering || now.isPlaying) return
        when (engine.state.value.status) {
            EngineStatus.PAUSED -> engine.resume()
            EngineStatus.IDLE, EngineStatus.ENDED, EngineStatus.FAILED -> startCurrent()
            EngineStatus.PLAYING, EngineStatus.BUFFERING -> Unit
        }
    }

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    /** Manual next: escapes Repeat One. */
    fun skipToNext() {
        trace("skipToNext")
        advance(manual = true)
    }

    fun skipToPrevious() {
        val current = _nowPlaying.value
        trace("skipToPrevious pos=${current.positionMs}")
        if (current.positionMs > 3_000) {
            engine.seekTo(0)
            return
        }
        val previous = queue.previousIndex() ?: return
        queue.jumpTo(previous)
        startCurrent()
    }

    fun canSkipNext(): Boolean = queue.nextIndex(respectRepeatOne = false) != null

    fun toggleShuffle() {
        val enabled = !queue.queue.value.shuffle
        queue.setShuffle(enabled)
    }

    fun cycleRepeat() {
        val next = when (queue.queue.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        queue.setRepeat(next)
    }

    fun setFavorite(favorite: Boolean) {
        _nowPlaying.update { it.copy(favorite = favorite) }
    }

    private fun handleNaturalCompletion(generation: Long) {
        if (generation != playGeneration) return
        advance(manual = false, fromGeneration = generation)
    }

    private fun advance(manual: Boolean, fromGeneration: Long? = null) {
        if (fromGeneration != null && fromGeneration != playGeneration) {
            trace("advance dropped: gen=$fromGeneration current=$playGeneration")
            return
        }
        val snapshot = queue.queue.value
        val next = queue.nextIndex(respectRepeatOne = !manual)
        trace(
            "advance manual=$manual current=${snapshot.currentIndex} next=$next size=${snapshot.items.size} " +
                "repeat=${snapshot.repeat} shuffle=${snapshot.shuffle}",
        )
        if (next == null) {
            if (!manual && onQueueExhausted != null) {
                val generationAtExhaustion = playGeneration
                playJob?.cancel()
                playJob = scope.launch {
                    tryQueueContinuation(generationAtExhaustion)
                }
                return
            }
            stopAtQueueEnd()
            return
        }
        queue.jumpTo(next)
        startCurrent()
    }

    private suspend fun tryQueueContinuation(generationAtExhaustion: Long) {
        if (generationAtExhaustion != playGeneration) return
        val exclude = queue.queue.value.items.map { it.track.canonicalId }.toSet()
        val more = runCatching { onQueueExhausted?.invoke(exclude).orEmpty() }
            .getOrDefault(emptyList())
        if (generationAtExhaustion != playGeneration) return
        if (more.isEmpty()) {
            stopAtQueueEnd()
            return
        }
        queue.appendTracks(more)
        val next = queue.nextIndex(respectRepeatOne = false)
        if (next == null) {
            stopAtQueueEnd()
            return
        }
        queue.jumpTo(next)
        startCurrent()
    }

    private fun stopAtQueueEnd() {
        trace("stopAtQueueEnd")
        beginTransition()
        playJob?.cancel()
        playJob = null
        engine.stop()
        _nowPlaying.update {
            it.copy(isPlaying = false, buffering = false, positionMs = 0, error = null)
        }
    }

    private fun beginTransition() {
        playGeneration++
        completionArmed = false
    }

    private fun startCurrent(): Job? {
        val snapshot = queue.queue.value
        val item = snapshot.current ?: return null
        beginTransition()
        val generation = playGeneration
        trace("startCurrent ${item.track.label()} gen=$generation (cancel previous job + engine.stop)")
        playJob?.cancel()
        engine.stop()
        playJob = scope.launch {
            playTrack(item.track, item.id, generation)
        }
        return playJob
    }

    private suspend fun playTrack(track: Track, queueItemId: String, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (generation != playGeneration) return
        val playable = prepareTrack(track)
        _nowPlaying.update {
            it.copy(
                track = playable,
                queueItemId = queueItemId,
                resolved = null,
                isPlaying = false,
                positionMs = 0,
                durationMs = playable.durationMs,
                buffering = true,
                error = null,
                fallback = null,
                favorite = isFavorite(playable.canonicalId),
            )
        }
        val resolved = runCatching { resolver.resolve(playable, _preferences.value) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                if (generation != playGeneration) return
                currentCoroutineContext().ensureActive()
                trace("resolve failed for ${playable.label()} gen=$generation: ${error.message}")
                _nowPlaying.update { it.copy(buffering = false, error = error.message, isPlaying = false) }
                advanceAfterTerminalFailure(generation)
                return
            }
        if (generation != playGeneration) return
        currentCoroutineContext().ensureActive()
        startResolved(resolved, fallback = null, generation = generation)
    }

    private suspend fun startResolved(
        resolved: ResolvedPlayback,
        fallback: SourceFallbackEvent?,
        generation: Long,
    ) {
        if (generation != playGeneration) return
        currentCoroutineContext().ensureActive()
        val enrichedSource = runCatching { enrichSource(resolved.track, resolved.source) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                if (generation != playGeneration) return
                currentCoroutineContext().ensureActive()
                trace("enrich failed for ${resolved.track.label()} via ${resolved.source.provider}: ${error.message}")
                tryFallback(resolved, error.message, generation)
                return
            }
        if (generation != playGeneration) {
            trace("startResolved dropped after enrich: gen=$generation current=$playGeneration")
            return
        }
        val playable = resolved.copy(source = enrichedSource)
        currentCoroutineContext().ensureActive()
        _nowPlaying.update {
            it.copy(
                track = playable.track,
                resolved = playable,
                durationMs = playable.track.durationMs ?: it.durationMs,
                buffering = true,
                fallback = fallback,
                error = null,
                favorite = isFavorite(playable.track.canonicalId),
            )
        }
        trace("engine.play ${playable.track.label()} via ${playable.source.provider} gen=$generation")
        runCatching {
            engine.play(playable.source.handle, playable.source.quality, playGeneration = generation)
        }.onFailure { error ->
            if (error is CancellationException) {
                trace("engine.play cancelled gen=$generation (current=$playGeneration)")
                throw error
            }
            if (generation != playGeneration) return
            currentCoroutineContext().ensureActive()
            trace("engine.play failed gen=$generation: ${error.message}")
            tryFallback(playable, error.message, generation)
            return
        }
        if (generation != playGeneration) {
            trace("engine.play returned for superseded gen=$generation current=$playGeneration; not arming")
            return
        }
        completionArmed = true
        trace("engine.play ok gen=$generation; completion armed")
    }

    private suspend fun tryFallback(current: ResolvedPlayback, reason: String? = null, generation: Long) {
        if (generation != playGeneration) return
        trace(
            "tryFallback from ${current.source.provider} reason=$reason remaining=" +
                current.fallbacks.map { it.provider },
        )
        val next = current.fallbacks.firstOrNull() ?: run {
            _nowPlaying.update {
                it.copy(
                    buffering = false,
                    isPlaying = false,
                    error = reason ?: "Playback failed and no fallback is available",
                )
            }
            advanceAfterTerminalFailure(generation)
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
        )
    }

    /** Skip a dead track when the queue still has something after it. */
    private fun advanceAfterTerminalFailure(generation: Long) {
        if (generation != playGeneration) return
        if (queue.nextIndex(respectRepeatOne = false) == null) return
        advance(manual = false, fromGeneration = generation)
    }
}

fun PlaybackSource.providerOrNull(): ProviderId = provider
