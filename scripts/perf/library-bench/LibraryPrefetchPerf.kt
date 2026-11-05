package com.universalmusic.player.ui.screens

import com.universalmusic.player.domain.model.*
import com.universalmusic.player.domain.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPrefetchPerf {
    @Test fun readySkipAndReplacedQueue() = runTest {
        val engine = PrefetchEngine()
        val requests = mutableListOf<String>()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope, enrichSource = { track, source ->
            requests += track.canonicalId
            withContext(NonCancellable) { delay(40) }
            source.copy(handle = PlaybackHandle.Url("https://mock.test/${track.canonicalId}"))
        })
        val tracks = youtubeQueue()
        session.play(tracks, 0)
        runCurrent(); advanceTimeBy(50); runCurrent()
        advanceTimeBy(1_100); runCurrent()
        assertEquals(listOf("youtube:0", "youtube:1"), requests)
        session.skipToNext(); runCurrent()
        assertEquals("https://mock.test/youtube:1", engine.urls.last())
        assertEquals(2, requests.size, "skip must consume the prefetched stream without extracting again")

        session.play(tracks, 0); runCurrent(); advanceTimeBy(50); runCurrent()
        advanceTimeBy(1_010); runCurrent() // extractor in progress
        val replacement = listOf(tracks[0].copy(canonicalId = "youtube:replacement"))
        val beforeReplacement = engine.urls.size
        session.play(replacement, 0); runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("https://mock.test/youtube:replacement"), engine.urls.drop(beforeReplacement))
        assertEquals("https://mock.test/youtube:replacement", engine.urls.last())
        assertEquals("youtube:replacement", session.nowPlaying.value.track?.canonicalId)
    }

    @Test fun expiredPrefetchIsResolvedAgain() = runTest {
        val engine = PrefetchEngine()
        var clock = 0L
        var extracts = 0
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope, clock = { clock },
            enrichSource = { track, source ->
                extracts++
                source.copy(handle = PlaybackHandle.Url("https://mock.test/${track.canonicalId}"))
            })
        session.play(youtubeQueue(), 0); runCurrent()
        advanceTimeBy(1_100); runCurrent()
        assertEquals(2, extracts)
        clock = 31_000
        session.skipToNext(); runCurrent()
        assertEquals(3, extracts)
        assertEquals("https://mock.test/youtube:1", engine.urls.last())
    }

    @Test fun skipLatency() = runBlocking {
        for (ready in listOf(false, true)) repeat(10) { run ->
            // Single thread mirrors the production session confinement.
            val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = PrefetchEngine()
            val extracted = MutableStateFlow(0)
            val session = PlayerSession(engine, DefaultSourceResolver(), scope, confineTo = dispatcher,
                workContext = Dispatchers.Default, enrichSource = { track, source ->
                    delay(40)
                    extracted.value++
                    source.copy(handle = PlaybackHandle.Url("https://mock.test/${track.canonicalId}"))
                })
            try {
                session.play(youtubeQueue(), 0)
                withTimeout(5_000) { engine.count.first { it == 1 } }
                if (ready) {
                    withTimeout(5_000) { extracted.first { it == 2 } }
                    withContext(dispatcher) { } // allow publication after worker return
                    delay(5)
                }
                val start = System.nanoTime()
                session.skipToNext()
                withTimeout(5_000) { engine.count.first { it == 2 } }
                val ms = (System.nanoTime()-start)/1e6
                assertEquals("https://mock.test/youtube:1", engine.urls.last())
                assertEquals(2, extracted.value)
                println("LIBRARY prefetchSkip:${if(ready) "ready" else "cold"}\t2\t$run\t$ms\t${extracted.value}")
            } finally { scope.cancel(); dispatcher.close() }
        }
    }
}
private fun youtubeQueue() = List(2) { i -> Track("youtube:$i", "Track $i", emptyList(), sources = listOf(
    PlaybackSource(ProviderId.YOUTUBE_MUSIC, "video-$i", isPlayable = true,
        handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, "video-$i")))) }
private class PrefetchEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    val count = MutableStateFlow(0)
    val urls = mutableListOf<String>()
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        urls += (handle as PlaybackHandle.Url).url
        state.value = EngineState(EngineStatus.PLAYING, playGeneration = playGeneration)
        count.value++
    }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = EngineState() }
    override fun setVolume(volume: Float) = Unit
}
