package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.EngineStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlaybackEngineTest {
    @Test
    fun stopIsSentBeforeAFollowingSpotifyPlay() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val engine = DesktopPlaybackEngine(
            spotify = SpotifyPlaybackController(
                play = { calls += "play:$it" },
                pause = { calls += "pause" },
                resume = {},
                seekTo = {},
            ),
            runtime = FakeDesktopPlaybackRuntime(),
        )

        engine.play(PlaybackHandle.ProviderPlayback(com.universalmusic.player.domain.model.ProviderId.SPOTIFY, "one"), null)
        engine.stop()
        engine.play(PlaybackHandle.ProviderPlayback(com.universalmusic.player.domain.model.ProviderId.SPOTIFY, "two"), null)

        withTimeout(1_000) {
            while (calls.size < 3) kotlinx.coroutines.yield()
        }
        assertEquals(listOf("play:one", "pause", "play:two"), calls)
        engine.stop()
    }

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

    @Test
    fun transportCallsReturnPromptlyWhileMpvIpcIsWedged() = runBlocking {
        val runtime = FakeDesktopPlaybackRuntime()
        runtime.sendIpcResult.set(true)
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
        engine.play(PlaybackHandle.Url("song"), quality = null)
        val wedged = runtime.blockNextIpc { it.startsWith("send:song:") && it.contains("volume") }

        try {
            engine.setVolume(0.3f)
            assertTrue(wedged.entered.await(1, TimeUnit.SECONDS), "volume IPC did not reach mpv")

            assertReturnsPromptly("pause") { engine.pause() }
            assertEquals(EngineStatus.PAUSED, engine.state.value.status)
            assertReturnsPromptly("setVolume") { engine.setVolume(0.7f) }
            assertReturnsPromptly("resume") { engine.resume() }
            assertEquals(EngineStatus.PLAYING, engine.state.value.status)
            assertReturnsPromptly("seekTo") { engine.seekTo(10_000) }
            assertEquals(10_000, engine.state.value.positionMs)
            assertReturnsPromptly("pause again") { engine.pause() }
            assertReturnsPromptly("stop") { engine.stop() }

            wedged.release.countDown()
            withTimeout(2_000) {
                while (runtime.sentIpc.isEmpty()) kotlinx.coroutines.yield()
            }
            // Commands queued for the stopped process are dropped; the wedged one completes.
            kotlinx.coroutines.delay(100)
            assertEquals(listOf("""song:["set_property","volume",30]"""), runtime.sentIpc.toList())
            assertEquals(EngineStatus.IDLE, engine.state.value.status)
        } finally {
            wedged.release.countDown()
            engine.stop()
            runtime.destroyAll()
        }
    }

    @Test
    fun transportIpcIsDeliveredInFifoOrderAfterAWedge() = runBlocking {
        val runtime = FakeDesktopPlaybackRuntime()
        runtime.sendIpcResult.set(true)
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
        engine.play(PlaybackHandle.Url("song"), quality = null)
        val wedged = runtime.blockNextIpc { it.startsWith("send:song:") && it.contains("volume") }

        try {
            engine.setVolume(0.3f)
            assertTrue(wedged.entered.await(1, TimeUnit.SECONDS), "volume IPC did not reach mpv")
            assertReturnsPromptly("pause") { engine.pause() }
            assertReturnsPromptly("setVolume") { engine.setVolume(0.7f) }
            assertReturnsPromptly("resume") { engine.resume() }
            assertReturnsPromptly("seekTo") { engine.seekTo(10_000) }
            wedged.release.countDown()

            withTimeout(2_000) {
                while (runtime.sentIpc.size < 5) kotlinx.coroutines.yield()
            }
            assertEquals(
                listOf(
                    """song:["set_property","volume",30]""",
                    """song:["set_property","pause",true]""",
                    """song:["set_property","volume",70]""",
                    """song:["set_property","pause",false]""",
                    """song:["set_property","time-pos",10.0]""",
                ),
                runtime.sentIpc.toList(),
            )
            assertEquals(EngineStatus.PLAYING, engine.state.value.status)
            assertEquals(listOf("song"), runtime.liveUrls())
        } finally {
            wedged.release.countDown()
            engine.stop()
            runtime.destroyAll()
        }
    }

    @Test
    fun staleTickerReadAfterStopAndNewPlayDoesNotPublishOldProcessState() = runBlocking {
        val runtime = FakeDesktopPlaybackRuntime()
        runtime.properties = { url, name ->
            when {
                name == "eof-reached" -> "no"
                url == "old" && name == "time-pos" -> "999"
                else -> null
            }
        }
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
        val wedged = runtime.blockNextIpc { it == "get:old:time-pos" }

        try {
            engine.play(PlaybackHandle.Url("old"), quality = null)
            assertTrue(wedged.entered.await(1, TimeUnit.SECONDS), "ticker read did not start")

            assertReturnsPromptly("stop") { engine.stop() }
            assertEquals(EngineStatus.IDLE, engine.state.value.status)
            engine.play(PlaybackHandle.Url("new"), quality = null)
            assertEquals(EngineStatus.PLAYING, engine.state.value.status)

            wedged.release.countDown()
            kotlinx.coroutines.delay(600)

            val state = engine.state.value
            assertEquals(EngineStatus.PLAYING, state.status)
            assertTrue(state.positionMs < 60_000, "stale old-process position leaked: ${state.positionMs}")
            assertEquals(listOf("new"), runtime.liveUrls())
        } finally {
            wedged.release.countDown()
            engine.stop()
            runtime.destroyAll()
        }
    }

    @Test
    fun staleTickerReadDoesNotOverrideASeekMadeWhileItWasInFlight() = runBlocking {
        val runtime = FakeDesktopPlaybackRuntime()
        runtime.sendIpcResult.set(true)
        val staleReads = AtomicInteger()
        runtime.properties = { _, name ->
            when (name) {
                "eof-reached" -> "no"
                // Only the in-flight (pre-seek) read reports the old position.
                "time-pos" -> if (staleReads.getAndIncrement() == 0) "999" else null
                else -> null
            }
        }
        val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
        val wedged = runtime.blockNextIpc { it == "get:song:time-pos" }

        try {
            engine.play(PlaybackHandle.Url("song"), quality = null)
            assertTrue(wedged.entered.await(1, TimeUnit.SECONDS), "ticker read did not start")

            assertReturnsPromptly("seekTo") { engine.seekTo(30_000) }
            wedged.release.countDown()
            withTimeout(2_000) {
                while (runtime.sentIpc.none { it.contains("time-pos") }) kotlinx.coroutines.yield()
            }
            kotlinx.coroutines.delay(600)

            val state = engine.state.value
            assertEquals(EngineStatus.PLAYING, state.status)
            assertTrue(
                state.positionMs in 30_000 until 60_000,
                "stale pre-seek position leaked: ${state.positionMs}",
            )
        } finally {
            wedged.release.countDown()
            engine.stop()
            runtime.destroyAll()
        }
    }

    private fun assertReturnsPromptly(name: String, call: () -> Unit) {
        val started = System.nanoTime()
        call()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 200, "$name blocked for ${elapsedMs}ms while mpv IPC was wedged")
    }
}

