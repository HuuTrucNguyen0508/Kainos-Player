package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.platform.monotonicElapsedRealtimeMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** True while the last [SLEEP_FADE_WINDOW_MS] are lowering the volume. */
    val fading: Boolean = false,
)

/** Common duration presets shown in Now Playing (minutes → ms). */
val SleepTimerDurationPresetsMs: List<Long> = listOf(5, 10, 15, 30, 45, 60).map { it * 60_000L }

/** The volume falls from the user's level to silence over this last stretch before the pause. */
const val SLEEP_FADE_WINDOW_MS = 30_000L

private const val FADE_STEP_MS = 250L

/** Desktop Spotify volume is a Web API call per step; fewer steps keep the fade clear of HTTP 429. */
private const val SPOTIFY_FADE_STEP_MS = 2_000L

/**
 * In-process sleep timer. Fades the engine volume over the last [SLEEP_FADE_WINDOW_MS]
 * through [PlayerSession.setSleepFadeGain] (the user's volume setting never moves), pauses
 * via [PlayerSession.pauseTransport] so notification / Bluetooth agree, then restores the
 * volume. Uses a monotonic clock; state is not persisted, so process death cancels the
 * timer (no surprise pause after reboot).
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
    private var endOfTrackItemId: String? = null

    fun startDuration(durationMs: Long) {
        val clamped = durationMs.coerceAtLeast(1_000L)
        stop()
        val deadline = clockMs() + clamped
        val fadeWindow = minOf(SLEEP_FADE_WINDOW_MS, clamped)
        _state.value = SleepTimerState(
            active = true,
            mode = SleepTimerMode.Duration(clamped),
            remainingMs = clamped,
        )
        timerJob = scope.launch {
            while (true) {
                val remaining = deadline - clockMs()
                if (remaining <= 0L) break
                val fading = remaining <= fadeWindow
                if (fading) player.setSleepFadeGain(remaining.toFloat() / fadeWindow)
                _state.update { current ->
                    if (!current.active) current else current.copy(remainingMs = remaining, fading = fading)
                }
                val untilFade = remaining - fadeWindow
                delay(if (untilFade > 0L) minOf(1_000L, untilFade) else minOf(fadeStepMs(), remaining))
            }
            if (_state.value.active && _state.value.mode is SleepTimerMode.Duration) {
                stop(pause = true)
            }
        }
    }

    fun startEndOfTrack() {
        val now = player.nowPlaying.value
        val itemId = now.queueItemId ?: return
        stop()
        endOfTrackItemId = itemId
        val armRemaining = now.durationMs?.takeIf { it > 0L }?.let { it - now.positionMs }
        val fadeWindow = minOf(SLEEP_FADE_WINDOW_MS, armRemaining ?: SLEEP_FADE_WINDOW_MS).coerceAtLeast(1L)
        _state.value = SleepTimerState(
            active = true,
            mode = SleepTimerMode.EndOfTrack,
            remainingMs = null,
        )
        player.setNaturalCompletionGate {
            val watched = endOfTrackItemId
            val current = player.nowPlaying.value.queueItemId
            if (watched != null && watched == current) {
                stop()
                true
            } else {
                false
            }
        }
        watchJob = scope.launch {
            var lastStepAtMs: Long? = null
            player.nowPlaying.collect { playing ->
                val watched = endOfTrackItemId ?: return@collect
                if (playing.queueItemId != watched) {
                    // Skip / jump away: do not pause; cancel end-of-track mode.
                    cancel()
                    return@collect
                }
                val duration = playing.durationMs?.takeIf { it > 0L } ?: return@collect
                val remaining = (duration - playing.positionMs).coerceAtLeast(0L)
                val fading = remaining <= fadeWindow
                if (!fading && !_state.value.fading) return@collect
                // Position ticks arrive every ~400 ms; Spotify steps are spaced further apart.
                val at = clockMs()
                if (fading && lastStepAtMs?.let { at - it < fadeStepMs() } == true) return@collect
                lastStepAtMs = at
                player.setSleepFadeGain(if (fading) remaining.toFloat() / fadeWindow else 1f)
                _state.update { current -> if (!current.active) current else current.copy(fading = fading) }
            }
        }
    }

    /** Replace the timer with a new duration budget from now. */
    fun adjustDuration(remainingMs: Long) = startDuration(remainingMs)

    /** Stop the timer without pausing; a fade in progress returns to full volume at once. */
    fun cancel() = stop()

    private fun stop(pause: Boolean = false) {
        timerJob?.cancel()
        timerJob = null
        watchJob?.cancel()
        watchJob = null
        player.setNaturalCompletionGate(null)
        endOfTrackItemId = null
        _state.value = SleepTimerState()
        // Pause first, so the restored volume reaches the engine after it has stopped sounding.
        if (pause) player.pauseForSleepTimer()
        player.setSleepFadeGain(1f)
    }

    private fun fadeStepMs(): Long =
        if (player.nowPlaying.value.resolved?.source?.provider == ProviderId.SPOTIFY) SPOTIFY_FADE_STEP_MS else FADE_STEP_MS
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
