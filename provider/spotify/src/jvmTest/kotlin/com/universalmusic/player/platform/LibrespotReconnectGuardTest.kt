package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class LibrespotReconnectGuardTest {
    @Test fun interruptedApReconnectRecoversButOtherFailuresReachAndroid() {
        var recovered = 0
        var forwarded = 0
        val thread = Thread({}, "session-packet-receiver")
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> forwarded++ }
        installLibrespotReconnectGuard(thread) { recovered++ }
        val interruption = InterruptedException().apply {
            stackTrace = arrayOf(
                StackTraceElement("xyz.gianlu.librespot.core.ApResolver", "request", "ApResolver.java", 1),
                StackTraceElement("xyz.gianlu.librespot.core.Session", "reconnect", "Session.java", 1),
            )
        }
        thread.uncaughtExceptionHandler.uncaughtException(thread, interruption)
        assertEquals(1, recovered)
        assertEquals(0, forwarded)
        thread.uncaughtExceptionHandler.uncaughtException(thread, IllegalStateException("unrelated"))
        thread.uncaughtExceptionHandler.uncaughtException(thread, InterruptedException("elsewhere"))
        assertEquals(2, forwarded)
    }
}
