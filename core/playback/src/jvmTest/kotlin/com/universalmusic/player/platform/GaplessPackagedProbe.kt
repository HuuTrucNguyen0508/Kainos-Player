package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.*
import com.universalmusic.player.domain.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.Executors

/** Launch against immutable packaged jars with synthetic files and private mpv configuration. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 3)
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    var mpv: Process? = null
    val runtime = object : DesktopPlaybackRuntime by SystemDesktopPlaybackRuntime {
        override fun startProcess(command: List<String>): Process =
            SystemDesktopPlaybackRuntime.startProcess(command).also { mpv = it }
    }
    val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
    val session = PlayerSession(engine, DefaultSourceResolver(), scope, confineTo = dispatcher)
    val tracks = args.mapIndexed { i, path -> Track("$i", "Tone $i", emptyList(), sources = listOf(
        PlaybackSource(ProviderId.LOCAL, "$i", isPlayable = true, handle = PlaybackHandle.Url(File(path).absolutePath)))) }
    suspend fun await(predicate: (NowPlayingState) -> Boolean) = withTimeout(20_000) { session.nowPlaying.first(predicate) }
    try {
        session.play(tracks)
        await { it.track?.canonicalId == "2" && !it.isPlaying && !it.buffering }
        check(engine.state.value.playGeneration >= 3)
        println("PROOF three natural tracks complete")
        session.play(tracks)
        await { it.isPlaying }
        session.pauseTransport()
        await { !it.isPlaying }
        session.seekTo(700)
        delay(100)
        check(engine.state.value.positionMs == 700L)
        session.playTransport()
        await { it.isPlaying }
        session.skipToNext()
        await { it.track?.canonicalId == "1" && it.isPlaying }
        println("PROOF pause seek resume skip complete")
        // Kill only the process owned by this probe, then require a same-track recovery.
        val dead = requireNotNull(mpv)
        val generation = engine.state.value.playGeneration
        dead.destroyForcibly()
        withTimeout(10_000) {
            while (mpv === dead || engine.state.value.playGeneration <= generation ||
                engine.state.value.status != EngineStatus.PLAYING) delay(10)
        }
        check(session.nowPlaying.value.track?.canonicalId == "1")
        println("PROOF process death recovered")
        session.play(listOf(tracks[0]))
        await { it.track?.canonicalId == "0" && it.isPlaying }
        session.cycleRepeat(); session.cycleRepeat()
        delay(200)
        val repeating = engine.state.value.playGeneration
        withTimeout(10_000) { engine.state.first { it.playGeneration > repeating } }
        check(session.nowPlaying.value.track?.canonicalId == "0")
        session.cycleRepeat()
        await { !it.isPlaying && !it.buffering }
        println("PROOF repeat one and queue end complete")
        session.play(listOf(tracks[0], tracks[1].copy(sources = listOf(tracks[1].sources[0].copy(
            handle = PlaybackHandle.Url("/nonexistent-kainos-gapless-fixture.flac")))), tracks[2]))
        await { it.track?.canonicalId == "2" && it.isPlaying }
        await { it.track?.canonicalId == "2" && !it.isPlaying && !it.buffering }
        println("PROOF missing successor skipped without losing current")
    } finally {
        engine.stop(); scope.cancel(); dispatcher.close()
    }
}
