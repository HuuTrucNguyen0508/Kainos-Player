package com.universalmusic.player.platform

/**
 * librespot 0.2.0 runs its own follow-up tasks after Kainos has already moved on, and on Android
 * an uncaught exception in one kills the app:
 * - With autoplay off, when a one-track context ends, its own advance re-arms the entry that
 *   already holds the output and throws on a pooled `player-queue` thread. Every Spotify track
 *   end looked like a pause after a restart.
 * - When a track is skipped while it is still loading, the closed session's load retry swaps
 *   into a queue whose executor is already terminated (phone trace 2026-11-04 21:37:13).
 * Kainos loads the next track itself in both cases, so these failures are logged and ignored.
 */
internal fun installLibrespotStaleTaskGuard() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    if (previous !is StaleTaskGuard) Thread.setDefaultUncaughtExceptionHandler(StaleTaskGuard(previous))
}

private class StaleTaskGuard(
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        when {
            error.isEndOfContextAdvance() ->
                PlaybackTrace.log("Librespot", "end of context: librespot's own advance failed; Kainos loads the next track")
            error.isClosedSessionLoadRetry() ->
                PlaybackTrace.log("Librespot", "closed session: librespot's load retry was rejected; Kainos already moved on")
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

private inline fun Throwable.hasFrame(className: String, method: (String) -> Boolean): Boolean =
    stackTrace.any { it.className == className && method(it.methodName) }

private const val PLAYER_SESSION = "xyz.gianlu.librespot.player.playback.PlayerSession"
private const val PLAYER_QUEUE = "xyz.gianlu.librespot.player.playback.PlayerQueue"
