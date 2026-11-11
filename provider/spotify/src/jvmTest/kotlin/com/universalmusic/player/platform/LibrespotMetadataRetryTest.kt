package com.universalmusic.player.platform

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrespotMetadataRetryTest {
    private val waits = mutableListOf<Long>()

    private suspend fun <T> retry(attempt: suspend () -> T): T =
        retryTransient("test", sleep = { waits += it }, attempt = attempt)

    @Test
    fun refusedConnectsAreRetriedWithGrowingWaitsUntilOneSucceeds() = runBlocking {
        var attempts = 0
        val result = retry {
            if (++attempts < 3) {
                // The exact failure from the 2026-11-07 phone trace.
                throw ConnectException("failed to connect to ap-gue1.spotify.com/34.158.255.62 (port 443): connect failed: ECONNREFUSED")
            }
            "playlist"
        }
        assertEquals("playlist", result)
        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 3_000L), waits)
    }

    @Test
    fun givesUpAfterTheLastWaitWithTheLastFailure() = runBlocking {
        var attempts = 0
        val failure = assertFailsWith<SocketException> {
            retry<Unit> { attempts++; throw SocketException("Connection reset") }
        }
        assertEquals("Connection reset", failure.message)
        assertEquals(4, attempts)
        assertEquals(listOf(1_000L, 3_000L, 8_000L), waits)
    }

    @Test
    fun permanentFailuresAreNotRetried() = runBlocking {
        var attempts = 0
        assertFailsWith<IllegalStateException> { retry<Unit> { attempts++; error("Spotify returned no radio") } }
        assertEquals(1, attempts)
        assertEquals(emptyList(), waits)
    }

    @Test
    fun transientFailuresAreRecognisedThroughWrappers() {
        assertTrue(isTransientNetworkFailure(IOException("read failed", SocketTimeoutException("timeout"))))
        assertTrue(isTransientNetworkFailure(RuntimeException(java.net.UnknownHostException("apresolve.spotify.com"))))
        assertFalse(isTransientNetworkFailure(IOException("HTTP 404")))
        assertFalse(isTransientNetworkFailure(IllegalArgumentException("bad Spotify track id")))
    }
}
