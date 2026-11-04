package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class LibrespotEndOfContextGuardTest {
    /** The frames from the phone trace of 2026-11-04 14:37:44, which killed the app at each track end. */
    private fun endOfContextFailure() = IllegalStateException("Cannot set output for PlayerQueueEntry{0192c31b381f3cb9f80d563a60807f31}").apply {
        stackTrace = arrayOf(
            StackTraceElement("xyz.gianlu.librespot.player.playback.PlayerQueueEntry", "setOutput", "PlayerQueueEntry.java", 227),
            StackTraceElement("xyz.gianlu.librespot.player.playback.PlayerSession", "playInternal", "PlayerSession.java", 282),
            StackTraceElement("xyz.gianlu.librespot.player.playback.PlayerSession", "advance", "PlayerSession.java", 131),
            StackTraceElement("xyz.gianlu.librespot.player.playback.PlayerSession", "playbackEnded", "PlayerSession.java", 157),
            StackTraceElement("xyz.gianlu.librespot.player.playback.PlayerQueueEntry", "run", "PlayerQueueEntry.java", 363),
        )
    }

    @Test fun endOfContextAdvanceNoLongerKillsTheAppButOtherCrashesStillDo() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val forwarded = mutableListOf<Throwable>()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, error -> forwarded += error }
            installLibrespotEndOfContextGuard()
            installLibrespotEndOfContextGuard()
            val thread = Thread({ throw endOfContextFailure() }, "player-queue-146042085")
            thread.start()
            thread.join()
            assertEquals(emptyList(), forwarded)

            val unrelated = IllegalStateException("Cannot set output for PlayerQueueEntry{x}")
            val handler = Thread.getDefaultUncaughtExceptionHandler()
            handler.uncaughtException(thread, unrelated)
            handler.uncaughtException(thread, RuntimeException("elsewhere"))
            assertEquals(listOf("Cannot set output for PlayerQueueEntry{x}", "elsewhere"), forwarded.map { it.message })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
