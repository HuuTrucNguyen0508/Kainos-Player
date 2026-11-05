package com.universalmusic.player.platform

import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals

class LibrespotStaleTaskGuardTest {
    /** The frames from the phone trace of 2026-11-04 14:37:44, which killed the app at each track end. */
    private fun endOfContextFailure() = IllegalStateException("Cannot set output for PlayerQueueEntry{0192c31b381f3cb9f80d563a60807f31}").apply {
        stackTrace = arrayOf(
            frame(QUEUE_ENTRY, "setOutput", 227),
            frame(SESSION, "playInternal", 282),
            frame(SESSION, "advance", 131),
            frame(SESSION, "playbackEnded", 157),
            frame(QUEUE_ENTRY, "run", 363),
        )
    }

    /** The frames from the phone trace of 2026-11-04 21:37:13, after skipping a track that was still loading. */
    private fun closedSessionLoadRetry(poolState: String = "Terminated") = RejectedExecutionException(
        "Task PlayerQueueEntry{01e453c7bc7f6e76140e8800eff14850} rejected from java.util.concurrent.ThreadPoolExecutor@5bc3d42" +
            "[$poolState, pool size = 0, active threads = 0, queued tasks = 0, completed tasks = 1]",
    ).apply {
        stackTrace = arrayOf(
            StackTraceElement("java.util.concurrent.ThreadPoolExecutor\$AbortPolicy", "rejectedExecution", "ThreadPoolExecutor.java", 2050),
            StackTraceElement("java.util.concurrent.ThreadPoolExecutor", "execute", "ThreadPoolExecutor.java", 1338),
            StackTraceElement("xyz.gianlu.librespot.player.playback.PlayerQueue", "swap", "PlayerQueue.java", 106),
            frame(SESSION, "lambda\$loadingError\$2", 174),
            StackTraceElement("java.util.concurrent.ThreadPoolExecutor", "runWorker", "ThreadPoolExecutor.java", 1100),
        )
    }

    @Test fun staleLibrespotTasksNoLongerKillTheAppButOtherCrashesStillDo() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val forwarded = mutableListOf<Throwable>()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, error -> forwarded += error }
            installLibrespotStaleTaskGuard()
            installLibrespotStaleTaskGuard()
            for ((name, failure) in listOf("player-queue-146042085" to endOfContextFailure(), "player-session-45586647" to closedSessionLoadRetry())) {
                val thread = Thread({ throw failure }, name)
                thread.start()
                thread.join()
            }
            assertEquals(emptyList(), forwarded)

            val handler = Thread.getDefaultUncaughtExceptionHandler()
            val thread = Thread.currentThread()
            handler.uncaughtException(thread, IllegalStateException("Cannot set output for PlayerQueueEntry{x}"))
            handler.uncaughtException(thread, closedSessionLoadRetry(poolState = "Running"))
            handler.uncaughtException(thread, RejectedExecutionException("Task PlayerQueueEntry{y} rejected"))
            handler.uncaughtException(thread, RuntimeException("elsewhere"))
            assertEquals(
                listOf("IllegalStateException", "RejectedExecutionException", "RejectedExecutionException", "RuntimeException"),
                forwarded.map { it::class.simpleName },
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    private fun frame(className: String, method: String, line: Int) =
        StackTraceElement(className, method, className.substringAfterLast('.') + ".java", line)

    private companion object {
        const val SESSION = "xyz.gianlu.librespot.player.playback.PlayerSession"
        const val QUEUE_ENTRY = "xyz.gianlu.librespot.player.playback.PlayerQueueEntry"
    }
}
