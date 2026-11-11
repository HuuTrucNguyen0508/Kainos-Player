package com.universalmusic.player.platform

import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class MetadataBatchTest {
    @Test
    fun aTransientFailureDoesNotPublishAPartialPlaylistAsFresh() {
        assertFailsWith<SocketException> {
            metadataBatchResults(listOf(Result.success("track"), Result.failure(SocketException("reset"))), ::isTransientNetworkFailure)
        }
    }

    @Test
    fun aRateLimitIsPropagatedEvenWhenAnotherTrackLoaded() {
        class RateLimit : Exception()
        assertFailsWith<RateLimit> {
            metadataBatchResults(listOf(Result.success("track"), Result.failure(RateLimit()))) { it is RateLimit }
        }
    }

    @Test
    fun anUnavailableTrackCanStillBeOmitted() {
        assertEquals(listOf("track"), metadataBatchResults(listOf(Result.success("track"), Result.failure(IllegalArgumentException()))) { false })
    }
}
