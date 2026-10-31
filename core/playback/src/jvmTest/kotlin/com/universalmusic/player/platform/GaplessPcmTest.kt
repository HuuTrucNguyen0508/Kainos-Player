package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.*
import com.universalmusic.player.domain.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.*

/** Opt-in real-engine proof. scripts/perf/gapless-pcm.py supplies synthetic files and PCM output. */
class GaplessPcmTest {
    @Test fun renderContinuousFiles() = runBlocking {
        val root = System.getenv("KAINOS_GAPLESS_PCM")?.let(::File) ?: return@runBlocking
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            for (pair in listOf("same")) repeat(10) { run ->
                val output = File(root, "$pair-$run.raw")
                var runtimeSocket = java.nio.file.Path.of("/tmp/nonexistent-gapless-proof")
                var spawns = 0
                val runtime = object : DesktopPlaybackRuntime by SystemDesktopPlaybackRuntime {
                    override fun createIpcPath(): java.nio.file.Path = SystemDesktopPlaybackRuntime.createIpcPath().also { runtimeSocket = it }
                    override fun startProcess(command: List<String>): Process {
                        spawns++
                        return ProcessBuilder(command.filter { it != "--really-quiet" } + listOf("--no-config", "--pause", "--ao=pcm",
                            "--ao-pcm-waveheader=no", "--ao-pcm-file=${output.absolutePath}",
                            "--audio-format=s16", "--audio-channels=mono"))
                            .redirectOutput(File(root, "$pair-$run.mpv.log")).redirectErrorStream(true).start()
                    }
                }
                val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                val session = PlayerSession(engine, DefaultSourceResolver(), scope, confineTo = dispatcher)
                val tracks = (0..1).map { index ->
                    val file = File(root, "$pair-$index.flac")
                    Track("$index", "Tone $index", emptyList(), durationMs = 123_000,
                        sources = listOf(PlaybackSource(ProviderId.LOCAL, "$index", isPlayable = true,
                            handle = PlaybackHandle.Url(file.absolutePath, durationMs = 123_000))))
                }
                try {
                    session.play(tracks)
                    withTimeout(5_000) {
                        while (runtime.readProperty("playlist-count", runtimeSocket) != "2") delay(10)
                    }
                    engine.pause()
                    engine.seekTo(500)
                    withTimeout(5_000) {
                        while (runtime.readProperty("time-pos", runtimeSocket)?.toDoubleOrNull() != 0.5) delay(10)
                        while (runtime.readProperty("playlist-count", runtimeSocket) != "2") delay(10)
                    }
                    engine.resume()
                    withTimeout(20_000) {
                        session.nowPlaying.first { it.track?.canonicalId == "1" && !it.isPlaying && !it.buffering }
                    }
                    assertEquals(1, spawns, "Seek and boundary must preserve the process")
                    assertNull(session.nowPlaying.value.error)
                    assertEquals(2_000, session.nowPlaying.value.durationMs, "Fresh file metadata")
                    assertEquals(2, session.queueState.value.items.size)
                    println("GAPLESS_PCM $pair $run ${output.length()}")
                } finally {
                    engine.stop()
                    scope.cancel()
                }
            }
        } finally { dispatcher.close() }
    }
}
