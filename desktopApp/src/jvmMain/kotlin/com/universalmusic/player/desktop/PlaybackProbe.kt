package com.universalmusic.player.desktop

import com.universalmusic.player.domain.model.*
import com.universalmusic.player.domain.playback.*
import com.universalmusic.player.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** Isolated, packaged-app probe; never connects to a provider or touches the user's library. */
internal fun runPlaybackProbe(paths: List<String>) = runBlocking {
    require(paths.size >= 3)
    val worker = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val delegate = createPlaybackEngine(SpotifyPlaybackController({}, {}, {}, {}))
    val engine = object : PlaybackEngine by delegate {
        override fun pause() {
            println("PERF\tengine_pause\t${System.nanoTime()}")
            delegate.pause()
        }
        override fun resume() {
            println("PERF\tengine_resume\t${System.nanoTime()}")
            delegate.resume()
        }
    }
    val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = PlayerSession(engine, DefaultSourceResolver { false }, probeScope,
        confineTo = worker, workContext = Dispatchers.Default)
    val tracks = paths.mapIndexed { i, path ->
        val handle = PlaybackHandle.Url(File(path).absolutePath, durationMs = 2_000)
        Track("probe:$i", "Probe $i", emptyList(), durationMs = 2_000,
            sources = listOf(PlaybackSource(ProviderId.LOCAL, "probe:$i", isPlayable = true, handle = handle)))
    }
    fun mark(name: String) = println("PERF\t$name\t${System.nanoTime()}")
    suspend fun playing() = withTimeout(15_000) { engine.state.first { it.status == EngineStatus.PLAYING } }
    try {
        mark("session_play")
        session.play(tracks)
        playing()
        delay(300)
        mark("pause")
        session.pauseTransport()
        delay(300)
        mark("resume")
        session.playTransport()
        delay(300)
        mark("seek")
        session.seekTo(500)
        delay(300)
        session.skipToNext()
        withTimeout(15_000) { session.nowPlaying.first { it.track?.canonicalId == "probe:2" } }
        withTimeout(15_000) { session.nowPlaying.first { it.track?.canonicalId == "probe:2" && !it.isPlaying && !it.buffering } }
        mark("natural_end")
        // The same real session and transport, with 512 entries paused.
        session.play(List(512) { tracks[it % tracks.size] })
        playing()
        delay(300)
        session.pauseTransport()
        delay(500)
        mark("idle_begin")
        delay(5_000)
        mark("idle_end")
        delay(200)
        check(engine.state.value.error == null) { engine.state.value.error.orEmpty() }
        mark("complete")
    } finally {
        session.stopTransport()
        engine.stop()
        probeScope.cancel()
        worker.close()
    }
}
