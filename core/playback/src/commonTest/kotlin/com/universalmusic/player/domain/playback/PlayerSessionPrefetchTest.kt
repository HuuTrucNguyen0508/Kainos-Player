package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionPrefetchTest {
    @Test
    fun naturalTransitionAfterThirtySecondsUsesPrefetchedUrl() = runTest {
        val fixture = Fixture(this)
        fixture.session.play(listOf(fixture.first, fixture.next), startIndex = 0)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, fixture.nextResolutions)
        advanceTimeBy(179_000)
        fixture.engine.end()
        runCurrent()
        assertEquals("Next", fixture.session.nowPlaying.value.track?.title)
        assertEquals(1, fixture.nextResolutions, "three-minute transition must use the ready URL")
        assertEquals(2, fixture.engine.handles.size)
    }

    private class Fixture(test: TestScope) {
        val first = track("First", "Artist", provider = ProviderId.YOUTUBE_MUSIC).copy(durationMs = 180_000)
        val next = track("Next", "Artist", provider = ProviderId.YOUTUBE_MUSIC).copy(durationMs = 180_000)
        var nextResolutions = 0
        val engine = PrefetchEngine()
        val session = PlayerSession(engine, object : SourceResolver {
            override suspend fun resolve(track: Track, preferences: PlaybackPreferences): ResolvedPlayback {
                if (track.canonicalId == next.canonicalId) nextResolutions++
                return DefaultSourceResolver().resolve(track, preferences)
            }
        }, test.backgroundScope, enrichSource = { _, source ->
            source.copy(handle = PlaybackHandle.Url("https://mock/audio?expire=3600"))
        }, clock = { test.testScheduler.currentTime })
    }
}

private class PrefetchEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    val handles = mutableListOf<PlaybackHandle>()
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        handles += handle
        state.value = EngineState(status = EngineStatus.PLAYING, playGeneration = playGeneration)
    }
    fun end() { state.value = state.value.copy(status = EngineStatus.ENDED) }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = EngineState(playGeneration = state.value.playGeneration) }
    override fun setVolume(volume: Float) = Unit
}
