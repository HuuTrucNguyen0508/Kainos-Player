package com.universalmusic.player.domain.continuation

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRadioTest {
    @Test
    fun keepsOnlyTheSeedProvidersTracks() = runTest {
        val radio = SourceRadio(ProviderId.SPOTIFY, fetch = { _, _ ->
            listOf(sp("a"), yt("b"), local("c"), sp("d"))
        }, trace = {})

        val batch = radio.nextBatch(queueOf(sp("seed")))

        assertEquals(listOf("spotify:a", "spotify:d"), batch.ids())
    }

    @Test
    fun excludesQueuedAndPreviouslyAppendedTracks() = runTest {
        val excludes = mutableListOf<Set<String>>()
        val radio = SourceRadio(ProviderId.YOUTUBE_MUSIC, fetch = { _, exclude ->
            excludes += exclude
            listOf(yt("seed"), yt("a"), yt("b"), yt("q"))
        }, trace = {})

        val first = radio.nextBatch(queueOf(yt("seed"), yt("q")))
        // The user removed "a" from the queue; the radio still remembers it.
        val second = radio.nextBatch(queueOf(yt("seed"), yt("q"), yt("b")))

        assertEquals(listOf("yt:a", "yt:b"), first.ids())
        assertEquals(emptyList(), second.ids())
        assertEquals(setOf("yt:seed", "yt:q"), excludes.first())
        assertTrue("yt:a" in excludes.last())
    }

    @Test
    fun seedsFromTheCurrentTrackOrTheLatestPlayedOneOfItsSource() = runTest {
        val seeds = mutableListOf<String>()
        val radio = SourceRadio(ProviderId.SPOTIFY, fetch = { seed, _ ->
            seeds += seed.canonicalId
            emptyList()
        }, trace = {})

        radio.nextBatch(queueOf(sp("seed"), sp("r1"), sp("r2"), current = 2))
        // A manually queued YouTube track is playing: seed from the last Spotify track before it.
        radio.nextBatch(queueOf(sp("seed"), sp("r1"), yt("manual"), current = 2))

        assertEquals(listOf("spotify:r2", "spotify:r1"), seeds)
    }

    @Test
    fun aFailingSourceRetriesWithoutPretendingItWasEmpty() = runTest {
        val traces = mutableListOf<String>()
        val radio = SourceRadio(
            ProviderId.SPOTIFY,
            fetch = { _, _ -> error("librespot credentials missing") },
            trace = { traces += it },
        )

        var failure: Exception? = null
        try { radio.nextBatch(queueOf(sp("seed"))) } catch (error: Exception) { failure = error }
        assertEquals("librespot credentials missing", failure?.message)
        assertEquals(3, traces.size)
    }

    @Test
    fun capsEachBatch() = runTest {
        val radio = SourceRadio(
            ProviderId.YOUTUBE_MUSIC,
            fetch = { _, _ -> (1..40).map { yt("v$it") } },
            batchLimit = 25,
            trace = {},
        )

        assertEquals(25, radio.nextBatch(queueOf(yt("seed"))).size)
    }

    private fun queueOf(vararg tracks: Track, current: Int = 0) = PlaybackQueue(
        items = tracks.mapIndexed { i, track -> QueueItem("item-$i", track) },
        currentIndex = current,
    )

    private fun List<Track>.ids() = map { it.canonicalId }
}

internal fun sp(id: String) = track(id, "Artist", provider = ProviderId.SPOTIFY, canonicalId = "spotify:$id")

internal fun yt(id: String) = track(id, "Channel", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:$id")

internal fun local(id: String) = track(id, "Artist", provider = ProviderId.LOCAL, canonicalId = "local:$id")
