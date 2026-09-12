package com.universalmusic.player.platform

/**
 * Transport / transition trace used to diagnose unexpected pauses and stalled skips.
 * Cheap enough to stay on: a handful of lines per track change, never per position tick.
 * Android writes logcat plus a rotating file; desktop prints to the run log.
 */
object PlaybackTrace {
    fun log(tag: String, message: String) = writePlaybackTrace(tag, message)
}

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
