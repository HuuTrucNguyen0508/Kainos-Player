package com.universalmusic.player.platform

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyDesktopClientTest {
    @Test
    fun returnsImmediatelyWhenAlreadyRunning() = runBlocking {
        val launches = AtomicInteger(0)
        val ok = ensureSpotifyDesktopClientRunning(
            isRunning = { true },
            launch = {
                launches.incrementAndGet()
                true
            },
            waitSeconds = 2,
        )
        assertTrue(ok)
        assertEquals(0, launches.get())
    }

    @Test
    fun launchesAndWaitsUntilRunning() = runBlocking {
        val launches = AtomicInteger(0)
        var ticks = 0
        val ok = ensureSpotifyDesktopClientRunning(
            isRunning = {
                ticks += 1
                ticks >= 2
            },
            launch = {
                launches.incrementAndGet()
                true
            },
            waitSeconds = 5,
        )
        assertTrue(ok)
        assertEquals(1, launches.get())
    }

    @Test
    fun failsWhenLaunchFails() = runBlocking {
        val ok = ensureSpotifyDesktopClientRunning(
            isRunning = { false },
            launch = { false },
            waitSeconds = 1,
        )
        assertFalse(ok)
    }
}
