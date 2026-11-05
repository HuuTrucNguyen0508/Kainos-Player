package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.PreparedPlayback
import kotlinx.coroutines.*
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** Private Pulse output and synthetic files are supplied by seek-bench.py. Also runs on the old jar. */
fun main(args: Array<String>) = runBlocking {
    var socket: Path? = null
    var spawns = 0
    val restarted = AtomicLong()
    val runtime = object : DesktopPlaybackRuntime by SystemDesktopPlaybackRuntime {
        override fun createIpcPath(): Path = SystemDesktopPlaybackRuntime.createIpcPath().also { socket = it }
        override fun startProcess(command: List<String>): Process {
            spawns++
            return SystemDesktopPlaybackRuntime.startProcess(command)
        }
        override fun waitForIpc(socket: Path) {
            SystemDesktopPlaybackRuntime.waitForIpc(socket)
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            channel.connect(UnixDomainSocketAddress.of(socket))
            Thread {
                runCatching {
                    Channels.newInputStream(channel).bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.contains("\"playback-restart\"")) restarted.set(System.nanoTime())
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
    }
    val engine = DesktopPlaybackEngine(spotifyStarter = {}, runtime = runtime)
    try {
        // The pre-gapless baseline has no configureGapless implementation.
        engine.javaClass.methods.firstOrNull { it.name == "configureGapless" }?.invoke(engine, true)
        engine.play(PlaybackHandle.Url(args[0]), null, 1)
        delay(600)
        if (System.getenv("KAINOS_SEEK_WARM") == "1") {
            val warmStart = System.nanoTime()
            engine.seekTo(500)
            withTimeout(5_000) { while (restarted.get() < warmStart) delay(1) }
            delay(300)
        }
        engine.javaClass.methods.firstOrNull { it.name == "prepareNext" }?.invoke(engine,
            PreparedPlayback("next", PlaybackHandle.Url(args[0]), null, 1))
        delay(200)
        val before = spawns
        if (args[1] == "storm") {
            repeat(20) { index ->
                engine.seekTo(2_000L + index * 100)
                if (index == 19) engine.pause()
                delay(100)
            }
            delay(300)
            val position = runtime.readProperty("time-pos", requireNotNull(socket))!!.toDouble()
            check(position in 3.899..3.92) { "Last slider target lost: $position" }
            println("SEEK_RESULT storm_spawns=${spawns-before} final_position=$position")
        } else {
            val start = System.nanoTime()
            engine.seekTo(4_000)
            withTimeout(5_000) { while (restarted.get() < start) delay(1) }
            val end = restarted.get()
            val position = runtime.readProperty("time-pos", requireNotNull(socket))!!.toDouble()
            check(position in 3.999..4.5) { "Wrong seek position: $position" }
            println("SEEK_RESULT latency_ms=${(end-start)/1e6} spawns=${spawns-before} position=$position")
            delay(600)
        }
    } finally { engine.stop() }
}
