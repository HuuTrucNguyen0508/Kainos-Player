package com.universalmusic.player.platform

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CrashOutputTest {
    @Test fun uncaughtCrashTerminatesWithoutLeakingCredentials() {
        val classpath = listOf(CrashOutputProbe::class.java, PlaybackTrace::class.java, Unit::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }.distinct().joinToString(java.io.File.pathSeparator)
        val child = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, CrashOutputProbe::class.java.name,
        ).redirectErrorStream(true).start()
        assertTrue(child.waitFor(5, TimeUnit.SECONDS))
        val output = child.inputStream.bufferedReader().readText()
        assertEquals(1, child.exitValue(), output)
        assertFalse(output.contains("FAKE_URL_CREDENTIAL"), output)
        assertTrue(output.contains("<redacted>"), output)
    }
}

object CrashOutputProbe {
    @JvmStatic fun main(args: Array<String>) {
        installCrashLogging()
        throw IllegalStateException("https://example.invalid/stream?token=FAKE_URL_CREDENTIAL")
    }
}
