package com.universalmusic.player.platform

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val traceTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

internal actual fun writePlaybackTrace(tag: String, message: String) {
    println("TRACE ${LocalTime.now().format(traceTime)} [$tag] $message")
}

actual fun playbackTraceInfo(): PlaybackTraceInfo = PlaybackTraceInfo(
    location = "stdout (captured in logs/desktop-run-*.log when launched via the run script)",
    sizeBytes = 0L,
    canShare = false,
)

actual fun sharePlaybackTrace(): Boolean = false

actual fun clearPlaybackTrace() = Unit
