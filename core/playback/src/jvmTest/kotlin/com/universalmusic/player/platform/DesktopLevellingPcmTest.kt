package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.*
import com.universalmusic.player.domain.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.*
import org.junit.Assume.assumeTrue

/** Real mpv rendering raw PCM: ReplayGain levelling per file, exact at the gapless boundary. */
class DesktopLevellingPcmTest {
    private val root: File = Files.createTempDirectory("kainos-levelling-").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    @BeforeTest fun tools() {
        for (tool in listOf("mpv", "ffmpeg", "ffprobe")) {
            assumeTrue("$tool is required", SystemDesktopPlaybackRuntime.findOnPath(tool) != null)
        }
    }

    /** A 441 Hz tone at 1/8 full scale, longer than ao_pcm's 65536-sample buffer. */
    private fun tone(name: String, vararg tags: Pair<String, String>): File = File(root, name).also { file ->
        val command = mutableListOf("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=441:sample_rate=44100:duration=$SECONDS")
        tags.forEach { (key, value) -> command += listOf("-metadata", "$key=$value") }
        command += listOf("-c:a", "flac", file.absolutePath)
        assertEquals(0, ProcessBuilder(command).inheritIO().start().waitFor())
    }

    private fun quiet() = tone("quiet.flac", "REPLAYGAIN_TRACK_GAIN" to "-13.00 dB", "REPLAYGAIN_TRACK_PEAK" to "0.100000")
    private fun untagged() = tone("untagged.flac")
    /** +3 dB plus the 7 dB preamp asks for +10 dB; the 0.5 peak allows only +6.02 dB. */
    private fun hot() = tone("hot.flac", "REPLAYGAIN_TRACK_GAIN" to "+3.00 dB", "REPLAYGAIN_TRACK_PEAK" to "0.500000")

    private fun track(index: Int, file: File) = Track("$index", file.name, emptyList(), durationMs = SECONDS * 1_000L,
        sources = listOf(PlaybackSource(ProviderId.LOCAL, "$index", isPlayable = true,
            handle = PlaybackHandle.Url(file.absolutePath, durationMs = SECONDS * 1_000L))))

    private companion object {
        const val SECONDS = 3
        const val FRAMES = 44_100 * SECONDS
        const val UNITY = 0.125
        const val MINUS_6_DB = 0.125 * 0.501187
        const val PLUS_6_DB = 0.25
    }

    private class Run(val pcm: FloatArray, val levelling: List<Levelling>, val spawns: Int)

    /**
     * The session stops mpv as soon as the last file's decoder ends, and a kill then loses what
     * ao_pcm had not written yet. Stopping waits for the whole render, then lets mpv close the file.
     */
    private class FlushingOnStop(private val output: File, private val bytes: Long, private val process: Process) : Process() {
        override fun getOutputStream() = process.outputStream
        override fun getInputStream() = process.inputStream
        override fun getErrorStream() = process.errorStream
        override fun waitFor() = process.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit) = process.waitFor(timeout, unit)
        override fun exitValue() = process.exitValue()
        override fun destroy() = process.destroy()
        override fun isAlive() = process.isAlive
        override fun destroyForcibly(): Process {
            val deadline = System.currentTimeMillis() + 5_000
            while (output.length() < bytes && process.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(10)
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            return this
        }
    }

    /**
     * Plays [files] gaplessly from a paused start. With [whilePlaying], mpv is paced to real time
     * and the block runs against the live process during playback.
     */
    private fun render(
        files: List<File>,
        levelling: Boolean,
        whilePlaying: (suspend (PlayerSession, (String) -> String?) -> Unit)? = null,
    ): Run = runBlocking {
        val output = File(root, "out-${System.nanoTime()}.raw")
        var socket = java.nio.file.Path.of("/tmp/nonexistent-levelling-proof")
        var spawns = 0
        val runtime = object : DesktopPlaybackRuntime by SystemDesktopPlaybackRuntime {
            override fun createIpcPath(): java.nio.file.Path = SystemDesktopPlaybackRuntime.createIpcPath().also { socket = it }
            override fun startProcess(command: List<String>): Process {
                spawns++
                // Unpaced runs start paused until the successor is queued; a paced one plays in real
                // time like a sound card. A reopened output would truncate the file and fail the count.
                return FlushingOnStop(output, 4L * FRAMES * files.size, ProcessBuilder(command.filter { it != "--really-quiet" } +
                    listOfNotNull("--pause".takeIf { whilePlaying == null }) + listOf("--no-config", "--ao=pcm",
                    "--ao-pcm-waveheader=no", "--ao-pcm-file=${output.absolutePath}",
                    "--audio-format=float", "--audio-channels=mono") +
                    listOfNotNull("--af-pre=@pace:lavfi=[arealtime]".takeIf { whilePlaying != null }))
                    .redirectOutput(File(root, "mpv-$spawns.log")).redirectErrorStream(true).start())
            }
        }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = PlayerSession(engine, DefaultSourceResolver(), scope, confineTo = dispatcher,
            initialPreferences = PlaybackPreferences(volumeLevelling = levelling))
        val seen = mutableListOf<Levelling?>()
        val watcher = scope.launch { session.nowPlaying.collect { if (seen.lastOrNull() != it.levelling) seen += it.levelling } }
        try {
            session.play(files.mapIndexed(::track))
            val property = { name: String -> runtime.readProperty(name, socket) }
            withTimeout(5_000) {
                while (property("playlist-count") != files.size.toString()) delay(10)
                session.nowPlaying.first { it.levelling != null }
            }
            assertTrue(runtime.sendIpc("""["set_property","pause",false]""", socket))
            whilePlaying?.invoke(session, property)
            withTimeout(20_000) {
                session.nowPlaying.first { it.track?.canonicalId == "${files.lastIndex}" && !it.isPlaying && !it.buffering }
            }
            assertNull(session.nowPlaying.value.error)
            watcher.cancel()
            engine.stop()
            val bytes = output.readBytes()
            val samples = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
            // The watcher may miss a short-lived state, so the final value is read from the session.
            Run(samples, seen.filterNotNull().take(1) + listOfNotNull(session.nowPlaying.value.levelling), spawns)
        } finally {
            watcher.cancel()
            engine.stop()
            scope.cancel()
            dispatcher.close()
        }
    }

