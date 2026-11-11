package com.universalmusic.player.platform

import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** A finished (or timed-out) child: [exitCode] is null when it missed the deadline. */
data class ProcessRun(val exitCode: Int?, val stdout: String, val stderrTail: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/** Redirect to a file so neither a full pipe nor inherited stdout can outlive the deadline. */
fun ProcessBuilder.readOutputWithin(timeoutMillis: Long): String? =
    runWithin(timeoutMillis).takeIf { it.succeeded }?.stdout

/**
 * Runs the child with stdout and stderr in separate files, so a failure keeps its reason.
 * Only the last [stderrTailBytes] of stderr are kept.
 */
fun ProcessBuilder.runWithin(timeoutMillis: Long, stderrTailBytes: Int = 4_096): ProcessRun {
    val output = Files.createTempFile("kainos-process-", ".out")
    val errors = Files.createTempFile("kainos-process-", ".err")
    var process: Process? = null
    try {
        process = redirectOutput(output.toFile()).redirectError(errors.toFile()).start()
        val exitCode = if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) process.exitValue() else null
        val stdout = if (exitCode == 0) {
            Files.newInputStream(output).use { it.readNBytes(16 * 1024 * 1024).toString(Charsets.UTF_8) }
        } else {
            ""
        }
        return ProcessRun(exitCode, stdout, readTail(errors, stderrTailBytes))
    } finally {
        process?.let { child ->
            child.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            if (child.isAlive) child.destroyForcibly()
            child.inputStream.close()
            child.errorStream.close()
            child.outputStream.close()
        }
        Files.deleteIfExists(output)
        Files.deleteIfExists(errors)
    }
}

/** The last [maxBytes] of [file] as text; empty when it cannot be read. */
fun readTail(file: java.nio.file.Path, maxBytes: Int): String = runCatching {
    java.io.RandomAccessFile(file.toFile(), "r").use { raf ->
        val start = (raf.length() - maxBytes).coerceAtLeast(0L)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }
}.getOrDefault("")
