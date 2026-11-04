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
    fun durationFadesEngineVolumeOverLastThirtySecondsThenPausesAndRestores() = runTest {
        val engine = SleepTimerTestEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        var clock = 0L
        val timer = SleepTimerController(session, backgroundScope) { clock }

        session.play(listOf(track("One", "A", provider = ProviderId.SAMPLE)), startIndex = 0)
        runCurrent()
        session.setVolume(0.8f)
        runCurrent()
        timer.startDuration(60_000L)
        runCurrent()
        engine.events.clear()

        clock += 29_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(emptyList(), engine.events, "no fade before the last 30 seconds")
        assertFalse(timer.state.value.fading)

        clock += 16_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("volume 0.4"), engine.events)
        assertTrue(timer.state.value.fading)
        assertEquals(0.8f, session.volume.value, "the user's volume setting never moves")
        assertTrue(session.nowPlaying.value.isPlaying)

        clock += 15_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("volume 0.4", "pause", "volume 0.8"), engine.events)
        assertFalse(timer.state.value.active)
        assertFalse(timer.state.value.fading)
        assertFalse(session.nowPlaying.value.isPlaying)

        engine.events.clear()
        session.playTransport()
        runCurrent()
        assertEquals(listOf("volume 0.8", "resume"), engine.events, "next Play resends the restored volume")
    }

    @Test
    fun cancelMidFadeRestoresVolumeAtOnceAndKeepsPlaying() = runTest {
        val engine = SleepTimerTestEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        var clock = 0L
        val timer = SleepTimerController(session, backgroundScope) { clock }

        session.play(listOf(track("One", "A", provider = ProviderId.SAMPLE)), startIndex = 0)
        runCurrent()
        timer.startDuration(60_000L)
        runCurrent()
        engine.events.clear()

        clock += 45_000L
        advanceTimeBy(1_000L)
        runCurrent()
        session.setVolume(0.6f)
        runCurrent()
        assertEquals(listOf("volume 0.5", "volume 0.3"), engine.events, "a slider move mid-fade stays scaled")

        timer.cancel()
        runCurrent()
        assertEquals(listOf("volume 0.5", "volume 0.3", "volume 0.6"), engine.events)
        assertFalse(timer.state.value.active)
        assertTrue(session.nowPlaying.value.isPlaying)

        clock += 60_000L
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(EngineStatus.PLAYING, engine.state.value.status)
        assertEquals(listOf("volume 0.5", "volume 0.3", "volume 0.6"), engine.events)
    }

    @Test
    fun endOfTrackFadesOverTheTracksRemainingTimeAndRestoresAtTheEnd() = runTest {
        val engine = SleepTimerTestEngine(durationMs = 120_000L)
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        var clock = 0L
        val timer = SleepTimerController(session, backgroundScope) { clock }

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
        engine.events.clear()

        engine.emitPosition(80_000L)
        runCurrent()
        assertEquals(emptyList(), engine.events)

        clock += 1_000L
        engine.emitPosition(105_000L)
        runCurrent()
        assertEquals(listOf("volume 0.5"), engine.events)
        assertTrue(timer.state.value.fading)

        clock += 1_000L
        engine.emitPosition(60_000L)
        runCurrent()
        assertEquals(listOf("volume 0.5", "volume 1.0"), engine.events, "seeking back out of the fade restores")
        assertFalse(timer.state.value.fading)

        clock += 1_000L
        engine.emitPosition(114_000L)
        runCurrent()
        engine.emitEnded()
        runCurrent()
        assertEquals(listOf("volume 0.5", "volume 1.0", "volume 0.2", "volume 1.0", "stop"), engine.events)
        assertFalse(timer.state.value.active)
        assertEquals("One", session.nowPlaying.value.track?.title)
        assertFalse(session.nowPlaying.value.isPlaying)
    }

    @Test
    fun skipDuringEndOfTrackFadeRestoresVolume() = runTest {
        val engine = SleepTimerTestEngine(durationMs = 120_000L)
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
        engine.emitPosition(105_000L)
        runCurrent()
        assertEquals(0.5f, engine.volume)

        session.skipToNext()
        runCurrent()
        assertFalse(timer.state.value.active)
        assertEquals("Two", session.nowPlaying.value.track?.title)
        assertEquals(1f, engine.volume)
    }


    @Test
    fun formatSleepTimerRemainingPadsMinutesAndHours() {
        assertEquals("0:05", formatSleepTimerRemaining(4_100L))
        assertEquals("5:00", formatSleepTimerRemaining(5 * 60_000L))
        assertEquals("1:02:03", formatSleepTimerRemaining((3_600L + 2 * 60L + 3L) * 1_000L))
    }
}

private class SleepTimerTestEngine(private val durationMs: Long = 1_000L) : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    private var activePlayGeneration: Long = 0L
    /** Volume, pause, resume and stop calls in order, volumes rounded to one decimal. */
    val events = mutableListOf<String>()
    var volume = 1f
        private set

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        activePlayGeneration = playGeneration
        state.value = EngineState(
            status = EngineStatus.PLAYING,
            durationMs = durationMs,
            playGeneration = playGeneration,
        )
    }

    override fun pause() {
        events += "pause"
        state.value = state.value.copy(status = EngineStatus.PAUSED, playGeneration = activePlayGeneration)
    }

    override fun resume() {
        events += "resume"
        state.value = state.value.copy(status = EngineStatus.PLAYING, playGeneration = activePlayGeneration)
    }

    override fun seekTo(positionMs: Long) {
        state.value = state.value.copy(positionMs = positionMs, playGeneration = activePlayGeneration)
    }

    override fun stop() {
        events += "stop"
        state.value = EngineState(playGeneration = activePlayGeneration)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        events += "volume ${kotlin.math.round(volume * 10) / 10}"
    }

    fun emitPosition(positionMs: Long) {
        state.value = state.value.copy(positionMs = positionMs, playGeneration = activePlayGeneration)
    }

    fun emitEnded() {
        state.value = state.value.copy(
            status = EngineStatus.ENDED,
            playGeneration = activePlayGeneration,
        )
    }
}