private class FakeDesktopPlaybackRuntime : DesktopPlaybackRuntime {
    val liveProcessCount = AtomicInteger()
    val maximumLiveProcesses = AtomicInteger()
    private val probes = ConcurrentHashMap<String, BlockedProbe>()
    private val processes = mutableListOf<FakeProcess>()
    private val urlBySocket = ConcurrentHashMap<Path, String>()

    /** Result returned by [sendIpc]; false by default (as if mpv never answered). */
    val sendIpcResult = AtomicBoolean(false)
    /** "url:command" for every sendIpc, in call order. */
    val sentIpc = CopyOnWriteArrayList<String>()
    /** Answers readProperty(name) for the process playing url; null by default. */
    @Volatile
    var properties: (url: String, name: String) -> String? = { _, _ -> null }
    @Volatile
    private var ipcGate: Pair<(String) -> Boolean, BlockedProbe>? = null

    fun blockProbe(url: String): BlockedProbe = BlockedProbe().also { probes[url] = it }

    /** Blocks the next sendIpc ("send:url:cmd") / readProperty ("get:url:name") matching [matches]. */
    fun blockNextIpc(matches: (String) -> Boolean): BlockedProbe =
        BlockedProbe().also { ipcGate = matches to it }

    private fun gate(call: String) {
        val (matches, probe) = ipcGate ?: return
        if (!matches(call)) return
        ipcGate = null
        probe.entered.countDown()
        assertTrue(probe.release.await(5, TimeUnit.SECONDS), "timed out releasing $call")
    }

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
        command.firstOrNull { it.startsWith("--input-ipc-server=") }
            ?.let { urlBySocket[Path.of(it.removePrefix("--input-ipc-server="))] = command.last() }
        val process = FakeProcess(command.last()) {
            liveProcessCount.decrementAndGet()
        }
        synchronized(processes) { processes += process }
        val live = liveProcessCount.incrementAndGet()
        maximumLiveProcesses.accumulateAndGet(live, ::maxOf)
        return process
    }

    override fun waitForIpc(socket: Path) = Unit

    override fun sendIpc(commandArrayJson: String, socket: Path): Boolean {
        val call = "${urlBySocket[socket]}:$commandArrayJson"
        gate("send:$call")
        sentIpc += call
        return sendIpcResult.get()
    }

    override fun readProperty(name: String, socket: Path): String? {
        val url = urlBySocket[socket] ?: return null
        gate("get:$url:$name")
        return properties(url, name)
    }

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