    private fun peak(pcm: FloatArray, from: Int, to: Int): Double = (from until to).maxOf { abs(pcm[it].toDouble()) }

    /** Every 10 ms window of each file sits at its own level, up to the boundary sample. */
    private fun assertLevels(pcm: FloatArray, vararg peaks: Double) {
        assertEquals(FRAMES * peaks.size, pcm.size, "No sample may be dropped or inserted at a boundary")
        peaks.forEachIndexed { file, expected ->
            for (window in 0 until FRAMES / 441) {
                val start = file * FRAMES + window * 441
                val actual = peak(pcm, start, start + 441)
                assertEquals(expected, actual, 0.0005, "file $file window $window")
            }
        }
    }

    @Test fun tagGainChangesOnTheFirstSampleOfTheNextGaplessFile() {
        val run = render(listOf(quiet(), untagged()), levelling = true)
        assertEquals(1, run.spawns, "The boundary must stay inside one mpv process")
        assertLevels(run.pcm, MINUS_6_DB, UNITY)
        assertEquals(listOf(Levelling.Applied(-6.0), Levelling.Untagged), run.levelling)
    }

    @Test fun peakLimitCapsTheGainAcrossTaggedFiles() {
        val run = render(listOf(hot(), quiet()), levelling = true)
        assertEquals(1, run.spawns)
        assertLevels(run.pcm, PLUS_6_DB, MINUS_6_DB)
        assertEquals(6.0206, (run.levelling.first() as Levelling.Applied).gainDb, 0.0001)
        assertEquals(Levelling.Applied(-6.0), run.levelling.last())
    }

    @Test fun offPlaysEveryFileAtItsOwnLevel() {
        val run = render(listOf(quiet(), hot()), levelling = false)
        assertLevels(run.pcm, UNITY, UNITY)
        assertEquals(listOf(Levelling.Off, Levelling.Off), run.levelling)
    }

    @Test fun turningItOffReachesTheTrackAlreadyLoaded() {
        val run = render(listOf(quiet()), levelling = true) { session, property ->
            assertTrue(property("af").orEmpty().contains("replaygain=track"))
            withTimeout(5_000) { while ((property("time-pos")?.toDoubleOrNull() ?: 0.0) < 1.0) delay(10) }
            session.updatePreferences(PlaybackPreferences(volumeLevelling = false))
            withTimeout(5_000) { while (property("af").orEmpty().contains("replaygain=track")) delay(10) }
        }
        assertEquals(FRAMES, run.pcm.size, "Changing the filter mid-track must not skip audio")
        assertEquals(MINUS_6_DB, peak(run.pcm, 0, 44_100 / 2), 0.0005, "Levelled before the switch")
        assertEquals(UNITY, peak(run.pcm, FRAMES - 44_100, FRAMES), 0.0005, "Own level after the switch")
        assertEquals(listOf(Levelling.Applied(-6.0), Levelling.Off), run.levelling)
    }

    @Test fun ffprobeReportsOnlyGainFfmpegApplies() {
        assertEquals(ReplayGain(-13.0, 0.1), SystemDesktopPlaybackRuntime.probeReplayGain(quiet().absolutePath))
        assertNull(SystemDesktopPlaybackRuntime.probeReplayGain(untagged().absolutePath))
        val opus = File(root, "tagged.opus")
        assertEquals(0, ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=duration=1",
            "-metadata", "REPLAYGAIN_TRACK_GAIN=-13.00 dB", "-c:a", "libopus", opus.absolutePath).inheritIO().start().waitFor())
        assertNull(SystemDesktopPlaybackRuntime.probeReplayGain(opus.absolutePath), "ffmpeg exports no ReplayGain for Opus")
        assertNull(SystemDesktopPlaybackRuntime.probeReplayGain("https://example.invalid/stream.flac"))
    }
}
