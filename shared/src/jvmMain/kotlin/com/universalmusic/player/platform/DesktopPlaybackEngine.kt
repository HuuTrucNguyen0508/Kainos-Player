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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel

/**
 * In-process desktop playback via headless mpv (no extra window).
 * Pause / resume / seek use mpv's JSON IPC socket.
 */
class DesktopPlaybackEngine internal constructor(
    private val spotify: SpotifyPlaybackController,
    private val runtime: DesktopPlaybackRuntime = SystemDesktopPlaybackRuntime,
) : PlaybackEngine {
    internal constructor(
        spotifyStarter: suspend (String) -> Unit,
        runtime: DesktopPlaybackRuntime = SystemDesktopPlaybackRuntime,
    ) : this(
        SpotifyPlaybackController(
            play = spotifyStarter,
            pause = {},
            resume = {},
            seekTo = {},
        ),
        runtime,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val spotifyCommands = Channel<SpotifyCommand>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private var activePlayGeneration: Long = 0L

    private fun publishState(state: EngineState) {
        _state.value = state.copy(playGeneration = activePlayGeneration)
    }
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

    init {
        scope.launch {
            for (command in spotifyCommands) {
                val result = runCatching { command.action() }
                command.completion?.complete(result)
                if (command.completion == null) {
                    result.onFailure { failure -> markSpotifyFailed(failure, command.name) }
                }
            }
        }
    }

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        activePlayGeneration = playGeneration
        val token = synchronized(lifecycleLock) {
            invalidatePendingStartLocked()
            cancelTickerLocked()
            if (!stopProcessLocked()) {
                publishState(EngineState(
                    status = EngineStatus.FAILED,
                    error = "Previous player did not terminate",
                ))
                error("Previous player did not terminate")
            }
            lastHandle = handle
            lastQuality = quality
            userPaused = false
            elapsedOffset = 0
            publishState(EngineState(status = EngineStatus.BUFFERING))
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
                runSpotifyCommand("Spotify Connect playback") { spotify.play(handle.trackId) }
                synchronized(lifecycleLock) {
                    if (token != requestToken) return
                    startedAt = System.currentTimeMillis()
                    publishState(EngineState(
                        status = EngineStatus.PLAYING,
                        positionMs = 0,
                        durationMs = handle.durationMs,
                    ))
                    startTickerLocked()
                }
            }
        }
    }

    override fun pause() {
        if (spotifyIsActive()) {
            synchronized(lifecycleLock) {
                if (_state.value.status != EngineStatus.PLAYING &&
                    _state.value.status != EngineStatus.BUFFERING
                ) return
                elapsedOffset = _state.value.positionMs
                cancelTickerLocked()
                userPaused = true
                publishState(_state.value.copy(status = EngineStatus.PAUSED, error = null))
            }
            enqueueSpotifyCommand("Spotify Connect pause", spotify.pause)
            return
        }
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
                    publishState(_state.value.copy(
                        status = EngineStatus.FAILED,
                        error = "Player could not be paused or stopped",
                    ))
                    return
                }
            }
            publishState(_state.value.copy(status = EngineStatus.PAUSED, error = null))
        }
    }

    override fun resume() {
        if (spotifyIsActive()) {
            synchronized(lifecycleLock) {
                if (_state.value.status != EngineStatus.PAUSED) return
                userPaused = false
                startedAt = System.currentTimeMillis()
                publishState(_state.value.copy(status = EngineStatus.PLAYING, error = null))
                startTickerLocked()
            }
            enqueueSpotifyCommand("Spotify Connect resume", spotify.resume)
            return
        }
        val restart = synchronized(lifecycleLock) {
            val current = _state.value
            if (current.status != EngineStatus.PAUSED) return
            userPaused = false
            val active = activeProcess
            if (active?.process?.isAlive == true &&
                runtime.sendIpc("""["set_property","pause",false]""", active.socket)
            ) {
                startedAt = System.currentTimeMillis()
                publishState(current.copy(status = EngineStatus.PLAYING, error = null))
                startTickerLocked()
                return
            }
            if (!stopProcessLocked()) {
                publishState(current.copy(
                    status = EngineStatus.FAILED,
                    error = "Player did not terminate before resume",
                ))
                return
            }
            val handle = lastHandle ?: return
            invalidatePendingStartLocked()
            publishState(current.copy(status = EngineStatus.BUFFERING, error = null))
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
        if (spotifyIsActive()) {
            val target = positionMs.coerceAtLeast(0)
            synchronized(lifecycleLock) {
                elapsedOffset = target
                startedAt = System.currentTimeMillis()
                publishState(_state.value.copy(positionMs = target, error = null))
            }
            enqueueSpotifyCommand("Spotify Connect seek") { spotify.seekTo(target) }
            return
        }
        val restart = synchronized(lifecycleLock) {
            elapsedOffset = positionMs.coerceAtLeast(0)
            startedAt = System.currentTimeMillis()
            val current = _state.value
            publishState(current.copy(positionMs = elapsedOffset))
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
                publishState(current.copy(
                    status = EngineStatus.FAILED,
                    error = "Player did not terminate before seek",
                ))
                return
            }
            invalidatePendingStartLocked()
            val targetStatus = current.status
            userPaused = targetStatus == EngineStatus.PAUSED
            publishState(_state.value.copy(status = EngineStatus.BUFFERING))
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
        val pauseSpotify = spotifyIsActive()
        synchronized(lifecycleLock) {
            invalidatePendingStartLocked()
            userPaused = false
            cancelTickerLocked()
            if (!stopProcessLocked()) {
                publishState(_state.value.copy(
                    status = EngineStatus.FAILED,
                    error = "Player did not terminate",
                ))
                return
            }
            elapsedOffset = 0
            lastHandle = null
            lastQuality = null
            publishState(EngineState())
        }
        if (pauseSpotify) enqueueSpotifyCommand("Spotify Connect stop", spotify.pause)
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
                        publishState(EngineState(
                            status = EngineStatus.FAILED,
                            positionMs = elapsedOffset,
                            durationMs = durationMs,
                            error = "Player could not be paused or stopped",
                        ))
                        return
                    }
                }
            } else {
                userPaused = false
            }
            startedAt = System.currentTimeMillis()
            publishState(EngineState(
                status = targetStatus,
                positionMs = elapsedOffset,
                durationMs = durationMs,
            ))
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
                publishState(if (code == 0) {
                    _state.value.copy(
                        status = EngineStatus.ENDED,
                        positionMs = durationMs ?: _state.value.positionMs,
                    )
                } else {
                    _state.value.copy(
                        status = EngineStatus.FAILED,
                        error = code?.let { "Player exited with $it" } ?: "Player process failed",
                    )
                })
                cancelTickerLocked()
            }
        }
    }

    private fun startTickerLocked() {
        cancelTickerLocked()
        val token = ++tickerToken
        ticker = scope.launch {
            while (true) {
                val ended = synchronized(lifecycleLock) {
                    if (token != tickerToken || _state.value.status != EngineStatus.PLAYING) {
                        return@launch
                    }
                    val active = activeProcess
                    if (active != null) {
                        val eof = runtime.readProperty("eof-reached", active.socket)
                        if (eof == "yes" || eof == "true") {
                            cancelTickerLocked()
                            publishState(_state.value.copy(
                                status = EngineStatus.ENDED,
                                positionMs = _state.value.durationMs ?: _state.value.positionMs,
                            ))
                            return@synchronized true
                        }
                        val timePos = runtime.readProperty("time-pos", active.socket)?.toDoubleOrNull()
                        if (timePos != null) {
                            val position = (timePos * 1000.0).toLong().coerceAtLeast(0)
                            elapsedOffset = position
                            startedAt = System.currentTimeMillis()
                            publishState(_state.value.copy(positionMs = position))
                            return@synchronized false
                        }
                    }
                    val position = elapsedOffset + (System.currentTimeMillis() - startedAt)
                    val duration = _state.value.durationMs
                        ?: (lastHandle as? PlaybackHandle.ProviderPlayback)?.durationMs
                    val capped = if (duration != null) position.coerceAtMost(duration) else position
                    publishState(_state.value.copy(
                        positionMs = capped,
                        durationMs = duration ?: _state.value.durationMs,
                    ))
                    if (duration != null && position >= duration) {
                        cancelTickerLocked()
                        publishState(_state.value.copy(
                            status = EngineStatus.ENDED,
                            positionMs = duration,
                        ))
                        true
                    } else {
                        false
                    }
                }
                if (ended) return@launch
                delay(400)
            }
        }
    }

    private fun cancelTickerLocked() {
        tickerToken++
        ticker?.cancel()
        ticker = null
    }

    private fun spotifyIsActive(): Boolean = synchronized(lifecycleLock) {
        (lastHandle as? PlaybackHandle.ProviderPlayback)?.provider == ProviderId.SPOTIFY
    }

    private suspend fun runSpotifyCommand(name: String, action: suspend () -> Unit) {
        val completion = CompletableDeferred<Result<Unit>>()
        spotifyCommands.send(SpotifyCommand(name, action, completion))
        completion.await().getOrThrow()
    }

    private fun enqueueSpotifyCommand(name: String, action: suspend () -> Unit) {
        spotifyCommands.trySend(SpotifyCommand(name, action))
    }

    private fun markSpotifyFailed(failure: Throwable, name: String) {
        synchronized(lifecycleLock) {
            if (spotifyIsActive()) {
                cancelTickerLocked()
                publishState(_state.value.copy(
                    status = EngineStatus.FAILED,
                    error = failure.message ?: "$name failed",
                ))
            }
        }
    }

    private data class SpotifyCommand(
        val name: String,
        val action: suspend () -> Unit,
        val completion: CompletableDeferred<Result<Unit>>? = null,
    )

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
    fun readProperty(name: String, socket: Path): String? = null
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
                // Drain one response line so the socket stays healthy for follow-up commands.
                readIpcLine(channel)
            }
            true
        }.getOrDefault(false)
    }

    override fun readProperty(name: String, socket: Path): String? {
        if (!Files.exists(socket)) return null
        return runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socket))
                val payload = """{"command":["get_property","$name"]}""" + "\n"
                channel.write(ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8)))
                val line = readIpcLine(channel) ?: return null
                parseMpvData(line)
            }
        }.getOrNull()
    }

    private fun readIpcLine(channel: SocketChannel): String? {
        channel.configureBlocking(true)
        val buffer = ByteBuffer.allocate(4096)
        val builder = StringBuilder()
        val deadline = System.currentTimeMillis() + 400
        while (System.currentTimeMillis() < deadline) {
            val read = channel.read(buffer)
            if (read < 0) break
            if (read == 0) {
                Thread.sleep(10)
                continue
            }
            buffer.flip()
            while (buffer.hasRemaining()) {
                val c = buffer.get().toInt().toChar()
                if (c == '\n') return builder.toString()
                builder.append(c)
            }
            buffer.clear()
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    private fun parseMpvData(line: String): String? {
        // Minimal parse: "data": <json-value>
        val key = "\"data\":"
        val start = line.indexOf(key)
        if (start < 0) return null
        var i = start + key.length
        while (i < line.length && line[i].isWhitespace()) i++
        if (i >= line.length) return null
        return when (line[i]) {
            '"' -> {
                val end = line.indexOf('"', i + 1)
                if (end < 0) null else line.substring(i + 1, end)
            }
            't', 'f', 'n' -> {
                val end = line.indexOfAny(charArrayOf(',', '}'), i).let { if (it < 0) line.length else it }
                line.substring(i, end).trim()
            }
            else -> {
                val end = line.indexOfAny(charArrayOf(',', '}'), i).let { if (it < 0) line.length else it }
                line.substring(i, end).trim()
            }
        }
    }

    override fun deleteIpcPath(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }
}
