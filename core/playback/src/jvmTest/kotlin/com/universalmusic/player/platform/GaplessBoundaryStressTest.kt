package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.*

/** Opt in with KAINOS_GAPLESS_STRESS=1; private null output, synthetic files only. */
class GaplessBoundaryStressTest {
    @Test fun shortSuccessorCannotPublishBeforeOutgoingOutput() = runBlocking {
        if (System.getenv("KAINOS_GAPLESS_STRESS") != "1") return@runBlocking
        val root = Files.createTempDirectory("kainos-short-proof-")
        fun tone(name: String, seconds: String): Path = root.resolve("$name.wav").also {
            check(ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=$seconds", it.toString()).start().waitFor() == 0)
        }
        val a = tone("a", "2")
        val b = tone("b", "0.05")
        val runtime = privateRuntime()
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
        try {
            engine.configureGapless(true)
            val start = System.nanoTime()
            engine.play(PlaybackHandle.Url(a.toString()), null, 1)
            engine.prepareNext(PreparedPlayback("b", PlaybackHandle.Url(b.toString()), null, 1))
            withTimeout(5_000) { while (engine.state.value.playGeneration != 2L) {
                assertTrue(engine.state.value.status != EngineStatus.FAILED, engine.state.value.toString())
                delay(1)
            } }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            assertTrue(elapsed >= 2_000, "Successor published early at ${elapsed}ms")
            assertEquals("b", engine.state.value.seamlessItemId)
            println("SHORT_SUCCESSOR elapsed_ms=$elapsed identity=b")
        } finally { engine.stop(); root.toFile().deleteRecursively() }
    }

    @Test fun randomizedBoundaryTransport() = runBlocking {
        if (System.getenv("KAINOS_GAPLESS_STRESS") != "1") return@runBlocking
        val root = Files.createTempDirectory("kainos-stress-")
        val files = listOf("0.2", "0.35", "0.7", "1.1", "2").mapIndexed { i, duration ->
            root.resolve("$i.wav").also {
                check(ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "sine=frequency=${440+i*110}:duration=$duration", it.toString()).start().waitFor() == 0)
            }
        }
        var socket = root.resolve("none")
        val runtime = privateRuntime { socket = it }
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
        val random = Random(20261031)
        var generation = 100L
        var owner = files[0].toString()
        var samples = 0
        var unresolved = 0
        val counts = IntArray(5)
        suspend fun play(index: Int) {
            owner = files[index].toString()
            engine.play(PlaybackHandle.Url(owner), null, ++generation)
        }
        suspend fun sample() {
            repeat(30) {
                val before = engine.state.value
                val pts = runtime.readProperty("audio-pts", socket)?.toDoubleOrNull()
                val path = runtime.readProperty("path", socket)
                val after = engine.state.value
                if (after.playGeneration != before.playGeneration) {
                    owner = after.seamlessItemId ?: owner
                    generation = after.playGeneration
                    return@repeat
                }
                if (after.seamlessItemId != null) owner = after.seamlessItemId!!
                generation = after.playGeneration
                if (pts == null || path == null || pts < 0 || after.status != EngineStatus.PLAYING) {
                    unresolved++
                    return
                }
                if (path == owner) { samples++; return }
                // The output-clock query and the engine event reader are separate IPC clients.
                // Re-read up to 30 ms to settle their ordering; never discard a persistent mismatch.
                delay(1)
            }
            val actual = runtime.readProperty("path", socket)
            assertEquals(owner, actual, "Reported owner differs from mpv after event settlement")
            samples++
        }
        try {
            engine.configureGapless(true)
            play(0)
            repeat(240) { step ->
                sample()
                if (engine.state.value.status in listOf(EngineStatus.ENDED, EngineStatus.FAILED)) play(random.nextInt(files.size))
                val action = random.nextInt(5)
                counts[action]++
                when (action) {
                    0 -> {
                        val next = if (step % 7 == 0) owner else files[random.nextInt(files.size)].toString()
                        engine.prepareNext(PreparedPlayback(next, PlaybackHandle.Url(next), null, generation))
                    }
                    1 -> play(random.nextInt(files.size))
                    2 -> engine.seekTo(random.nextLong(0, 180))
                    3 -> engine.pause()
                    4 -> engine.resume()
                }
                delay(random.nextLong(5, 100))
                sample()
                if (step % 12 == 0) {
                    engine.resume()
                    engine.prepareNext(PreparedPlayback(files[0].toString(), PlaybackHandle.Url(files[0].toString()), null, generation))
                    delay(220)
                    sample()
                }
            }
            assertTrue(samples > 100, "Insufficient resolved output samples: $samples")
            assertTrue(counts.all { it > 0 })
            println("STRESS seed=20261031 operations=240 counts=${counts.toList()} matched_samples=$samples unresolved_samples=$unresolved settlement_ms=30")
        } finally { engine.stop(); root.toFile().deleteRecursively() }
    }

    private fun privateRuntime(onSocket: (Path) -> Unit = {}): DesktopPlaybackRuntime =
        object : DesktopPlaybackRuntime by SystemDesktopPlaybackRuntime {
            override fun createIpcPath(): Path = SystemDesktopPlaybackRuntime.createIpcPath().also(onSocket)
            override fun startProcess(command: List<String>): Process = ProcessBuilder(command +
                listOf("--no-config", "--ao=null")).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }
}
