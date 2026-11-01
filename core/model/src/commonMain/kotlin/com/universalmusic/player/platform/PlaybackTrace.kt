package com.universalmusic.player.platform

import kotlinx.coroutines.CancellationException

/**
 * Transport / transition trace used to diagnose unexpected pauses and stalled skips.
 * Cheap enough to stay on: a handful of lines per track change, never per position tick.
 * Android writes logcat plus a rotating file; desktop prints to the run log.
 */
object PlaybackTrace {
    fun log(tag: String, message: String) = writePlaybackTrace(tag, redactCredentials(message))

    /** The log is shared by hand, and exception messages quote request URLs and the JSON being decoded. */
    fun failure(tag: String, message: String, cause: Throwable) =
        writePlaybackTrace(tag, "$message\n" + redactCredentials(cause.stackTraceToString()))
}

private val URL_QUERY = Regex("""(\w+://[^\s?#]+)\?[^\s\]]*""")
private val JSON_EXCERPT = Regex("""JSON input:[\s\S]*?(?=\n\s+at |\z)""")
private val CREDENTIAL_FIELD = Regex(
    """("\w*(?:token|secret|key|password)\w*"\s*:\s*)"[^"]*"""",
    RegexOption.IGNORE_CASE,
)
private val BEARER = Regex("""Bearer\s+\S+""")

internal fun redactCredentials(text: String): String = text
    .replace(JSON_EXCERPT, "JSON input: <redacted>")
    .replace(CREDENTIAL_FIELD, "$1\"<redacted>\"")
    .replace(BEARER, "Bearer <redacted>")
    .replace(URL_QUERY, "$1?<redacted>")

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
