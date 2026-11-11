package com.universalmusic.player.data.local

import com.universalmusic.player.data.loudness.loudnessLocationKey
import com.universalmusic.player.domain.playback.MeasuredLoudness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

actual fun createLocalLoudnessAnalyzer(): LocalLoudnessAnalyzer? =
    onPath("ffmpeg")?.let { ffmpeg -> FfmpegLoudnessAnalyzer(ffmpeg, onPath("nice"), onPath("ionice")) }

/**
 * Runs ffmpeg with idle I/O and lowest CPU priority where supported, with one decoder
 * and filter thread. Playback never awaits this pass.
 */
internal class FfmpegLoudnessAnalyzer(private val ffmpeg: String, private val nice: String?, private val ionice: String? = null) : LocalLoudnessAnalyzer {
    override suspend fun measure(location: String): MeasuredLoudness? {
        val path = loudnessLocationKey(location)
        if (!File(path).isFile) return null
        val log = File.createTempFile("kainos-loudness-", ".log")
        val started = java.util.concurrent.CompletableFuture<Process?>()
        val shutdown = Thread({ started.join()?.destroyForcibly()?.waitFor() }, "kainos-loudness-stop")
        Runtime.getRuntime().addShutdownHook(shutdown)
        val process = try {
            ProcessBuilder(command(path)).redirectErrorStream(true).redirectOutput(log).start().also { started.complete(it) }
        } catch (failure: Throwable) {
            started.complete(null)
            log.delete()
            try { Runtime.getRuntime().removeShutdownHook(shutdown) } catch (_: IllegalStateException) { }
            throw failure
        }
        process.outputStream.close()
        return try {
            withContext(Dispatchers.IO) {
                // Interrupting waitFor kills the child before waiting for a pipe reader.
                // ffmpeg's summary is small enough to spool to disk, avoiding a blocked reader.
                runInterruptible { process.waitFor() }
                val text = log.readText()
                if (process.exitValue() == 0) parseEbur128Summary(text) else null
            }
        } catch (cancelled: CancellationException) {
            process.destroyForcibly()
            throw cancelled
        } finally {
            if (process.isAlive) process.destroyForcibly()
            log.delete()
            try { Runtime.getRuntime().removeShutdownHook(shutdown) } catch (_: IllegalStateException) {
                // JVM shutdown is already running this hook.
            }
        }
    }

    internal fun command(path: String): List<String> = buildList {
        if (ionice != null) addAll(listOf(ionice, "-c", "3"))
        if (nice != null) addAll(listOf(nice, "-n", "19"))
        addAll(listOf(ffmpeg, "-nostdin", "-hide_banner", "-nostats", "-threads", "1", "-i", path,
            "-filter_threads", "1", "-map", "0:a:0", "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-"))
    }
}

private fun onPath(tool: String): String? =
    System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .map { File(it, tool) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
