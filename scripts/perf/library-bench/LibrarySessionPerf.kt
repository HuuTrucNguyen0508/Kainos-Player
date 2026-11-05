package com.universalmusic.player.ui.screens

import com.universalmusic.player.domain.model.*
import com.universalmusic.player.domain.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

/** Mock extractor and sink, so this measures press-to-engine admission, never audible sound. */
class LibrarySessionPerf {
    @Test fun queuedYoutubeReadiness() = runBlocking {
        for (ready in listOf(false, true)) {
            repeat(10) { run ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val engine = BenchEngine()
                var extracts = 0
                val session = PlayerSession(engine, DefaultSourceResolver(), scope, enrichSource = { _, source ->
                    if (!ready) { delay(40); extracts++ }
                    source.copy(handle = PlaybackHandle.Url("https://invalid.test/mocked-audio"))
                })
                try {
                    val tracks = List(2) { i -> Track("youtube:$i", "Track $i", emptyList(), sources = listOf(
                        PlaybackSource(ProviderId.YOUTUBE_MUSIC, "video-$i", isPlayable = true,
                            handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, "video-$i")),
                    )) }
                    session.play(tracks, 0)
                    withTimeout(5_000) { engine.state.first { it.status == EngineStatus.PLAYING } }
                    val start = System.nanoTime()
                    session.skipToNext()
                    withTimeout(5_000) { engine.played.first { it >= 2 } }
                    val ms = (System.nanoTime() - start)/1e6
                    println("LIBRARY ${if (ready) "mockReadyPressToEngine" else "mockColdPressToEngine"}\t2\t$run\t$ms\t${engine.played.value}")
                    assertEquals(if (ready) 0 else 2, extracts)
                } finally { scope.cancel() }
            }
        }
    }
}

private class BenchEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    val played = MutableStateFlow(0)
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        state.value = EngineState(EngineStatus.PLAYING, playGeneration = playGeneration)
        played.value++
    }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = EngineState() }
    override fun setVolume(volume: Float) = Unit
}
