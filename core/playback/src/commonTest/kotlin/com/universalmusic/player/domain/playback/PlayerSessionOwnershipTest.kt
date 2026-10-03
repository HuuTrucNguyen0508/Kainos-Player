package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionOwnershipTest {
    @Test
    fun threeTrackQueueAdvancesOncePerNaturalCompletion() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        val tracks = listOf(
            track("One", "A", provider = ProviderId.SAMPLE),
            track("Two", "B", provider = ProviderId.SAMPLE),
            track("Three", "C", provider = ProviderId.SAMPLE),
        )
        session.play(tracks, startIndex = 0)
        runCurrent()
        assertEquals("One", session.nowPlaying.value.track?.title)

        engine.emitEnded()
        runCurrent()
        assertEquals("Two", session.nowPlaying.value.track?.title)

        engine.emitEnded()
        runCurrent()
        assertEquals("Three", session.nowPlaying.value.track?.title)

        engine.emitEnded()
        runCurrent()
        assertEquals("Three", session.nowPlaying.value.track?.title)
        assertFalse(session.nowPlaying.value.isPlaying)
        assertEquals(EngineStatus.IDLE, engine.state.value.status)
    }

    @Test
    fun lateArtworkFillsCurrentTrackAndQueueRowOnly() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        val first = track("One", "A", provider = ProviderId.SAMPLE)
        val second = track("Two", "B", provider = ProviderId.SAMPLE)
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()
        assertNull(session.nowPlaying.value.track?.artwork)

        session.updateCurrentTrackArtwork(second.canonicalId, Artwork("file:/late.jpg"))
        assertNull(session.nowPlaying.value.track?.artwork, "must not touch a non-current track")

        session.updateCurrentTrackArtwork(first.canonicalId, Artwork("file:/late.jpg"))
        assertEquals("file:/late.jpg", session.nowPlaying.value.track?.artwork?.url)
        assertEquals("file:/late.jpg", session.queue.queue.value.current?.track?.artwork?.url)
        assertNull(session.queue.queue.value.items[1].track.artwork)

        session.updateCurrentTrackArtwork(first.canonicalId, Artwork("file:/other.jpg"))
        assertEquals("file:/late.jpg", session.nowPlaying.value.track?.artwork?.url, "existing art wins")
    }

    @Test
    fun rapidNextDoesNotReviveStaleCompletion() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE),
                track("Two", "B", provider = ProviderId.SAMPLE),
                track("Three", "C", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        runCurrent()

        session.skipToNext()
        session.skipToNext()
        runCurrent()
        assertEquals("Three", session.nowPlaying.value.track?.title)

        // Extra ENDED after the queue is exhausted must not restart a cancelled request.
        engine.emitEnded()
        runCurrent()
        assertEquals("Three", session.nowPlaying.value.track?.title)
        assertFalse(session.nowPlaying.value.isPlaying)
    }

    @Test
    fun endedAtQueueTailDoesNotRetrigger() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE),
                track("Two", "B", provider = ProviderId.SAMPLE),
            ),
            startIndex = 1,
        )
        runCurrent()
        assertEquals("Two", session.nowPlaying.value.track?.title)

        engine.emitEnded()
        runCurrent()
        assertEquals("Two", session.nowPlaying.value.track?.title)
        assertFalse(session.nowPlaying.value.isPlaying)

        engine.emitEnded()
        runCurrent()
        assertEquals("Two", session.nowPlaying.value.track?.title)
        assertFalse(session.nowPlaying.value.isPlaying)
    }

    @Test
    fun clearQueueStopsAudioAndNowPlaying() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.playAwait(track("One", "A", provider = ProviderId.SAMPLE))
        assertEquals(EngineStatus.PLAYING, engine.state.value.status)

        session.clearQueue()
        runCurrent()

        assertEquals(EngineStatus.IDLE, engine.state.value.status)
        assertNull(session.nowPlaying.value.track)
        assertTrue(session.queue.queue.value.items.isEmpty())
    }

    @Test
    fun removeCurrentStartsReplacementTrack() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE),
                track("Two", "B", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        runCurrent()
        val currentId = session.queue.queue.value.current!!.id

        session.removeFromQueue(currentId)
        runCurrent()

        assertEquals("Two", session.nowPlaying.value.track?.title)
        assertEquals(EngineStatus.PLAYING, engine.state.value.status)
    }

    @Test
    fun manualNextEscapesRepeatOne() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE),
                track("Two", "B", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        session.queue.setRepeat(RepeatMode.ONE)
        runCurrent()

        session.skipToNext()
        runCurrent()

        assertEquals("Two", session.nowPlaying.value.track?.title)
    }

    @Test
    fun naturalCompletionRespectsRepeatOne() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE),
                track("Two", "B", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        session.queue.setRepeat(RepeatMode.ONE)
        runCurrent()

        engine.emitEnded()
        runCurrent()

        assertEquals("One", session.nowPlaying.value.track?.title)
        assertEquals(EngineStatus.PLAYING, engine.state.value.status)
    }

    @Test
    fun naturalCompletionAppendsAutoplayTracksOnce() = runTest {
        var fetches = 0
        val engine = ControllableEngine()
        val session = PlayerSession(
            engine,
            DefaultSourceResolver(),
            backgroundScope,
            onQueueExhausted = {
                fetches++
                if (fetches == 1) {
                    listOf(track("Cont", "Z", provider = ProviderId.SAMPLE, canonicalId = "sample:cont"))
                } else {
                    emptyList()
                }
            },
        )
        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE, canonicalId = "sample:1"),
                track("Two", "B", provider = ProviderId.SAMPLE, canonicalId = "sample:2"),
            ),
            startIndex = 0,
        )
        runCurrent()
        assertEquals("One", session.nowPlaying.value.track?.title)

        engine.emitEnded()
        runCurrent()
        assertEquals("Two", session.nowPlaying.value.track?.title)

        engine.emitEnded()
        runCurrent()
        runCurrent()
        assertEquals(1, fetches)
        assertEquals("Cont", session.nowPlaying.value.track?.title)
        assertEquals(3, session.queue.queue.value.items.size)

        // Second exhaustion: controller-style empty result stops cleanly (engine idle).
        engine.emitEnded()
        runCurrent()
        runCurrent()
        assertEquals(2, fetches)
        assertEquals(EngineStatus.IDLE, engine.state.value.status)
    }

    @Test
    fun emptyContinuationStopsAtQueueEnd() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(
            engine,
            DefaultSourceResolver(),
            backgroundScope,
            onQueueExhausted = { emptyList() },
        )
        session.play(listOf(track("Only", "A", provider = ProviderId.SAMPLE)), startIndex = 0)
        runCurrent()
        engine.emitEnded()
        runCurrent()
        runCurrent()
        assertEquals("Only", session.nowPlaying.value.track?.title)
        assertFalse(session.nowPlaying.value.isPlaying)
    }

    @Test
    fun favoriteDerivesFromTrackIdentityWithoutCarryover() = runTest {
        val favorites = mutableSetOf("spotify:kept")
        val engine = ControllableEngine()
        val session = PlayerSession(
            engine = engine,
            resolver = DefaultSourceResolver(),
            scope = backgroundScope,
            isFavorite = { it in favorites },
        )
        session.playAwait(track("Kept", "A", provider = ProviderId.SPOTIFY).copy(canonicalId = "spotify:kept"))
        assertTrue(session.nowPlaying.value.favorite)

        session.playAwait(track("Other", "B", provider = ProviderId.SPOTIFY).copy(canonicalId = "spotify:other"))
        assertFalse(session.nowPlaying.value.favorite)
    }

    @Test
    fun delayedEndedFromPreviousTrackDoesNotAdvancePastCurrent() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(
            listOf(
                track("A", "Artist", provider = ProviderId.SAMPLE),
                track("B", "Artist", provider = ProviderId.SAMPLE),
                track("C", "Artist", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        runCurrent()
        val generationA = engine.state.value.playGeneration

        session.skipToNext()
        runCurrent()
        assertEquals("B", session.nowPlaying.value.track?.title)
        val generationB = engine.state.value.playGeneration
        assertTrue(generationB > generationA)

        // A's delayed ENDED arrives after B is armed — must not skip to C.
        engine.emitEnded(generationA)
        runCurrent()
        assertEquals("B", session.nowPlaying.value.track?.title)

        // Untagged / zero-generation ENDED must also be ignored once B is active.
        engine.state.value = EngineState(status = EngineStatus.ENDED, durationMs = 1_000, playGeneration = 0L)
        runCurrent()
        assertEquals("B", session.nowPlaying.value.track?.title)
    }

    @Test
    fun delayedFailedFromPreviousTrackDoesNotReplaceCurrent() = runTest {
        val engine = ControllableEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(
            listOf(
                track("A", "Artist", provider = ProviderId.SAMPLE),
                track("B", "Artist", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        runCurrent()
        val generationA = engine.state.value.playGeneration
        session.skipToNext()
        runCurrent()
        assertEquals("B", session.nowPlaying.value.track?.title)

        engine.state.value = EngineState(
            status = EngineStatus.FAILED,
            error = "stale A failure",
            playGeneration = generationA,
        )
        runCurrent()
        assertEquals("B", session.nowPlaying.value.track?.title)
        assertNull(session.nowPlaying.value.error)
    }
}

private class ControllableEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    private var activePlayGeneration: Long = 0L

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        activePlayGeneration = playGeneration
        state.value = EngineState(
            status = EngineStatus.PLAYING,
            durationMs = 1_000,
            playGeneration = playGeneration,
        )
    }

    override fun pause() {
        state.value = state.value.copy(status = EngineStatus.PAUSED, playGeneration = activePlayGeneration)
    }

    override fun resume() {
        state.value = state.value.copy(status = EngineStatus.PLAYING, playGeneration = activePlayGeneration)
    }

    override fun seekTo(positionMs: Long) {
        state.value = state.value.copy(positionMs = positionMs, playGeneration = activePlayGeneration)
    }

    override fun stop() {
        state.value = EngineState(playGeneration = activePlayGeneration)
    }

    override fun setVolume(volume: Float) = Unit

    fun emitEnded() {
        state.value = state.value.copy(
            status = EngineStatus.ENDED,
            playGeneration = activePlayGeneration,
        )
    }

    /** Emit ENDED stamped with an explicit generation (stale-event probes). */
    fun emitEnded(generation: Long) {
        state.value = EngineState(
            status = EngineStatus.ENDED,
            durationMs = state.value.durationMs,
            playGeneration = generation,
        )
    }
}
