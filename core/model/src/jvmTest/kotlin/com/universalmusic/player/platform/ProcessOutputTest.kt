package com.universalmusic.player.platform

import java.nio.file.Files
import kotlin.test.*

class ProcessOutputTest {
    @Test fun largeStderrDoesNotBlockStdoutAndFailureKeepsItsTail() {
        for (exit in listOf(0, 1)) {
            val script = Files.createTempFile("extractor-stderr", ".sh").toFile()
            script.writeText("#!/bin/sh\nhead -c 2097152 /dev/zero >&2\necho ERROR: last failure >&2\necho result\nexit $exit\n")
            script.setExecutable(true)
            try {
                val run = ProcessBuilder(script.absolutePath).runWithin(5_000, 128)
                assertEquals(exit, run.exitCode)
                assertTrue(run.stderrTail.endsWith("ERROR: last failure\n"))
                assertTrue(run.stderrTail.length <= 128)
                assertEquals(if (exit == 0) "result\n" else "", run.stdout)
            } finally { script.delete() }
        }
    }

    @Test fun deadlineBoundsHungAndChattyExtractors() {
        for (body in listOf("exec sleep 3", "while :; do echo output; done")) {
            val script = Files.createTempFile("extractor", ".sh").toFile()
            script.writeText("#!/bin/sh\n$body\n")
            script.setExecutable(true)
            try {
                val started = System.nanoTime()
                assertNull(ProcessBuilder(script.absolutePath).readOutputWithin(150))
                assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)
            } finally { script.delete() }
        }
    }
}
