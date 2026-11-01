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

internal actual fun flushPlaybackTrace() = System.out.flush()

actual fun installCrashLogging() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    if (previous !is CrashLogHandler) Thread.setDefaultUncaughtExceptionHandler(CrashLogHandler(previous))
}

private class CrashLogHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, cause: Throwable) {
        logCrash(thread.name, cause)
        if (previous != null) {
            previous.uncaughtException(thread, redactedCrash(cause))
        } else {
            System.err.print("Exception in thread \"${thread.name}\" ")
            redactedCrash(cause).printStackTrace()
        }
    }
}

actual fun clearPlaybackTrace() = Unit

private fun redactedCrash(cause: Throwable): Throwable {
    if (cause is ThreadDeath) return cause
    val trace = redactCredentials(cause.stackTraceToString())
    return object : Throwable(redactCredentials(cause.message.orEmpty())) {
        override fun toString(): String = trace.lineSequence().first()
        override fun printStackTrace(stream: java.io.PrintStream) = stream.print(trace)
        override fun printStackTrace(writer: java.io.PrintWriter) = writer.print(trace)
    }
}
