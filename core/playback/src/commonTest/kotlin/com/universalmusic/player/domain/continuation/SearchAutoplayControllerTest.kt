package com.universalmusic.player.domain.continuation

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SearchAutoplayControllerTest {
    @Test
    fun disabledReturnsDisabledWithoutFetching() = runTest {
        var fetches = 0
        val controller = SearchAutoplayController(
            autoplayEnabled = { false },
            fetchContinuation = { _, _, _ ->
                fetches++
                listOf(track("X", "Y", provider = ProviderId.YOUTUBE_MUSIC))
            },
        )
        controller.beginSearchPlayback(listOf(track("A", "B", provider = ProviderId.YOUTUBE_MUSIC)), "q")
        assertIs<ContinuationOutcome.Disabled>(controller.requestContinuation(emptySet()))
        assertEquals(0, fetches)
    }

    @Test
    fun appendsOnceThenAlreadyAttempted() = runTest {
        var fetches = 0
        val more = track("More", "Z", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:more")
        val controller = SearchAutoplayController(
            autoplayEnabled = { true },
            fetchContinuation = { _, _, _ ->
                fetches++
                listOf(more)
            },
        )
        val seed = track("Seed", "A", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:seed")
        controller.beginSearchPlayback(listOf(seed), "night drive")
        val first = controller.requestContinuation(setOf("yt:seed"))
        assertIs<ContinuationOutcome.Appended>(first)
        assertEquals(listOf("yt:more"), first.tracks.map { it.canonicalId })
        assertEquals(1, fetches)
        assertIs<ContinuationOutcome.AlreadyAttempted>(controller.requestContinuation(setOf("yt:seed")))
        assertEquals(1, fetches)
    }

    @Test
    fun unavailableIsClearAndDoesNotRetryFetch() = runTest {
        var fetches = 0
        val controller = SearchAutoplayController(
            autoplayEnabled = { true },
            fetchContinuation = { _, _, _ ->
                fetches++
                error("Spotify recommendations/radio unavailable for this Client ID")
            },
        )
        controller.beginSearchPlayback(
            listOf(track("S", "A", provider = ProviderId.SPOTIFY, canonicalId = "spotify:1")),
            "q",
        )
        val first = controller.requestContinuation(emptySet())
        assertIs<ContinuationOutcome.Unavailable>(first)
        assertTrue(first.reason.contains("unavailable", ignoreCase = true))
        assertEquals(1, fetches)
        assertIs<ContinuationOutcome.AlreadyAttempted>(controller.requestContinuation(emptySet()))
        assertEquals(1, fetches)
    }

    @Test
    fun nonSearchSessionDoesNotFetch() = runTest {
        var fetches = 0
        val controller = SearchAutoplayController(
            autoplayEnabled = { true },
            fetchContinuation = { _, _, _ ->
                fetches++
                emptyList()
            },
        )
        assertIs<ContinuationOutcome.NotSearchSession>(controller.requestContinuation(emptySet()))
        assertEquals(0, fetches)
    }
}
