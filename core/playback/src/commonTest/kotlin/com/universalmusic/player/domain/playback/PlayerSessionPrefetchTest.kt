package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.*
import kotlinx.coroutines.delay
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

    @Test
    fun rejectedPrefetchRetriesFreshOnceForThrownAndAsynchronousFailures() = runTest {
        for (asynchronous in listOf(false, true)) {
            for (rejectFresh in listOf(false, true)) {
                val fixture = Fixture(this)
                fixture.engine.onPlay = { attempt ->
                    if ((!asynchronous && attempt == 2) || (rejectFresh && attempt == 3)) {
                        error("rejected URL")
                    }
                }
                fixture.session.play(listOf(fixture.first, fixture.next), startIndex = 0)
                runCurrent()
                advanceTimeBy(1_001)
                runCurrent()
                fixture.engine.end()
                runCurrent()
                if (asynchronous) {
                    fixture.engine.state.value = fixture.engine.state.value.copy(
                        status = EngineStatus.FAILED, error = "rejected URL",
                    )
                    runCurrent()
                }
                assertEquals(2, fixture.nextResolutions, "must freshly resolve after prefetch rejection")
                assertEquals(3, fixture.engine.handles.size, "fresh retry is bounded to one")
                assertEquals(!rejectFresh, fixture.session.nowPlaying.value.isPlaying)
                assertEquals("Next", fixture.session.nowPlaying.value.track?.title)
            }
        }
    }

    @Test
    fun aPrefetchedUrlFailingMidTrackResumesWhereItStopped() = runTest {
        val fixture = Fixture(this)
        fixture.session.play(listOf(fixture.first, fixture.next), startIndex = 0)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        fixture.engine.end()
        runCurrent()
        fixture.engine.state.value = fixture.engine.state.value.copy(status = EngineStatus.PLAYING, positionMs = 42_000)
        runCurrent()

        fixture.engine.state.value = fixture.engine.state.value.copy(status = EngineStatus.FAILED, error = "rejected URL")
        runCurrent()

        assertEquals(3, fixture.engine.handles.size)
        assertEquals(42_000, fixture.engine.state.value.positionMs)
        assertEquals("Next", fixture.session.nowPlaying.value.track?.title)
    }

    @Test
    fun queueMetadataKeepsExtractionAndChangedTargetReschedulesWithoutStalePlayback() = runTest {
        val fixture = Fixture(this)
        fixture.enrichDelayMs = 2_000
        fixture.session.play(listOf(fixture.first.copy(durationMs = null), fixture.next), startIndex = 0)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, fixture.nextResolutions)
        fixture.engine.state.value = fixture.engine.state.value.copy(durationMs = 180_000)
        fixture.session.addToQueue(fixture.first)
        fixture.session.cycleRepeat()
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        fixture.engine.end()
        runCurrent()
        assertEquals(1, fixture.nextResolutions, "duration, append and repeat emissions must preserve extraction")

        val changed = Fixture(this)
        changed.enrichDelayMs = 2_000
        changed.session.play(listOf(changed.first, changed.next), startIndex = 0)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        val inserted = changed.next.copy(canonicalId = "inserted", title = "Inserted")
        changed.session.playNext(inserted)
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        changed.engine.end()
        runCurrent()
        assertEquals("Inserted", changed.session.nowPlaying.value.track?.title)
        assertEquals(1, changed.resolutions["inserted"], "changed target must be prefetched again")

        // Replacing the queue during extraction must drop its late result, even for the same song.
        val replaced = Fixture(this)
        replaced.enrichDelayMs = 2_000
        replaced.session.play(listOf(replaced.first, replaced.next), startIndex = 0)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        replaced.session.play(listOf(replaced.first, replaced.next), startIndex = 0)
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        replaced.engine.end()
        runCurrent()
        assertEquals(2, replaced.nextResolutions, "replacement needs its own extraction")
        assertEquals("Next", replaced.session.nowPlaying.value.track?.title)
    }

    private class Fixture(test: TestScope) {
        val first = track("First", "Artist", provider = ProviderId.YOUTUBE_MUSIC).copy(durationMs = 180_000)
        val next = track("Next", "Artist", provider = ProviderId.YOUTUBE_MUSIC).copy(durationMs = 180_000)
        var enrichDelayMs = 0L
        val resolutions = mutableMapOf<String, Int>()
        var nextResolutions = 0
        val engine = PrefetchEngine()
        val session = PlayerSession(engine, object : SourceResolver {
            override suspend fun resolve(track: Track, preferences: PlaybackPreferences): ResolvedPlayback {
                resolutions[track.canonicalId] = (resolutions[track.canonicalId] ?: 0) + 1
                if (track.canonicalId == next.canonicalId) nextResolutions++
                return DefaultSourceResolver().resolve(track, preferences)
            }
        }, test.backgroundScope, enrichSource = { track, source ->
            if (track.canonicalId != first.canonicalId) delay(enrichDelayMs)
            source.copy(handle = PlaybackHandle.Url("https://mock/audio?expire=3600"))
        }, clock = { test.testScheduler.currentTime })
    }
}

private class PrefetchEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    val handles = mutableListOf<PlaybackHandle>()
    var onPlay: (Int) -> Unit = {}
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        handles += handle
        onPlay(handles.size)
        state.value = EngineState(status = EngineStatus.PLAYING, playGeneration = playGeneration)
    }
    fun end() { state.value = state.value.copy(status = EngineStatus.ENDED) }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = EngineState(playGeneration = state.value.playGeneration) }
    override fun setVolume(volume: Float) = Unit
}
