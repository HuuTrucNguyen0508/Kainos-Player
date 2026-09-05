package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.UnsupportedPlaybackException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DesktopPlaybackEngine(
    private val spotifyStarter: suspend (String) -> Unit,
) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private val processRef = AtomicReference<Process?>(null)
    private val userPaused = AtomicBoolean(false)
    private var ticker: Job? = null
    private var startedAt = 0L
    private var elapsedOffset = 0L
    private var lastHandle: PlaybackHandle? = null
    private var lastQuality: AudioQuality? = null

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?) {
        lastHandle = handle
        lastQuality = quality
        userPaused.set(false)
        stopProcess()
        when (handle) {
            is PlaybackHandle.Url -> startUrl(handle.url, startSeconds = 0)
            is PlaybackHandle.ProviderPlayback -> {
                if (handle.provider != ProviderId.SPOTIFY) {
                    throw UnsupportedPlaybackException(
                        "${handle.provider.displayName} does not provide a supported Linux playback mechanism.",
                    )
                }
                spotifyStarter(handle.trackId)
                startedAt = System.currentTimeMillis()
                elapsedOffset = 0
                _state.value = EngineState(
                    status = EngineStatus.PLAYING,
                    durationMs = qualityDurationHint(quality),
                )
                startTicker()
            }
        }
    }

    override fun pause() {
        if (_state.value.status != EngineStatus.PLAYING) return
        elapsedOffset = _state.value.positionMs
        ticker?.cancel()
        userPaused.set(true)
        // Keep the process alive and toggle pause — destroying it used to look like a
        // source failure and triggered "Playback failed and no fallback is available".
        sendStdin("p")
        _state.value = _state.value.copy(status = EngineStatus.PAUSED, error = null)
    }

    override fun resume() {
        val current = _state.value
        if (current.status != EngineStatus.PAUSED) return
        userPaused.set(false)
        val process = processRef.get()
        if (process != null && process.isAlive) {
            sendStdin("p")
            startedAt = System.currentTimeMillis()
            _state.value = current.copy(status = EngineStatus.PLAYING, error = null)
            startTicker()
            return
        }
        val handle = lastHandle ?: return
        scope.launch {
            when (handle) {
                is PlaybackHandle.Url -> startUrl(handle.url, startSeconds = elapsedOffset / 1000)
                is PlaybackHandle.ProviderPlayback -> play(handle, lastQuality)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        elapsedOffset = positionMs.coerceAtLeast(0)
        startedAt = System.currentTimeMillis()
        _state.value = _state.value.copy(positionMs = elapsedOffset)
        val handle = lastHandle
        if (handle is PlaybackHandle.Url && _state.value.status == EngineStatus.PLAYING) {
            userPaused.set(false)
            scope.launch { startUrl(handle.url, startSeconds = elapsedOffset / 1000) }
        }
    }

    override fun stop() {
        userPaused.set(false)
        stopProcess()
        ticker?.cancel()
        elapsedOffset = 0
        lastHandle = null
        _state.value = EngineState()
    }

    override fun setVolume(volume: Float) = Unit

    private fun startUrl(url: String, startSeconds: Long) {
        stopProcess()
        val command = mediaCommand(url, startSeconds) ?: throw UnsupportedPlaybackException(
            "Install mpv or ffplay to play HTTP streams on Linux.",
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        processRef.set(process)
        startedAt = System.currentTimeMillis()
        _state.value = EngineState(
            status = EngineStatus.PLAYING,
            positionMs = elapsedOffset,
            durationMs = _state.value.durationMs ?: qualityDurationHint(lastQuality),
        )
        startTicker()
        scope.launch {
            val code = process.waitFor()
            if (processRef.get() !== process) return@launch
            if (userPaused.get()) return@launch
            _state.value = if (code == 0) {
                _state.value.copy(status = EngineStatus.ENDED)
            } else {
                _state.value.copy(status = EngineStatus.FAILED, error = "Player exited with $code")
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (_state.value.status == EngineStatus.PLAYING) {
                val position = elapsedOffset + (System.currentTimeMillis() - startedAt)
                _state.value = _state.value.copy(positionMs = position)
                delay(400)
            }
        }
    }

    private fun stopProcess() {
        processRef.getAndSet(null)?.destroy()
    }

    private fun sendStdin(command: String) {
        runCatching {
            processRef.get()?.outputStream?.apply {
                write(command.toByteArray())
                flush()
            }
        }
    }

    private fun mediaCommand(url: String, startSeconds: Long): List<String>? {
        val mpv = findOnPath("mpv")
        if (mpv != null) {
            return buildList {
                add(mpv)
                add("--no-video")
                add("--really-quiet")
                add("--input-terminal=yes")
                if (startSeconds > 0) add("--start=$startSeconds")
                add(url)
            }
        }
        val ffplay = findOnPath("ffplay")
        if (ffplay != null) {
            return buildList {
                add(ffplay)
                add("-nodisp")
                add("-autoexit")
                add("-loglevel")
                add("quiet")
                if (startSeconds > 0) {
                    add("-ss")
                    add(startSeconds.toString())
                }
                add(url)
            }
        }
        return null
    }

    private fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(':').firstNotNullOfOrNull { dir ->
            val file = java.io.File(dir, name)
            if (file.canExecute()) file.absolutePath else null
        }
    }

    private fun qualityDurationHint(quality: AudioQuality?): Long? = null
}
