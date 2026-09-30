package com.universalmusic.player.data.cache

import com.universalmusic.player.platform.JvmYouTubeAudioDownloader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.*

class HeartedAudioDownloaderTest {
    private fun connection(expected: Long) = object : URLConnection(URL("https://example.invalid/audio")) {
        override fun connect() {}
        override fun getContentLengthLong() = expected
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    }

    @Test fun httpCopyRejectsTruncatedKnownLengthAndSetsTimeouts() {
        val connection = connection(10)
        assertFailsWith<IllegalArgumentException> {
            copyHeartedAudio(connection, ByteArrayOutputStream(), {}, null)
        }
        assertEquals(30_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
    }

    @Test fun httpCopyAcceptsExactOrUnknownLengthAndRejectsOverrun() {
        for (size in listOf(3L, -1L)) {
            val output = ByteArrayOutputStream()
            assertEquals(3, copyHeartedAudio(connection(size), output, {}, null))
            assertContentEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            copyHeartedAudio(connection(2), ByteArrayOutputStream(), {}, null)
        }
    }

    @Test fun httpCopyPropagatesCancellation() {
        assertFailsWith<CancellationException> {
            copyHeartedAudio(connection(3), ByteArrayOutputStream(), { throw CancellationException() }, null)
        }
    }

    private fun child(root: File, succeeds: Boolean = false): File {
        val script = File(root, "fake-yt-dlp")
        // yt-dlp's fourth argument is the output template. A sleeping shell keeps stdout open.
        script.writeText("""
            #!/bin/sh
            echo ${'$'}${'$'} > '${root.path}/pid'
            template="${'$'}4"
            output="${'$'}{template%.*}.m4a"
            printf audio > "${'$'}output.part"
            echo '[download] 10%'
            ${if (succeeds) "mv \"\$output.part\" \"\$output\"\nexit 0" else "sleep 30"}
        """.trimIndent())
        assertTrue(script.setExecutable(true))
        return script
    }

    private fun assertChildStopped(root: File) {
        val pid = File(root, "pid").readText().trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertEquals(emptyList(), File(root, "audio").listFiles().orEmpty().map { it.name })
    }

    @Test fun processDeadlineIncludesChildHoldingStdoutOpenAndCleansPartial() = runBlocking {
        val root = createTempDirectory("downloader-timeout").toFile()
        try {
            val script = child(root)
            val start = System.nanoTime()
            val result = withTimeout(5_000) {
                JvmYouTubeAudioDownloader({ script.toPath() }, timeoutMillis = 200)
                    .downloadAudio("video", File(root, "audio").path, "attempt", null)
            }
            assertNull(result)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 4_000)
            assertChildStopped(root)
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationKillsChildAndCleansPartial() = runBlocking {
        val root = createTempDirectory("downloader-cancel").toFile()
        try {
            val script = child(root)
            val task = async {
                JvmYouTubeAudioDownloader({ script.toPath() })
                    .downloadAudio("video", File(root, "audio").path, "attempt", null)
            }
            withTimeout(5_000) { while (!File(root, "pid").exists()) delay(10) }
            task.cancelAndJoin()
            assertChildStopped(root)
        } finally { root.deleteRecursively() }
    }

    @Test fun successfulChildPublishesAudioAndPreservesExistingFiles() = runBlocking {
        val root = createTempDirectory("downloader-success").toFile()
        try {
            val script = child(root, succeeds = true)
            val directory = File(root, "audio").apply { mkdirs() }
            val existing = File(directory, "existing.m4a").apply { writeText("keep") }
            val downloader = JvmYouTubeAudioDownloader({ script.toPath() }, timeoutMillis = 1_000)
            assertNull(downloader.downloadAudio("video", directory.path, "existing", null))
            assertEquals("keep", existing.readText())
            val result = downloader.downloadAudio("video", directory.path, "attempt", null)
            assertNotNull(result)
            assertEquals("audio", File(result.absolutePath).readText())
            assertFalse(File(directory, "attempt.m4a.part").exists())
        } finally { root.deleteRecursively() }
    }
}
