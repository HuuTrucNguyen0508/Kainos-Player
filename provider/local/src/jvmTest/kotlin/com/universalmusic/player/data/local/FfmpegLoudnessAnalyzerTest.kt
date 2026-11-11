package com.universalmusic.player.data.local

import kotlinx.coroutines.runBlocking
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
        assertTrue(command.containsAll(listOf("-threads", "1", "ebur128=peak=true:framelog=quiet")))
    }
}
