package com.universalmusic.player.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EchoStatusPollerTest {
    private fun body(generatedAt: Long) = """{"version":1,"generated_at_ms":$generatedAt,"echoes":[]}"""

    @Test
    fun pollsEveryFiveSecondsOnlyWhileCollected() = runTest {
        var calls = 0
        val poller = EchoStatusPoller(backgroundScope, fetch = { calls++; body(calls.toLong()) }, clock = { testScheduler.currentTime })
        runCurrent()
        assertEquals(0, calls, "nothing polls before Home collects")

        val watching = poller.status.launchIn(backgroundScope)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, poller.status.value?.generatedAtMs)
        advanceTimeBy(ECHO_POLL_MS * 2)
        runCurrent()
        assertEquals(3, calls)

        watching.cancel()
        advanceTimeBy(ECHO_BACKOFF_MS * 4)
        runCurrent()
        val afterGrace = calls
        assertTrue(afterGrace in 3..4, "at most one more poll during the grace period")
        advanceTimeBy(ECHO_BACKOFF_MS * 4)
        runCurrent()
        assertEquals(afterGrace, calls, "leaving Home stops polling")
        assertNull(poller.status.value, "an old status is not replayed on return")
    }

    @Test
    fun aTimedOutRequestCountsAsAMissAndPollingGoesOn() = runTest {
        var calls = 0
        val poller = EchoStatusPoller(
            backgroundScope,
            fetch = {
                calls++
                if (calls == 1) withTimeout(3_000) { awaitCancellation() }
                body(calls.toLong())
            },
            clock = { testScheduler.currentTime },
        )
        poller.status.launchIn(backgroundScope)
        advanceTimeBy(3_000 + ECHO_POLL_MS)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(2, poller.status.value?.generatedAtMs)
    }

    @Test
    fun oneMissKeepsTheCardAndTwoClearItAndBackOff() = runTest {
        val replies = ArrayDeque(listOf<String?>(body(1), null, null, null, body(2)))
        var calls = 0
        val poller = EchoStatusPoller(
            backgroundScope,
            fetch = { calls++; replies.removeFirst() ?: error("connection refused") },
            clock = { testScheduler.currentTime },
        )
        poller.status.launchIn(backgroundScope)
        runCurrent()
        assertNotNull(poller.status.value)

        advanceTimeBy(ECHO_POLL_MS)
        runCurrent()
        assertEquals(2, calls)
        assertNotNull(poller.status.value, "a single miss keeps the last status")

        advanceTimeBy(ECHO_POLL_MS)
        runCurrent()
        assertEquals(3, calls)
        assertNull(poller.status.value, "a second miss hides the card")

        advanceTimeBy(ECHO_BACKOFF_MS - 1)
        runCurrent()
        assertEquals(3, calls, "unreachable gateway is polled every 30 s")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, calls)
        advanceTimeBy(ECHO_BACKOFF_MS)
        runCurrent()
        assertEquals(2, poller.status.value?.generatedAtMs)
    }

    @Test
    fun anUnsupportedVersionCountsAsAMiss() = runTest {
        val poller = EchoStatusPoller(
            backgroundScope,
            fetch = { """{"version":2,"generated_at_ms":1,"echoes":[]}""" },
            clock = { testScheduler.currentTime },
        )
        poller.status.launchIn(backgroundScope)
        runCurrent()
        advanceTimeBy(ECHO_POLL_MS)
        runCurrent()
        assertNull(poller.status.value)
    }
}
