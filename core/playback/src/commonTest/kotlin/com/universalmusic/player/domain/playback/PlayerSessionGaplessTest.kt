package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionGaplessTest {
    private class Engine : PlaybackEngine {
        override val supportsGapless = true
        override val state = MutableStateFlow(EngineState())
        var next: PreparedPlayback? = null
        var starts = 0
        var gapless = false
        override fun configureGapless(enabled: Boolean) { gapless = enabled }
        override fun prepareNext(next: PreparedPlayback?) { this.next = next }
        override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
            starts++
            state.value = EngineState(EngineStatus.PLAYING, playGeneration = playGeneration)
        }
        fun switch() {
            val prepared = requireNotNull(next)
            next = null
            state.value = EngineState(EngineStatus.PLAYING, durationMs = 42_000,
                playGeneration = prepared.generation + 1, previousGeneration = prepared.generation,
                seamlessItemId = prepared.itemId)
        }
        override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
        override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
        override fun stop() { next = null }
        override fun setVolume(volume: Float) = Unit
    }
    private fun local(title: String): Track {
        val track = track(title, "Artist", provider = ProviderId.LOCAL)
        return track.copy(sources = track.sources.map { it.copy(handle = PlaybackHandle.Url("/$title.flac")) })
    }

    @Test fun adoptsSwitchOnceWithoutStartingEngineAndRejectsStaleSwitch() = runTest {
        val engine = Engine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(listOf(local("A"), local("B"), local("C")))
        runCurrent()
        assertNotNull(engine.next)
        engine.switch()
        runCurrent()
        assertEquals("B", session.nowPlaying.value.track?.title)
        assertEquals(42_000, session.nowPlaying.value.durationMs)
        assertEquals(1, engine.starts)
        val stale = engine.state.value
        engine.switch()
        runCurrent()
        assertEquals("C", session.nowPlaying.value.track?.title)
        assertNull(engine.next)
        engine.state.value = stale
        runCurrent()
        assertEquals("C", session.nowPlaying.value.track?.title)
        assertEquals(1, engine.starts)
    }

    @Test fun shuffleWrapUsesPreparedTrackFirstInFreshOrder() = runTest {
        val engine = Engine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(listOf(local("A"), local("B"), local("C")))
        session.toggleShuffle()
        session.setRepeatNow(RepeatMode.ALL)
        runCurrent()
        repeat(3) {
            val expected = requireNotNull(engine.next).itemId
            engine.switch()
            runCurrent()
            assertEquals(expected, session.nowPlaying.value.queueItemId)
        }
        val queue = session.queueState.value
        assertEquals(queue.currentIndex, queue.shuffleOrder.first())
        assertEquals(1, engine.starts)
    }

    @Test fun cachedYoutubeFilesKeepTheOrdinaryEnginePath() = runTest {
        val engine = Engine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope,
            enrichSource = { _, source -> source.copy(handle = PlaybackHandle.Url("/cache.flac")) })
        session.play(listOf(track("Cached", "Artist", provider = ProviderId.YOUTUBE_MUSIC)))
        runCurrent()
        assertFalse(engine.gapless)
        assertNull(engine.next)
        assertEquals(1, engine.starts)
    }

    @Test fun replacesNextForQueueEditsRepeatAndDisablesForGateAndPreference() = runTest {
        val engine = Engine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(listOf(local("A"), local("B")))
        runCurrent()
        val original = engine.next
        session.playNext(local("Inserted"))
        runCurrent()
        assertNotEquals(original?.itemId, engine.next?.itemId)
        session.setRepeatNow(RepeatMode.ONE)
        runCurrent()
        assertEquals(session.nowPlaying.value.queueItemId, engine.next?.itemId)
        engine.switch()
        runCurrent()
        assertEquals("A", session.nowPlaying.value.track?.title)
        assertEquals(1, engine.starts)
        session.setNaturalCompletionGate { true }
        runCurrent()
        assertNull(engine.next)
        engine.state.value = engine.state.value.copy(status = EngineStatus.ENDED)
        runCurrent()
        assertFalse(session.nowPlaying.value.isPlaying)
        assertEquals("A", session.nowPlaying.value.track?.title)
        session.setNaturalCompletionGate(null)
        session.updatePreferences(PlaybackPreferences.Default.copy(gapless = false))
        runCurrent()
        assertNull(engine.next)
    }
}
