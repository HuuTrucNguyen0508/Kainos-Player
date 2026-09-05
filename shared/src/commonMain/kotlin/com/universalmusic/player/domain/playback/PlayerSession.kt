package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.ResolvedPlayback
import com.universalmusic.player.domain.model.SourceFallbackEvent
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.queue.QueueController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NowPlayingState(
    val track: Track? = null,
    val resolved: ResolvedPlayback? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val buffering: Boolean = false,
    val favorite: Boolean = false,
    val fallback: SourceFallbackEvent? = null,
    val error: String? = null,
)

class PlayerSession(
    private val engine: PlaybackEngine,
    private val resolver: SourceResolver,
    private val scope: CoroutineScope,
    val queue: QueueController = QueueController(),
    initialPreferences: PlaybackPreferences = PlaybackPreferences.Default,
) {
    private val _nowPlaying = MutableStateFlow(NowPlayingState())
    val nowPlaying: StateFlow<NowPlayingState> = _nowPlaying.asStateFlow()

    private val _preferences = MutableStateFlow(initialPreferences)
    val preferences: StateFlow<PlaybackPreferences> = _preferences.asStateFlow()

    private var playJob: Job? = null

    init {
        scope.launch {
            engine.state.collectLatest { engineState ->
                _nowPlaying.update { current ->
                    current.copy(
                        isPlaying = engineState.status == EngineStatus.PLAYING,
                        positionMs = engineState.positionMs,
                        durationMs = engineState.durationMs ?: current.track?.durationMs,
                        buffering = engineState.status == EngineStatus.BUFFERING ||
                            (current.buffering && current.resolved == null &&
                                engineState.status == EngineStatus.IDLE),
                        error = engineState.error,
                    )
                }
                if (engineState.status == EngineStatus.ENDED) {
                    skipToNext()
                }
                if (engineState.status == EngineStatus.FAILED) {
                    val current = _nowPlaying.value.resolved
                    if (current != null) {
                        playJob?.cancel()
                        playJob = scope.launch { tryFallback(current) }
                    }
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

    fun togglePlayPause() {
        if (_nowPlaying.value.buffering) {
            playJob?.cancel()
            engine.stop()
            _nowPlaying.update { it.copy(buffering = false, isPlaying = false) }
            return
        }
        val state = engine.state.value
        when (state.status) {
            EngineStatus.PLAYING -> engine.pause()
            EngineStatus.PAUSED -> engine.resume()
            EngineStatus.IDLE, EngineStatus.ENDED, EngineStatus.FAILED -> startCurrent()
            EngineStatus.BUFFERING -> engine.pause()
        }
    }

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    fun skipToNext() {
        val next = queue.nextIndex()
        if (next == null) {
            _nowPlaying.update {
                it.copy(isPlaying = false, buffering = false, positionMs = 0)
            }
            return
        }
        queue.jumpTo(next)
        startCurrent()
    }

    fun skipToPrevious() {
        val current = _nowPlaying.value
        if (current.positionMs > 3_000) {
            engine.seekTo(0)
            return
        }
        val previous = queue.previousIndex() ?: return
        queue.jumpTo(previous)
        startCurrent()
    }

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

    private fun startCurrent(): Job? {
        val item = queue.queue.value.current ?: return null
        playJob?.cancel()
        engine.stop()
        playJob = scope.launch {
            playTrack(item.track)
        }
        return playJob
    }

    private suspend fun playTrack(track: Track) {
        currentCoroutineContext().ensureActive()
        _nowPlaying.update {
            it.copy(track = track, resolved = null, isPlaying = false, positionMs = 0,
                buffering = true, error = null, fallback = null)
        }
        val resolved = runCatching { resolver.resolve(track, _preferences.value) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                _nowPlaying.update { it.copy(buffering = false, error = error.message, isPlaying = false) }
                return
            }
        currentCoroutineContext().ensureActive()
        startResolved(resolved, fallback = null)
    }

    private suspend fun startResolved(resolved: ResolvedPlayback, fallback: SourceFallbackEvent?) {
        currentCoroutineContext().ensureActive()
        _nowPlaying.update {
            it.copy(
                track = resolved.track,
                resolved = resolved,
                buffering = true,
                fallback = fallback,
                error = null,
            )
        }
        runCatching {
            engine.play(resolved.source.handle, resolved.source.quality)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            tryFallback(resolved, error.message)
        }
    }

    private suspend fun tryFallback(current: ResolvedPlayback, reason: String? = null) {
        val next = current.fallbacks.firstOrNull() ?: run {
            _nowPlaying.update {
                it.copy(
                    buffering = false,
                    isPlaying = false,
                    error = reason ?: "Playback failed and no fallback is available",
                )
            }
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
        )
    }
}

fun PlaybackSource.providerOrNull(): ProviderId = provider
