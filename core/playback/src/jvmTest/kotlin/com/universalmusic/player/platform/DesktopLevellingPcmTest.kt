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

/** Real mpv rendering raw PCM: levelling per file from tags or analysis, exact at the gapless boundary. */
class DesktopLevellingPcmTest {
    private val root: File = Files.createTempDirectory("kainos-levelling-").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    @BeforeTest fun tools() {
        for (tool in listOf("mpv", "ffmpeg", "ffprobe")) {
            assumeTrue("$tool is required", SystemDesktopPlaybackRuntime.findOnPath(tool) != null)
        }
    }

    /** A 441 Hz tone at 1/8 full scale. */
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

    /** [asUri] gives the `file:/path` form hearted-cache entries store (`File.toURI()`), not a plain path. */
    private fun track(index: Int, file: File, asUri: Boolean = false) = Track("$index", file.name, emptyList(), durationMs = SECONDS * 1_000L,
        sources = listOf(PlaybackSource(ProviderId.LOCAL, "$index", isPlayable = true,
            handle = PlaybackHandle.Url(if (asUri) file.toURI().toASCIIString() else file.absolutePath, durationMs = SECONDS * 1_000L))))

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
     * Plays [files] gaplessly, paced to real time like a sound card so every state lasts long
     * enough to observe. [whilePlaying] runs against the live process during playback.
     */
    private fun render(
        files: List<File>,
        levelling: Boolean,
        asUri: Boolean = false,
        loudness: LoudnessLookup = LoudnessLookup.None,
        whilePlaying: suspend (PlayerSession, (String) -> String?) -> Unit = { _, _ -> },
    ): Run = runBlocking {
        val output = File(root, "out-${System.nanoTime()}.raw")
        var socket = java.nio.file.Path.of("/tmp/nonexistent-levelling-proof")
        var spawns = 0
        val runtime = object : DesktopPlaybackRuntime by SystemDesktopPlaybackRuntime {
            override fun createIpcPath(): java.nio.file.Path = SystemDesktopPlaybackRuntime.createIpcPath().also { socket = it }
            override fun startProcess(command: List<String>): Process {
                spawns++
                // A reopened output would truncate the file and fail the sample count.
                return FlushingOnStop(output, 4L * FRAMES * files.size, ProcessBuilder(command.filter { it != "--really-quiet" } +
                    listOf("--no-config", "--ao=pcm", "--ao-pcm-waveheader=no", "--ao-pcm-file=${output.absolutePath}",
                        "--audio-format=float", "--audio-channels=mono", "--af-pre=@pace:lavfi=[arealtime]"))
                    .redirectOutput(File(root, "mpv-$spawns.log")).redirectErrorStream(true).start())
            }
        }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime, loudness = loudness)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = PlayerSession(engine, DefaultSourceResolver(), scope, confineTo = dispatcher,
            initialPreferences = PlaybackPreferences(volumeLevelling = levelling))
        val seen = mutableListOf<Levelling?>()
        val watcher = scope.launch { session.nowPlaying.collect { if (seen.lastOrNull() != it.levelling) seen += it.levelling } }
        try {
            session.play(files.mapIndexed { index, file -> track(index, file, asUri) })
            val property = { name: String -> runtime.readProperty(name, socket) }
            withTimeout(5_000) {
                // The session keeps one successor behind the playing file.
                while (property("playlist-count") != minOf(files.size, 2).toString()) delay(10)
                session.nowPlaying.first { it.levelling != null }
            }
            whilePlaying(session, property)
            withTimeout(20_000) {
                session.nowPlaying.first { it.track?.canonicalId == "${files.lastIndex}" && !it.isPlaying && !it.buffering }
            }
            assertNull(session.nowPlaying.value.error)
            watcher.cancel()
            engine.stop()
            val bytes = output.readBytes()
            val samples = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
            Run(samples, seen.filterNotNull(), spawns)
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

    @Test fun heartedCacheFileUrisPlayInOneProcessLikePaths() {
        val run = render(listOf(untagged(), quiet()), levelling = false, asUri = true)
        assertEquals(1, run.spawns, "The appended cache file must join the same mpv process")
        assertLevels(run.pcm, UNITY, UNITY)
    }

    @Test fun offPlaysEveryFileAtItsOwnLevel() {
        val run = render(listOf(quiet(), hot()), levelling = false)
        assertLevels(run.pcm, UNITY, UNITY)
        assertEquals(listOf(Levelling.Off), run.levelling)
    }

    @Test fun turningItOffReachesTheTrackAlreadyLoaded() {
        val run = render(listOf(quiet()), levelling = true) { session, property ->
            assertTrue(property("af").orEmpty().contains("volume=0.501187"))
            withTimeout(5_000) { while ((property("time-pos")?.toDoubleOrNull() ?: 0.0) < 1.0) delay(10) }
            session.updatePreferences(PlaybackPreferences(volumeLevelling = false))
            withTimeout(5_000) { while (!property("af").orEmpty().contains("volume=1.000000")) delay(10) }
        }
        assertEquals(FRAMES, run.pcm.size, "Changing the filter mid-track must not skip audio")
        assertEquals(MINUS_6_DB, peak(run.pcm, 0, 44_100 / 2), 0.0005, "Levelled before the switch")
        assertEquals(UNITY, peak(run.pcm, FRAMES - 44_100, FRAMES), 0.0005, "Own level after the switch")
        assertEquals(listOf(Levelling.Applied(-6.0), Levelling.Off), run.levelling)
    }

    /** Measurements keyed by file name, as the loudness repository keys them by path. */
    private fun measured(vararg entries: Pair<File, TrackLoudness>) = LoudnessLookup { location ->
        entries.firstOrNull { (file, _) -> location.endsWith(file.name) }?.second
    }

    @Test fun analysedLoudnessLevelsUntaggedFilesAndTagsStillWin() {
        val untagged = untagged()
        val quiet = quiet()
        // -12 LUFS wants -18 - (-12) + 7 = +1 dB; the -6 dBTP peak allows it. Rounded to +1 dB below.
        val measuredLoud = TrackLoudness(MeasuredLoudness(-12.0, -6.0))
        val run = render(listOf(untagged, quiet), levelling = true,
            loudness = measured(untagged to measuredLoud, quiet to TrackLoudness(MeasuredLoudness(-30.0, -20.0))))
        assertEquals(1, run.spawns)
        assertLevels(run.pcm, UNITY * 1.122018, MINUS_6_DB)
        assertEquals(listOf(Levelling.Applied(1.0, GainSource.ANALYSED), Levelling.Applied(-6.0)), run.levelling)
    }

    @Test fun analysedBoostStopsOneDecibelBelowTheTruePeak() {
        val untagged = untagged()
        // -30 LUFS wants +19 dB; a -7 dBTP true peak allows only +6 dB.
        val run = render(listOf(untagged), levelling = true,
            loudness = measured(untagged to TrackLoudness(MeasuredLoudness(-30.0, -7.0))))
        assertEquals(UNITY * 1.995262, peak(run.pcm, 0, FRAMES), 0.0005)
        assertEquals(listOf(Levelling.Applied(6.0, GainSource.ANALYSED)), run.levelling)
    }

    @Test fun aGaplessJoinInsideAnAlbumUsesTheAlbumGain() {
        val first = untagged()
        val second = tone("second.flac")
        val third = tone("third.flac")
        val album = MeasuredLoudness(-15.0, -6.0) // +4 dB
        val run = render(listOf(first, second, third), levelling = true, loudness = measured(
            first to TrackLoudness(MeasuredLoudness(-9.0, -6.0), album, "album"), // -2 dB on its own
            second to TrackLoudness(MeasuredLoudness(-21.0, -6.0), album, "album"), // album, not +5 dB (capped)
            third to TrackLoudness(MeasuredLoudness(-11.0, -6.0), album, "other"), // another album: its own 0 dB
        ))
        assertEquals(1, run.spawns)
        assertLevels(run.pcm, UNITY * 0.794328, UNITY * 1.584893, UNITY)
        assertEquals(listOf(-2.0, 4.0, 0.0), run.levelling.map { (it as Levelling.Applied).gainDb })
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
