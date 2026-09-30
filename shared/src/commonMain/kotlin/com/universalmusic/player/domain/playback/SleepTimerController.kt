package com.universalmusic.player.domain.playback

import com.universalmusic.player.platform.monotonicElapsedRealtimeMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/** How the sleep timer decides when to pause. */
sealed class SleepTimerMode {
    /** Pause after [durationMs] of process-monotonic time. */
    data class Duration(val durationMs: Long) : SleepTimerMode()

    /**
     * Pause when the track that was current at arm-time ends naturally.
     * A manual skip does not pause; it cancels this mode.
     */
    data object EndOfTrack : SleepTimerMode()
}

data class SleepTimerState(
    val active: Boolean = false,
    val mode: SleepTimerMode? = null,
    /** Remaining wall for [SleepTimerMode.Duration]; null when inactive or end-of-track. */
    val remainingMs: Long? = null,
)

/** Common duration presets shown in Now Playing (minutes → ms). */
val SleepTimerDurationPresetsMs: List<Long> = listOf(5, 10, 15, 30, 45, 60).map { it * 60_000L }

/**
 * In-process sleep timer. Pauses via [PlayerSession.pauseTransport] so notification /
 * Bluetooth agree. Uses a monotonic clock; state is not persisted, so process death
 * cancels the timer (no surprise pause after reboot).
 */
class SleepTimerController(
    private val player: PlayerSession,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = ::monotonicElapsedRealtimeMs,
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var watchJob: Job? = null
    private var deadlineMonotonicMs: Long? = null
    private var endOfTrackItemId: String? = null

    fun startDuration(durationMs: Long) {
        val clamped = durationMs.coerceAtLeast(1_000L)
        clearJobsAndGate()
        endOfTrackItemId = null
        val deadline = clockMs() + clamped
        deadlineMonotonicMs = deadline
        _state.value = SleepTimerState(
            active = true,
            mode = SleepTimerMode.Duration(clamped),
            remainingMs = clamped,
        )
        timerJob = scope.launch {
            while (true) {
                val remaining = (deadlineMonotonicMs ?: deadline) - clockMs()
                if (remaining <= 0L) break
                _state.update { current ->
                    if (!current.active) current else current.copy(remainingMs = remaining)
                }
                delay(minOf(1_000L, remaining))
            }
            if (_state.value.active && _state.value.mode is SleepTimerMode.Duration) {
                firePause()
            }
        }
    }

    fun startEndOfTrack() {
        val itemId = player.nowPlaying.value.queueItemId
        if (itemId == null) return
        clearJobsAndGate()
        deadlineMonotonicMs = null
        endOfTrackItemId = itemId
        _state.value = SleepTimerState(
            active = true,
            mode = SleepTimerMode.EndOfTrack,
            remainingMs = null,
        )
        player.setNaturalCompletionGate {
            val watched = endOfTrackItemId
            val current = player.nowPlaying.value.queueItemId
            if (watched != null && watched == current) {
                clearAfterFire()
                true
            } else {
                false
            }
        }
        watchJob = scope.launch {
            player.nowPlaying
                .map { it.queueItemId }
                .distinctUntilChanged()
                .collect { currentId ->
                    val watched = endOfTrackItemId ?: return@collect
                    if (currentId != watched) {
                        // Skip / jump away: do not pause; cancel end-of-track mode.
                        cancel()
                    }
                }
        }
    }

    /** Replace an active duration timer with a new remaining budget; no-op if inactive. */
    fun adjustDuration(remainingMs: Long) {
        if (!_state.value.active) {
            startDuration(remainingMs)
            return
        }
        startDuration(remainingMs)
    }

    fun cancel() {
        clearJobsAndGate()
        endOfTrackItemId = null
        deadlineMonotonicMs = null
        _state.value = SleepTimerState()
    }

    private fun firePause() {
        clearJobsAndGate()
        endOfTrackItemId = null
        deadlineMonotonicMs = null
        _state.value = SleepTimerState()
        player.pauseTransport()
    }

    private fun clearAfterFire() {
        clearJobsAndGate()
        endOfTrackItemId = null
        deadlineMonotonicMs = null
        _state.value = SleepTimerState()
    }

    private fun clearJobsAndGate() {
        timerJob?.cancel()
        timerJob = null
        watchJob?.cancel()
        watchJob = null
        player.setNaturalCompletionGate(null)
    }
}

fun formatSleepTimerRemaining(remainingMs: Long): String {
    val totalSec = max(0L, (remainingMs + 999L) / 1_000L)
    val hours = totalSec / 3_600L
    val minutes = (totalSec % 3_600L) / 60L
    val seconds = totalSec % 60L
    fun two(n: Long): String = if (n < 10L) "0$n" else "$n"
    return if (hours > 0L) {
        "$hours:${two(minutes)}:${two(seconds)}"
    } else {
        "$minutes:${two(seconds)}"
    }
}
