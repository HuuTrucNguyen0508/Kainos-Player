package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.session.toSessionSnapshot
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LearnedDurationTest {
    private val unknown = track("Folder song", "Artist", durationMs = null, provider = ProviderId.LOCAL)
    private val other = track("Other", "Artist", durationMs = null, provider = ProviderId.LOCAL)
    private val queue = PlaybackQueue(
        items = listOf(QueueItem("a", other), QueueItem("b", unknown)),
        currentIndex = 1,
    )

    @Test
    fun engineDurationIsKeptOnTheCurrentItemOnly() = runTest {
        val engine = ReportingEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.restorePaused(queue.toSessionSnapshot(0, 0))
        runCurrent()
        engine.state.value = EngineState(EngineStatus.PLAYING, durationMs = 212_000, playGeneration = 1)
        runCurrent()
        assertEquals(212_000, session.queue.queue.value.items[1].track.durationMs)
        assertEquals(212_000, session.nowPlaying.value.track?.durationMs)
        assertNull(session.queue.queue.value.items[0].track.durationMs)
    }

    @Test
    fun scannedDurationIsNeverOverwritten() = runTest {
        val engine = ReportingEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        val known = queue.copy(items = listOf(queue.items[0], QueueItem("b", unknown.copy(durationMs = 100_000))))
        session.restorePaused(known.toSessionSnapshot(0, 0))
        runCurrent()
        engine.state.value = EngineState(EngineStatus.PLAYING, durationMs = 212_000, playGeneration = 1)
        runCurrent()
        assertEquals(100_000, session.queue.queue.value.current?.track?.durationMs)
    }

    @Test
    fun staleOrMissingEngineDurationLeavesTheQueueAlone() = runTest {
        val engine = ReportingEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.restorePaused(queue.toSessionSnapshot(0, 0))
        runCurrent()
        for (event in listOf(
            EngineState(EngineStatus.PLAYING, durationMs = 212_000, playGeneration = -1),
            EngineState(EngineStatus.PLAYING, durationMs = null, playGeneration = 1),
            EngineState(EngineStatus.PLAYING, durationMs = 0, playGeneration = 1),
            EngineState(EngineStatus.PLAYING, durationMs = -1, playGeneration = 1),
        )) {
            engine.state.value = event
            runCurrent()
            assertNull(session.queue.queue.value.current?.track?.durationMs)
        }
    }
}

private class ReportingEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun stop() = Unit
    override fun setVolume(volume: Float) = Unit
}
