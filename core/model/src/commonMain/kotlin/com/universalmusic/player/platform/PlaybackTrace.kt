package com.universalmusic.player.platform

import kotlinx.coroutines.CancellationException

/**
 * Transport / transition trace used to diagnose unexpected pauses and stalled skips.
 * Cheap enough to stay on: a handful of lines per track change, never per position tick.
 * Android writes logcat plus a rotating file; desktop prints to the run log.
 */
object PlaybackTrace {
    fun log(tag: String, message: String) = writePlaybackTrace(tag, message)

    /** Query strings are dropped because exception messages quote request URLs, and those carry API keys. */
    fun failure(tag: String, message: String, cause: Throwable) =
        writePlaybackTrace(tag, "$message\n" + cause.stackTraceToString().replace(URL_QUERY, "$1?<redacted>"))
}

private val URL_QUERY = Regex("""(\w+://[^\s?#]+)\?[^\s\]]*""")

/** For a failure the caller recovers from: it stays handled, and the shared log still has its cause. */
fun <T> Result<T>.logged(tag: String, message: String): Result<T> = onFailure {
    if (it is CancellationException) throw it
    PlaybackTrace.failure(tag, message, it)
}

/** Sends crashes to the trace, then on to the handler that was already installed. Safe to call twice. */
expect fun installCrashLogging()

internal fun logCrash(threadName: String, cause: Throwable) {
    runCatching {
        PlaybackTrace.failure("Crash", "uncaught on $threadName", cause)
        flushPlaybackTrace()
    }
}

internal expect fun flushPlaybackTrace()

internal expect fun writePlaybackTrace(tag: String, message: String)

/** Where the trace lives on this platform and whether the OS share sheet can hand it off. */
data class PlaybackTraceInfo(
    val location: String,
    val sizeBytes: Long,
    val canShare: Boolean,
)

expect fun playbackTraceInfo(): PlaybackTraceInfo

/** Opens the platform share sheet with the trace file(s). Returns false when nothing could be shared. */
expect fun sharePlaybackTrace(): Boolean

expect fun clearPlaybackTrace()
