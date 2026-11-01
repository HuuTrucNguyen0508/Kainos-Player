package com.universalmusic.player.platform

import java.nio.file.Files
import kotlin.test.*

class ProcessOutputTest {
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
