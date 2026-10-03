package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {
    @Test
    fun durationExpiryPausesViaTransportOnce() = runTest {
        val engine = SleepTimerTestEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        var clock = 1_000_000L
        val timer = SleepTimerController(session, backgroundScope) { clock }

        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE),
                track("Two", "B", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        runCurrent()
        assertTrue(session.nowPlaying.value.isPlaying)

        timer.startDuration(5_000L)
        runCurrent()
        assertTrue(timer.state.value.active)
        assertEquals(5_000L, timer.state.value.remainingMs)

        clock += 2_500L
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(timer.state.value.active)
        assertEquals(2_500L, timer.state.value.remainingMs)
        assertTrue(session.nowPlaying.value.isPlaying)

        clock += 2_500L
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(timer.state.value.active)
        assertNull(timer.state.value.mode)
        assertEquals(EngineStatus.PAUSED, engine.state.value.status)
        assertFalse(session.nowPlaying.value.isPlaying)
        assertEquals("One", session.nowPlaying.value.track?.title)
    }

    @Test
    fun cancelStopsDurationWithoutPausing() = runTest {
        val engine = SleepTimerTestEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        var clock = 0L
        val timer = SleepTimerController(session, backgroundScope) { clock }

        session.play(listOf(track("One", "A", provider = ProviderId.SAMPLE)), startIndex = 0)
        runCurrent()
        timer.startDuration(10_000L)
        runCurrent()

        timer.cancel()
        clock += 20_000L
        advanceTimeBy(20_000L)
        runCurrent()

        assertFalse(timer.state.value.active)
        assertEquals(EngineStatus.PLAYING, engine.state.value.status)
        assertTrue(session.nowPlaying.value.isPlaying)
    }

    @Test
    fun adjustDurationReplacesRemainingBudget() = runTest {
        val engine = SleepTimerTestEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        var clock = 0L
        val timer = SleepTimerController(session, backgroundScope) { clock }

        session.play(listOf(track("One", "A", provider = ProviderId.SAMPLE)), startIndex = 0)
        runCurrent()
        timer.startDuration(30_000L)
        runCurrent()

        clock += 5_000L
        timer.adjustDuration(3_000L)
        runCurrent()
        assertEquals(3_000L, timer.state.value.remainingMs)

        clock += 3_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(timer.state.value.active)
        assertEquals(EngineStatus.PAUSED, engine.state.value.status)
    }

    @Test
    fun endOfTrackPausesOnNaturalCompletionWithoutAdvancing() = runTest {
        val engine = SleepTimerTestEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        val timer = SleepTimerController(session, backgroundScope) { 0L }

        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE),
                track("Two", "B", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        runCurrent()
        timer.startEndOfTrack()
        runCurrent()
        assertTrue(timer.state.value.active)
        assertIs<SleepTimerMode.EndOfTrack>(timer.state.value.mode)

        engine.emitEnded()
        runCurrent()

        assertFalse(timer.state.value.active)
        assertEquals("One", session.nowPlaying.value.track?.title)
        assertFalse(session.nowPlaying.value.isPlaying)
        assertEquals(EngineStatus.IDLE, engine.state.value.status)
    }

    @Test
    fun skipDoesNotFireEndOfTrackPause() = runTest {
        val engine = SleepTimerTestEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        val timer = SleepTimerController(session, backgroundScope) { 0L }

        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE),
                track("Two", "B", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        runCurrent()
        timer.startEndOfTrack()
        runCurrent()

        session.skipToNext()
        runCurrent()

        assertFalse(timer.state.value.active, "skip cancels end-of-track mode")
        assertEquals("Two", session.nowPlaying.value.track?.title)
        assertTrue(session.nowPlaying.value.isPlaying)

        engine.emitEnded()
        runCurrent()
        assertEquals("Two", session.nowPlaying.value.track?.title)
        // Natural end of the skipped-to track advances or stops normally; timer stays off.
        assertFalse(timer.state.value.active)
        assertFalse(session.nowPlaying.value.isPlaying)
    }

    @Test
    fun formatSleepTimerRemainingPadsMinutesAndHours() {
        assertEquals("0:05", formatSleepTimerRemaining(4_100L))
        assertEquals("5:00", formatSleepTimerRemaining(5 * 60_000L))
        assertEquals("1:02:03", formatSleepTimerRemaining((3_600L + 2 * 60L + 3L) * 1_000L))
    }
}

private class SleepTimerTestEngine : PlaybackEngine {
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
}
