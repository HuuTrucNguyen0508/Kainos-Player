package com.universalmusic.player.platform

/**
 * librespot 0.2.0 runs its own follow-up tasks after Kainos has already moved on, and on Android
 * an uncaught exception in one kills the app:
 * - With autoplay off, when a one-track context ends, its own advance re-arms the entry that
 *   already holds the output and throws on a pooled `player-queue` thread. Every Spotify track
 *   end looked like a pause after a restart.
 * - When a track is skipped while it is still loading, the closed session's load retry swaps
 *   into a queue whose executor is already terminated (phone trace 2026-11-04 21:37:13).
 * - On a PANIC, librespot releases the AudioTrack from the failing thread while the
 *   `player-audio-sink` thread is still running, and that thread's next `stop()` hits the
 *   released track (phone trace 2026-11-09 10:45:01). Only that player's audio thread dies;
 *   [onAudioSinkDied] tells the host to rebuild the player before it loads the next track.
 * Kainos loads the next track itself in each case, so these failures are logged and ignored.
 */
internal fun installLibrespotStaleTaskGuard(onAudioSinkDied: () -> Unit = {}) {
    val current = Thread.getDefaultUncaughtExceptionHandler()
    if (current is StaleTaskGuard) current.onAudioSinkDied = onAudioSinkDied
    else Thread.setDefaultUncaughtExceptionHandler(StaleTaskGuard(current, onAudioSinkDied))
}

private class StaleTaskGuard(
    private val delegate: Thread.UncaughtExceptionHandler?,
    @Volatile var onAudioSinkDied: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        when {
            error.isEndOfContextAdvance() ->
                PlaybackTrace.log("Librespot", "end of context: librespot's own advance failed; Kainos loads the next track")
            error.isClosedSessionLoadRetry() ->
                PlaybackTrace.log("Librespot", "closed session: librespot's load retry was rejected; Kainos already moved on")
            error.isReleasedAudioTrack() -> {
                PlaybackTrace.log("Librespot", "audio sink stopped a released AudioTrack after a PANIC; the receiver is rebuilt before the next track")
                onAudioSinkDied()
            }
            else -> delegate?.uncaughtException(thread, error)
        }
    }
}

private fun Throwable.isEndOfContextAdvance(): Boolean =
    this is IllegalStateException &&
        message.orEmpty().startsWith("Cannot set output for PlayerQueueEntry") &&
        hasFrame(PLAYER_SESSION) { it == "advance" } &&
        hasFrame(PLAYER_SESSION) { it == "playbackEnded" }

/** A running executor would accept the task, so a rejection names a shut-down queue. */
private fun Throwable.isClosedSessionLoadRetry(): Boolean =
    this is java.util.concurrent.RejectedExecutionException &&
        message.orEmpty().startsWith("Task PlayerQueueEntry") &&
        !message.orEmpty().contains("[Running") &&
        hasFrame(PLAYER_QUEUE) { it == "swap" } &&
        hasFrame(PLAYER_SESSION) { it.contains("loadingError") }

private fun Throwable.isReleasedAudioTrack(): Boolean =
    this is IllegalStateException &&
        hasFrame(ANDROID_SINK_OUTPUT) { true } &&
        hasFrame(AUDIO_SINK) { it == "run" }

private inline fun Throwable.hasFrame(className: String, method: (String) -> Boolean): Boolean =
    stackTrace.any { it.className == className && method(it.methodName) }

private const val PLAYER_SESSION = "xyz.gianlu.librespot.player.playback.PlayerSession"
private const val PLAYER_QUEUE = "xyz.gianlu.librespot.player.playback.PlayerQueue"
private const val AUDIO_SINK = "xyz.gianlu.librespot.player.mixing.AudioSink"
private const val ANDROID_SINK_OUTPUT = "xyz.gianlu.librespot.android.sink.AndroidSinkOutput"
