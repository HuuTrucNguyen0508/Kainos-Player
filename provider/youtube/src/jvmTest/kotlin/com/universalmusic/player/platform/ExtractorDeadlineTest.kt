package com.universalmusic.player.platform

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class ExtractorDeadlineTest {
    @Test fun cancellingExtractionKillsTheHungExtractorPromptly() = runBlocking {
        for (mix in listOf(false, true)) {
            val root = Files.createTempDirectory("hung-extractor").toFile()
            val pid = root.resolve("pid")
            val script = root.resolve("extractor.sh")
            script.writeText("#!/bin/sh\necho $$ > '${pid.absolutePath}'\nexec sleep 3\n")
            script.setExecutable(true)
            try {
                val resolver = JvmYouTubeStreamResolver { script.toPath() }
                val job = launch(Dispatchers.Default) {
                    if (mix) resolver.mix("video") else resolver.resolveAudioUrl("video")
                }
                withTimeout(2_000) { while (!pid.exists() || pid.length() == 0L) delay(5) }
                val child = ProcessHandle.of(pid.readText().trim().toLong()).orElseThrow()
                val started = System.nanoTime()
                job.cancelAndJoin()
                assertTrue((System.nanoTime() - started) / 1_000_000 < 1000, "cancellation waited for the extractor")
                withTimeout(1_000) { while (child.isAlive) delay(5) }
            } finally { root.deleteRecursively() }
        }
    }
}
