package com.universalmusic.player.platform

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class ApConnectDeadlineTest {
    private class Connection(val name: String)

    @Test
    fun aConnectThatAnswersInTimeIsReturnedAndKept() = runBlocking {
        val discarded = mutableListOf<Connection>()
        val connection = connectWithin(1_000, "test-connect", { discarded += it }) { Connection("ap") }
        assertEquals("ap", connection.name)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun aConnectThatNeverAnswersTimesOutAsARetryableFailure() = runBlocking {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val failure = assertFailsWith<ApConnectTimeoutException> {
            connectWithin(200, "test-connect", {}) { release.await(); Connection("late") }
        }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertTrue(waitedMs in 200..2_000, "waited $waitedMs ms")
        assertTrue(isRetryableLibrespotConnectFailure(failure), failure.message)
    }

    @Test
    fun aConnectThatAnswersAfterItsDeadlineIsDiscarded() = runBlocking {
        val release = CountDownLatch(1)
        val discarded = CountDownLatch(1)
        val late = mutableListOf<Connection>()
        assertFailsWith<ApConnectTimeoutException> {
            connectWithin(100, "test-connect", { late += it; discarded.countDown() }) {
                release.await()
                Connection("late")
            }
        }
        release.countDown()
        assertTrue(discarded.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("late"), late.map { it.name })
    }

    @Test
    fun aCancelledCallerStopsWaitingAndTheLateConnectIsDiscarded() = runBlocking {
        val release = CountDownLatch(1)
        val discarded = CountDownLatch(1)
        val waiting = async {
            connectWithin(10_000, "test-connect", { discarded.countDown() }) { release.await(); Connection("late") }
        }
        delay(50)
        val started = System.nanoTime()
        waiting.cancel()
        waiting.join()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        release.countDown()
        assertTrue(discarded.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun aConnectFailureReachesTheCallerUnchanged() = runBlocking {
        class Rejected : Exception("Spotify rejected the credentials")
        assertFailsWith<Rejected> {
            connectWithin<Connection>(1_000, "test-connect", {}) { throw Rejected() }
        }
        Unit
    }
}
