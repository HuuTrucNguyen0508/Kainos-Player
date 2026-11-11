package com.universalmusic.player.data.local

import com.universalmusic.player.domain.playback.MeasuredLoudness
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real ffmpeg over generated tones: what the desktop pass stores for a file. */
class FfmpegLoudnessAnalyzerTest {
    private fun tone(dir: File, name: String, amplitude: Double): File = File(dir, name).also { file ->
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
            "sine=frequency=1000:sample_rate=48000:duration=5", "-af", "volume=$amplitude", "-c:a", "flac", file.absolutePath)
            .inheritIO().start()
        assertEquals(0, process.waitFor())
    }

    @Test fun measuresAToneAndItsFileUriAlike() = runBlocking<Unit> {
        val analyzer = createLocalLoudnessAnalyzer()
        assumeTrue("ffmpeg is required", analyzer != null)
        val dir = Files.createTempDirectory("kainos-loudness-").toFile()
        try {
            val file = tone(dir, "a tone.flac", 0.5)
            val measured = assertNotNull(analyzer!!.measure(file.absolutePath))
            // ffmpeg's sine is 1/8 of full scale (-18.1 dB), halved here (-6.0 dB). A full-scale
            // 1 kHz sine reads -3.0 LUFS, so this one reads -27.1 LUFS with a -24.1 dBTP peak.
            assertEquals(-27.1, measured.integratedLufs, 0.2)
            assertEquals(-24.1, measured.truePeakDbtp, 0.2)
            assertEquals(measured, analyzer.measure(file.toURI().toASCIIString()), "hearted-cache entries are file: URIs")
            assertNull(analyzer.measure(File(dir, "missing.flac").absolutePath))
            File(dir, "silence.flac").also { silent ->
                assertEquals(0, ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono", "-t", "3",
                    "-c:a", "flac", silent.absolutePath).inheritIO().start().waitFor())
                assertNull(analyzer.measure(silent.absolutePath), "silence has no meaningful gain")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun runsAtTheLowestPriorityOnOneThread() {
        val command = FfmpegLoudnessAnalyzer("/usr/bin/ffmpeg", "/usr/bin/nice").command("/m/a.flac")
        assertEquals(listOf("/usr/bin/nice", "-n", "19", "/usr/bin/ffmpeg"), command.take(4))
        assertTrue(command.containsAll(listOf("-threads", "-filter_threads", "1", "ebur128=peak=true:framelog=quiet")))
        val idleIo = FfmpegLoudnessAnalyzer("/usr/bin/ffmpeg", "/usr/bin/nice", "/usr/bin/ionice").command("/m/a.flac")
        assertEquals(listOf("/usr/bin/ionice", "-c", "3"), idleIo.take(3))
    }
    @Test fun cancellationKillsAProcessWhosePipeReaderIsBlocked() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("kainos-cancel-").toFile()
        val pid = File(dir, "pid")
        val script = File(dir, "analyzer").apply {
            writeText("#!/bin/sh\necho $$ > '${pid.absolutePath}'\nexec sleep 4\n")
            setExecutable(true)
        }
        val input = File(dir, "input").apply { writeText("audio") }
        try {
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                FfmpegLoudnessAnalyzer(script.absolutePath, null).measure(input.absolutePath)
            }
            while (!pid.exists()) kotlinx.coroutines.delay(10)
            val started = System.nanoTime()
            job.cancel()
            job.join()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1500, "cancellation must kill ffmpeg before waiting for its reader")
            assertTrue(!ProcessHandle.of(pid.readText().trim().toLong()).map { it.isAlive }.orElse(false))
        } finally { dir.deleteRecursively() }
    }

    @Test fun quittingTheJvmDoesNotOrphanFfmpeg() {
        val dir = Files.createTempDirectory("kainos-quit-").toFile()
        val pid = File(dir, "pid")
        val script = File(dir, "analyzer").apply {
            writeText("#!/bin/sh\necho $$ > '${pid.absolutePath}'\nexec sleep 30\n")
            setExecutable(true)
        }
        val input = File(dir, "input").apply { writeText("audio") }
        try {
            val classpath = listOf(javaClass, FfmpegLoudnessAnalyzer::class.java,
                LocalLoudnessAnalyzer::class.java, MeasuredLoudness::class.java,
                kotlin.Unit::class.java, kotlinx.coroutines.Job::class.java)
                .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
                .distinct().joinToString(File.pathSeparator)
            val fixture = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-cp", classpath, "com.universalmusic.player.data.local.LoudnessShutdownFixture",
                script.absolutePath, input.absolutePath, pid.absolutePath).inheritIO().start()
            assertTrue(fixture.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, fixture.exitValue())
            val process = ProcessHandle.of(pid.readText().trim().toLong())
            try { assertTrue(!process.map { it.isAlive }.orElse(false), "quit must kill the analyzer child") }
            finally { process.ifPresent { it.destroyForcibly() } }
        } finally { dir.deleteRecursively() }
    }

}


object LoudnessShutdownFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        launch(kotlinx.coroutines.Dispatchers.IO) { FfmpegLoudnessAnalyzer(args[0], null).measure(args[1]) }
        while (!File(args[2]).exists()) kotlinx.coroutines.delay(10)
        kotlin.system.exitProcess(0)
    }
}
