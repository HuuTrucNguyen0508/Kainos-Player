package com.universalmusic.player.data.sync

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubOwnerLockTest {
    @Test
    fun secondProcessWaitsUntilFirstReleases() = withOwner { directory, owner ->
        assertNull(HubOwnerLock.acquire(directory, 100))
        val contender = child(directory, 5_000)
        try {
            assertFalse(contender.waitFor(150, TimeUnit.MILLISECONDS))
            val releasedAt = System.nanoTime()
            owner.outputStream.write('\n'.code)
            owner.outputStream.flush()
            assertTrue(owner.waitFor(2, TimeUnit.SECONDS))
            assertTrue(contender.waitFor(1, TimeUnit.SECONDS))
            assertEquals(0, contender.exitValue(), contender.inputStream.bufferedReader().readText())
            assertTrue(System.nanoTime() - releasedAt < 1_000_000_000)
        } finally {
            stop(contender)
        }
    }

    @Test
    fun killedOwnerLeavesNoStaleLock() = withOwner { directory, owner ->
        assertNull(HubOwnerLock.acquire(directory, 50))
        val kill = ProcessBuilder("kill", "-9", owner.pid().toString()).start()
        assertTrue(kill.waitFor(2, TimeUnit.SECONDS))
        assertEquals(0, kill.exitValue())
        assertTrue(owner.waitFor(2, TimeUnit.SECONDS))
        val contender = child(directory, 1_000)
        try {
            assertTrue(contender.waitFor(3, TimeUnit.SECONDS))
            assertEquals(0, contender.exitValue(), contender.inputStream.bufferedReader().readText())
        } finally {
            stop(contender)
        }
    }

    @Test
    fun sameJvmContentionDoesNotReleaseTheProcessLock() {
        val directory = Files.createTempDirectory("kainos-hub-lock-")
        try {
            assertNotNull(HubOwnerLock.acquire(directory, 0)).use {
                assertNull(HubOwnerLock.acquire(directory, 50))
                val contender = child(directory, 100)
                try {
                    assertTrue(contender.waitFor(3, TimeUnit.SECONDS))
                    assertEquals(75, contender.exitValue(), "Same-JVM contention must preserve cross-process exclusion")
                } finally {
                    stop(contender)
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun contentionTimesOutAndSeparateDataDirectoriesDoNotContend() {
        val directory = Files.createTempDirectory("kainos-hub-lock-")
        try {
            assertNotNull(HubOwnerLock.acquire(directory, 0)).use {
                assertNull(HubOwnerLock.acquire(directory, 50))
                assertNotNull(HubOwnerLock.acquire(directory.resolve("other"), 0)).close()
            }
            assertNotNull(HubOwnerLock.acquire(directory, 0)).close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun withOwner(test: (Path, Process) -> Unit) {
        val directory = Files.createTempDirectory("kainos-hub-lock-")
        val ready = directory.resolve("ready")
        val owner = child(directory, 0, ready)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(ready) && owner.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(ready), "Child did not acquire lock")
            test(directory, owner)
        } finally {
            stop(owner)
            directory.toFile().deleteRecursively()
        }
    }

    private fun child(directory: Path, timeoutMs: Long, ready: Path? = null): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"),
        HubLockChild::class.java.name,
        directory.toString(), timeoutMs.toString(), ready?.toString().orEmpty(),
    ).redirectErrorStream(true).start()

    private fun stop(process: Process) {
        if (process.isAlive) process.destroyForcibly()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
    }
}

object HubLockChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val lock = HubOwnerLock.acquire(Path.of(args[0]), args[1].toLong()) ?: kotlin.system.exitProcess(75)
        lock.use {
            if (args[2].isNotEmpty()) {
                Files.writeString(Path.of(args[2]), "locked")
                System.`in`.read()
            }
        }
    }
}
