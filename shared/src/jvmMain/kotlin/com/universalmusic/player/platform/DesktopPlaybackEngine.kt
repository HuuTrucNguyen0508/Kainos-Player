package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.UnsupportedPlaybackException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-process desktop playback via headless mpv (no extra window).
 * Pause / resume / seek use mpv's JSON IPC socket.
 */
class DesktopPlaybackEngine internal constructor(
    private val spotifyStarter: suspend (String) -> Unit,
    private val runtime: DesktopPlaybackRuntime = SystemDesktopPlaybackRuntime,
) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private val lifecycleLock = Any()
    private var requestToken = 0L
    private var activeProcess: ActiveProcess? = null
    private var userPaused = false
    private var ticker: Job? = null
    private var tickerToken = 0L
    private var startedAt = 0L
    private var elapsedOffset = 0L
    private var lastHandle: PlaybackHandle? = null
    private var lastQuality: AudioQuality? = null
    private val shutdownHook = if (runtime === SystemDesktopPlaybackRuntime) {
        Thread({ shutdown() }, "kainos-mpv-shutdown").also(Runtime.getRuntime()::addShutdownHook)
    } else {
        null
    }

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?) {
        val token = synchronized(lifecycleLock) {
            invalidatePendingStartLocked()
            cancelTickerLocked()
            if (!stopProcessLocked()) {
                _state.value = EngineState(
                    status = EngineStatus.FAILED,
                    error = "Previous player did not terminate",
                )
                error("Previous player did not terminate")
            }
            lastHandle = handle
            lastQuality = quality
            userPaused = false
            elapsedOffset = 0
            _state.value = EngineState(status = EngineStatus.BUFFERING)
            requestToken
        }
        when (handle) {
            is PlaybackHandle.Url -> startUrl(handle.url, startSeconds = 0, token = token)
            is PlaybackHandle.ProviderPlayback -> {
                if (handle.provider != ProviderId.SPOTIFY) {
                    throw UnsupportedPlaybackException(
                        "${handle.provider.displayName} does not provide a supported Linux playback mechanism.",
                    )
                }
                spotifyStarter(handle.trackId)
                synchronized(lifecycleLock) {
                    if (token != requestToken) return
                    startedAt = System.currentTimeMillis()
                    _state.value = EngineState(
                        status = EngineStatus.PLAYING,
                        positionMs = 0,
                        durationMs = null,
                    )
                    startTickerLocked()
                }
            }
        }
    }

    override fun pause() {
        synchronized(lifecycleLock) {
            if (_state.value.status != EngineStatus.PLAYING &&
                _state.value.status != EngineStatus.BUFFERING
            ) {
                return
            }
            invalidatePendingStartLocked()
            elapsedOffset = _state.value.positionMs
            cancelTickerLocked()
            userPaused = true
            val active = activeProcess
            if (active != null && !runtime.sendIpc("""["set_property","pause",true]""", active.socket)) {
                if (!stopProcessLocked()) {
                    _state.value = _state.value.copy(
                        status = EngineStatus.FAILED,
                        error = "Player could not be paused or stopped",
                    )
                    return
                }
            }
            _state.value = _state.value.copy(status = EngineStatus.PAUSED, error = null)
        }
    }

    override fun resume() {
        val restart = synchronized(lifecycleLock) {
            val current = _state.value
            if (current.status != EngineStatus.PAUSED) return
            userPaused = false
            val active = activeProcess
            if (active?.process?.isAlive == true &&
                runtime.sendIpc("""["set_property","pause",false]""", active.socket)
            ) {
                startedAt = System.currentTimeMillis()
                _state.value = current.copy(status = EngineStatus.PLAYING, error = null)
                startTickerLocked()
                return
            }
            if (!stopProcessLocked()) {
                _state.value = current.copy(
                    status = EngineStatus.FAILED,
                    error = "Player did not terminate before resume",
                )
                return
            }
            val handle = lastHandle ?: return
            invalidatePendingStartLocked()
            _state.value = current.copy(status = EngineStatus.BUFFERING, error = null)
            Restart(handle, lastQuality, elapsedOffset / 1000, requestToken, EngineStatus.PLAYING)
        }
        scope.launch {
            when (val handle = restart.handle) {
                is PlaybackHandle.Url -> startUrl(handle.url, restart.startSeconds, restart.token)
                is PlaybackHandle.ProviderPlayback -> play(handle, restart.quality)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val restart = synchronized(lifecycleLock) {
            elapsedOffset = positionMs.coerceAtLeast(0)
            startedAt = System.currentTimeMillis()
            val current = _state.value
            _state.value = current.copy(positionMs = elapsedOffset)
            val handle = lastHandle
            if (handle !is PlaybackHandle.Url) return
            val active = activeProcess
            if (active?.process?.isAlive == true) {
                val seconds = elapsedOffset / 1000.0
                if (runtime.sendIpc("""["set_property","time-pos",$seconds]""", active.socket)) {
                    if (current.status == EngineStatus.PLAYING) {
                        startedAt = System.currentTimeMillis()
                    }
                    return
                }
            }
            if (current.status != EngineStatus.PLAYING && current.status != EngineStatus.PAUSED) return
            if (!stopProcessLocked()) {
                _state.value = current.copy(
                    status = EngineStatus.FAILED,
                    error = "Player did not terminate before seek",
                )
                return
            }
            invalidatePendingStartLocked()
            val targetStatus = current.status
            userPaused = targetStatus == EngineStatus.PAUSED
            _state.value = _state.value.copy(status = EngineStatus.BUFFERING)
            Restart(handle, lastQuality, elapsedOffset / 1000, requestToken, targetStatus)
        }
        scope.launch {
            startUrl(
                url = (restart.handle as PlaybackHandle.Url).url,
                startSeconds = restart.startSeconds,
                token = restart.token,
                targetStatus = restart.targetStatus,
            )
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            invalidatePendingStartLocked()
            userPaused = false
            cancelTickerLocked()
            if (!stopProcessLocked()) {
                _state.value = _state.value.copy(
                    status = EngineStatus.FAILED,
                    error = "Player did not terminate",
                )
                return
            }
            elapsedOffset = 0
            lastHandle = null
            lastQuality = null
            _state.value = EngineState()
        }
    }

    override fun setVolume(volume: Float) {
        val percent = (volume.coerceIn(0f, 1f) * 100).toInt()
        synchronized(lifecycleLock) {
            activeProcess?.let { runtime.sendIpc("""["set_property","volume",$percent]""", it.socket) }
        }
    }

    private suspend fun startUrl(
        url: String,
        startSeconds: Long,
        token: Long,
        targetStatus: EngineStatus = EngineStatus.PLAYING,
    ) {
        val mpv = runtime.findOnPath("mpv")
            ?: throw UnsupportedPlaybackException("Install mpv for in-app local playback on Linux.")
        val durationMs = withContext(Dispatchers.IO) { runtime.probeDurationMs(url) }
        val active = synchronized(lifecycleLock) {
            if (token != requestToken) return
            val socket = runtime.createIpcPath()
            val command = buildList {
                add(mpv)
                add("--no-video")
                add("--force-window=no")
                add("--really-quiet")
                add("--no-terminal")
                add("--idle=no")
                add("--keep-open=no")
                add("--input-ipc-server=$socket")
                if (targetStatus == EngineStatus.PAUSED) add("--pause")
                if (startSeconds > 0) add("--start=$startSeconds")
                add(url)
            }
            val process = try {
                runtime.startProcess(command)
            } catch (error: Throwable) {
                runtime.deleteIpcPath(socket)
                throw error
            }
            ActiveProcess(process, socket).also { activeProcess = it }
        }
        watchProcess(active, durationMs)
        runtime.waitForIpc(active.socket)
        synchronized(lifecycleLock) {
            if (token != requestToken || activeProcess !== active) return
            if (startSeconds > 0) {
                runtime.sendIpc("""["set_property","time-pos",$startSeconds]""", active.socket)
            }
            if (targetStatus == EngineStatus.PAUSED) {
                userPaused = true
                if (!runtime.sendIpc("""["set_property","pause",true]""", active.socket)) {
                    if (!stopProcessLocked()) {
                        _state.value = EngineState(
                            status = EngineStatus.FAILED,
                            positionMs = elapsedOffset,
                            durationMs = durationMs,
                            error = "Player could not be paused or stopped",
                        )
                        return
                    }
                }
            } else {
                userPaused = false
            }
            startedAt = System.currentTimeMillis()
            _state.value = EngineState(
                status = targetStatus,
                positionMs = elapsedOffset,
                durationMs = durationMs,
            )
            if (targetStatus == EngineStatus.PLAYING) startTickerLocked()
        }
    }

    private fun watchProcess(active: ActiveProcess, durationMs: Long?) {
        scope.launch(Dispatchers.IO) {
            val code = runCatching { active.process.waitFor() }.getOrNull()
            runtime.deleteIpcPath(active.socket)
            synchronized(lifecycleLock) {
                if (activeProcess !== active) return@synchronized
                activeProcess = null
                if (userPaused) return@synchronized
                _state.value = if (code == 0) {
                    _state.value.copy(
                        status = EngineStatus.ENDED,
                        positionMs = durationMs ?: _state.value.positionMs,
                    )
                } else {
                    _state.value.copy(
                        status = EngineStatus.FAILED,
                        error = code?.let { "Player exited with $it" } ?: "Player process failed",
                    )
                }
                cancelTickerLocked()
            }
        }
    }

    private fun startTickerLocked() {
        cancelTickerLocked()
        val token = ++tickerToken
        ticker = scope.launch {
            while (true) {
                synchronized(lifecycleLock) {
                    if (token != tickerToken || _state.value.status != EngineStatus.PLAYING) {
                        return@launch
                    }
                    val position = elapsedOffset + (System.currentTimeMillis() - startedAt)
                    val duration = _state.value.durationMs
                    _state.value = _state.value.copy(
                        positionMs = if (duration != null) position.coerceAtMost(duration) else position,
                    )
                }
                delay(400)
            }
        }
    }

    private fun cancelTickerLocked() {
        tickerToken++
        ticker?.cancel()
        ticker = null
    }

    private fun invalidatePendingStartLocked() {
        requestToken++
    }

    private fun stopProcessLocked(): Boolean {
        val active = activeProcess ?: return true
        val terminated = try {
            active.process.destroyForcibly()
            active.process.waitFor(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
        if (!terminated) return false
        if (activeProcess === active) activeProcess = null
        runtime.deleteIpcPath(active.socket)
        return true
    }

    private fun shutdown() {
        synchronized(lifecycleLock) {
            invalidatePendingStartLocked()
            cancelTickerLocked()
            stopProcessLocked()
        }
    }

    private class ActiveProcess(
        val process: Process,
        val socket: Path,
    )

    private data class Restart(
        val handle: PlaybackHandle,
        val quality: AudioQuality?,
        val startSeconds: Long,
        val token: Long,
        val targetStatus: EngineStatus,
    )
}

internal interface DesktopPlaybackRuntime {
    fun findOnPath(name: String): String?
    fun probeDurationMs(url: String): Long?
    fun createIpcPath(): Path
    fun startProcess(command: List<String>): Process
    fun waitForIpc(socket: Path)
    fun sendIpc(commandArrayJson: String, socket: Path): Boolean
    fun deleteIpcPath(path: Path)
}

private object SystemDesktopPlaybackRuntime : DesktopPlaybackRuntime {
    override fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(':').firstOrNull { dir ->
            java.io.File(dir, name).canExecute()
        }?.let { java.io.File(it, name).absolutePath }
    }

    override fun probeDurationMs(url: String): Long? {
        val ffprobe = findOnPath("ffprobe") ?: return null
        val media = mediaPathForProbe(url) ?: url
        return runCatching {
            val process = ProcessBuilder(
                ffprobe,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                media,
            ).redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return null
            }
            if (process.exitValue() != 0) return null
            val output = process.inputStream.bufferedReader().readText().trim()
            val seconds = output.lineSequence().firstOrNull()?.toDoubleOrNull() ?: return null
            (seconds * 1000.0).toLong().takeIf { it > 0L }
        }.getOrNull()
    }

    private fun mediaPathForProbe(url: String): String? = runCatching {
        when {
            url.startsWith("file:") -> java.nio.file.Paths.get(java.net.URI(url)).toString()
            else -> url
        }
    }.getOrNull()

    override fun createIpcPath(): Path =
        Files.createTempFile("kainos-mpv-", ".sock").also { Files.deleteIfExists(it) }

    override fun startProcess(command: List<String>): Process =
        ProcessBuilder(command).redirectErrorStream(true).start()

    override fun waitForIpc(socket: Path) {
        val deadline = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(socket) && sendIpc("""["get_property","pause"]""", socket)) return
            Thread.sleep(40)
        }
    }

    override fun sendIpc(commandArrayJson: String, socket: Path): Boolean {
        if (!Files.exists(socket)) return false
        return runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socket))
                val payload = """{"command":$commandArrayJson}""" + "\n"
                channel.write(ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8)))
            }
            true
        }.getOrDefault(false)
    }

    override fun deleteIpcPath(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }
}
