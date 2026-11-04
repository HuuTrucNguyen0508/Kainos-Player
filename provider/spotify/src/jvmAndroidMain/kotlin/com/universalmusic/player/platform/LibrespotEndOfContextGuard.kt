package com.universalmusic.player.platform

/**
 * librespot 0.2.0 with autoplay off: when a one-track context ends, its own advance re-arms the
 * entry that already holds the output and throws on a pooled `player-queue` thread. That task
 * is finished anyway (Kainos loads the next track itself), but on Android an uncaught exception
 * kills the app, so every Spotify track end looked like a pause after a restart.
 */
internal fun installLibrespotEndOfContextGuard() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    if (previous !is EndOfContextGuard) Thread.setDefaultUncaughtExceptionHandler(EndOfContextGuard(previous))
}

private class EndOfContextGuard(
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        if (error.isEndOfContextAdvance()) {
            PlaybackTrace.log("Librespot", "end of context: librespot's own advance failed; Kainos loads the next track")
        } else {
            delegate?.uncaughtException(thread, error)
        }
    }
}

private fun Throwable.isEndOfContextAdvance(): Boolean =
    this is IllegalStateException &&
        message.orEmpty().startsWith("Cannot set output for PlayerQueueEntry") &&
        stackTrace.any { it.className == PLAYER_SESSION && it.methodName == "advance" } &&
        stackTrace.any { it.className == PLAYER_SESSION && it.methodName == "playbackEnded" }

private const val PLAYER_SESSION = "xyz.gianlu.librespot.player.playback.PlayerSession"
