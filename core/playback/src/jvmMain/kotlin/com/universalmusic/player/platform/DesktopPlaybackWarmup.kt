package com.universalmusic.player.platform

import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** One unused, silent player, prepared only by the GUI after its first frame. */
object DesktopPlaybackWarmup {
    private var started = false
    @Volatile private var playbackRequested = false
    private var closed = false
    private var idle: Pair<Process, Path>? = null

    fun start(): CompletableFuture<Void> = CompletableFuture.runAsync {
        synchronized(this) {
            if (started || closed || playbackRequested) return@runAsync
            started = true
            val runtime = SystemDesktopPlaybackRuntime
            val mpv = runtime.findOnPath("mpv") ?: return@runAsync
            val socket = runtime.createIpcPath()
            var process: Process? = null
            try {
                process = runtime.startProcess(listOf(mpv, "--no-video", "--force-window=no",
                    "--really-quiet", "--no-terminal", "--idle=yes", "--gapless-audio=no",
                    "--prefetch-playlist=yes", "--keep-open=no", "--input-ipc-server=$socket"))
                runtime.waitForIpc(socket)
                check(process.isAlive && runtime.sendIpc("""["get_property","idle-active"]""", socket))
                idle = process to socket
                Runtime.getRuntime().addShutdownHook(Thread({ close() }, "kainos-idle-mpv-shutdown"))
            } catch (_: Exception) {
                process?.destroyForcibly()
                runtime.deleteIpcPath(socket)
            }
        }
    }

    internal fun playbackRequested() { playbackRequested = true }

    @Synchronized
    internal fun take(): Pair<Process, Path>? = idle.also { idle = null }?.let {
        if (it.first.isAlive) it else {
            SystemDesktopPlaybackRuntime.deleteIpcPath(it.second)
            null
        }
    }

    @Synchronized
    fun close() {
        closed = true
        idle?.let { (process, socket) ->
            process.destroyForcibly()
            process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            SystemDesktopPlaybackRuntime.deleteIpcPath(socket)
        }
        idle = null
    }
}
