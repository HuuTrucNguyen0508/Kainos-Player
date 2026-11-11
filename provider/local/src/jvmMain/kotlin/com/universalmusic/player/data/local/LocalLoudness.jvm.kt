package com.universalmusic.player.data.local

import com.universalmusic.player.data.loudness.loudnessLocationKey
import com.universalmusic.player.domain.playback.MeasuredLoudness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

actual fun createLocalLoudnessAnalyzer(): LocalLoudnessAnalyzer? =
    onPath("ffmpeg")?.let { ffmpeg -> FfmpegLoudnessAnalyzer(ffmpeg, onPath("nice")) }

/**
 * Runs ffmpeg's `ebur128=peak=true` over a file at the lowest CPU priority, on one thread, so the
 * pass never competes with playback. About half a second for a five-minute FLAC.
 */
internal class FfmpegLoudnessAnalyzer(private val ffmpeg: String, private val nice: String?) : LocalLoudnessAnalyzer {
    override suspend fun measure(location: String): MeasuredLoudness? {
        val path = loudnessLocationKey(location)
        if (!File(path).isFile) return null
        val process = ProcessBuilder(command(path)).redirectErrorStream(true).start()
        process.outputStream.close()
        return try {
            coroutineScope {
                // Reading a pipe ignores interrupts; on cancellation the process is killed, which ends the read.
                val log = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                val text = log.await()
                if (process.waitFor() == 0) parseEbur128Summary(text) else null
            }
        } catch (cancelled: CancellationException) {
            process.destroyForcibly()
            throw cancelled
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    internal fun command(path: String): List<String> = buildList {
        if (nice != null) addAll(listOf(nice, "-n", "19"))
        addAll(listOf(ffmpeg, "-nostdin", "-hide_banner", "-nostats", "-threads", "1", "-i", path,
            "-map", "0:a:0", "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-"))
    }
}

private fun onPath(tool: String): String? =
    System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .map { File(it, tool) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
