package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.EngineStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlaybackEngineTest {
    @Test
    fun newerPlayPreventsAnOlderBlockedStartFromOverlappingIt() = runBlocking {
        val runtime = FakeDesktopPlaybackRuntime()
        val blocked = runtime.blockProbe("first")
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)

        try {
            val first = async(Dispatchers.Default) {
                engine.play(PlaybackHandle.Url("first"), quality = null)
            }
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS), "first probe did not start")

            engine.play(PlaybackHandle.Url("second"), quality = null)
            blocked.release.countDown()
            first.await()

            assertTrue(runtime.maximumLiveProcesses.get() <= 1, "two mpv processes overlapped")
            assertEquals(listOf("second"), runtime.liveUrls())
        } finally {
            blocked.release.countDown()
            engine.stop()
            runtime.destroyAll()
        }
    }

    @Test
    fun stopInvalidatesAStartThatIsStillProbing() = runBlocking {
        val runtime = FakeDesktopPlaybackRuntime()
        val blocked = runtime.blockProbe("blocked")
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)

        try {
            val play = async(Dispatchers.Default) {
                engine.play(PlaybackHandle.Url("blocked"), quality = null)
            }
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS), "probe did not start")

            engine.stop()
            blocked.release.countDown()
            play.await()

            assertEquals(0, runtime.liveProcessCount.get())
            assertEquals(EngineStatus.IDLE, engine.state.value.status)
        } finally {
            blocked.release.countDown()
            engine.stop()
            runtime.destroyAll()
        }
    }

    @Test
    fun cancellingPlayWhileItProbesCannotLaunchAProcess() = runBlocking {
        val runtime = FakeDesktopPlaybackRuntime()
        val blocked = runtime.blockProbe("cancelled")
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)

        try {
            val play = async(Dispatchers.Default) {
                engine.play(PlaybackHandle.Url("cancelled"), quality = null)
            }
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS), "probe did not start")

            play.cancel()
            blocked.release.countDown()
            play.cancelAndJoin()

            assertEquals(0, runtime.liveProcessCount.get())
        } finally {
            blocked.release.countDown()
            engine.stop()
            runtime.destroyAll()
        }
    }

    @Test
    fun pauseInvalidatesAReplacementThatIsStillProbing() = runBlocking {
        val runtime = FakeDesktopPlaybackRuntime()
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
        engine.play(PlaybackHandle.Url("playing"), quality = null)
        val blocked = runtime.blockProbe("replacement")

        try {
            val replacement = async(Dispatchers.Default) {
                engine.play(PlaybackHandle.Url("replacement"), quality = null)
            }
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS), "replacement probe did not start")

            engine.pause()
            blocked.release.countDown()
            replacement.await()

            assertEquals(0, runtime.liveProcessCount.get())
            assertEquals(EngineStatus.PAUSED, engine.state.value.status)
        } finally {
            blocked.release.countDown()
            engine.stop()
            runtime.destroyAll()
        }
    }
}

private class FakeDesktopPlaybackRuntime : DesktopPlaybackRuntime {
    val liveProcessCount = AtomicInteger()
    val maximumLiveProcesses = AtomicInteger()
    private val probes = ConcurrentHashMap<String, BlockedProbe>()
    private val processes = mutableListOf<FakeProcess>()

    fun blockProbe(url: String): BlockedProbe = BlockedProbe().also { probes[url] = it }

    override fun findOnPath(name: String): String? = "/test/mpv"

    override fun probeDurationMs(url: String): Long? {
        probes[url]?.let { probe ->
            probe.entered.countDown()
            assertTrue(probe.release.await(2, TimeUnit.SECONDS), "timed out releasing $url probe")
        }
        return 60_000
    }

    override fun createIpcPath(): Path = Path.of("/test/ipc-${System.nanoTime()}")

    override fun startProcess(command: List<String>): Process {
        val process = FakeProcess(command.last()) {
            liveProcessCount.decrementAndGet()
        }
        synchronized(processes) { processes += process }
        val live = liveProcessCount.incrementAndGet()
        maximumLiveProcesses.accumulateAndGet(live, ::maxOf)
        return process
    }

    override fun waitForIpc(socket: Path) = Unit

    override fun sendIpc(commandArrayJson: String, socket: Path): Boolean = false

    override fun deleteIpcPath(path: Path) = Unit

    fun liveUrls(): List<String> = synchronized(processes) {
        processes.filter { it.isAlive }.map { it.url }
    }

    fun destroyAll() {
        synchronized(processes) { processes.toList() }.forEach { it.destroyForcibly() }
    }
}

private class BlockedProbe(
    val entered: CountDownLatch = CountDownLatch(1),
    val release: CountDownLatch = CountDownLatch(1),
)

private class FakeProcess(
    val url: String,
    private val onDestroyed: () -> Unit,
) : Process() {
    private val alive = AtomicBoolean(true)
    private val exited = CountDownLatch(1)

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())

    override fun waitFor(): Int {
        exited.await()
        return 0
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)

    override fun exitValue(): Int {
        check(!alive.get()) { "process is still alive" }
        return 0
    }

    override fun destroy() {
        finish()
    }

    override fun destroyForcibly(): Process {
        finish()
        return this
    }

    override fun isAlive(): Boolean = alive.get()

    private fun finish() {
        if (alive.compareAndSet(true, false)) {
            onDestroyed()
            exited.countDown()
        }
    }
}
