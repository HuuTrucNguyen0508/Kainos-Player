package com.universalmusic.player.platform

import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Redirect to a file so neither a full pipe nor inherited stdout can outlive the deadline. */
fun ProcessBuilder.readOutputWithin(timeoutMillis: Long): String? {
    val output = Files.createTempFile("kainos-process-", ".out")
    var process: Process? = null
    try {
        process = redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return null
        return Files.newInputStream(output).use { it.readNBytes(16 * 1024 * 1024).toString(Charsets.UTF_8) }
    } finally {
        process?.let { child ->
            child.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            if (child.isAlive) child.destroyForcibly()
            child.inputStream.close()
            child.errorStream.close()
            child.outputStream.close()
        }
        Files.deleteIfExists(output)
    }
}
