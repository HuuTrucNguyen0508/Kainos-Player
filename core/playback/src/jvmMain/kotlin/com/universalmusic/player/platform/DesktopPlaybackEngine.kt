package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.SpotifyObservationGate
import com.universalmusic.player.domain.playback.reconcileSpotifyObservation
import com.universalmusic.player.domain.playback.spotifyObservationStillValid
import com.universalmusic.player.domain.playback.spotifyPauseGuardOpen
import com.universalmusic.player.domain.playback.UnsupportedPlaybackException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.completeWith
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
    private var spotifyPauseAcknowledged = false
    private var spotifyPauseCommandId = 0L
    private var spotifyPauseCommandCompleted = false
    private var spotifyObservationsSincePause = 0
    private var transportGeneration = 0L
    private var ticker: Job? = null
    private var spotifyReconcile: Job? = null
    private var spotifyMisses = 0
    private var tickerToken = 0L
    private var startedAt = 0L
    private var elapsedOffset = 0L
    private var lastHandle: PlaybackHandle? = null
    private var lastQuality: AudioQuality? = null
    /**
     * Bumped (under [lifecycleLock]) by every local mpv transport decision: play / stop / pause /
     * resume / seek. Async IPC results captured under an older epoch are stale and must not publish.
     */
    private var mpvEpoch = 0L
    /**
     * Single-consumer FIFO for all mpv socket I/O. Jobs are enqueued while holding [lifecycleLock]
     * so queue order matches the order of lock-protected transport decisions; the jobs themselves
     * run on Dispatchers.IO and never hold the lock across socket I/O.
     */
    private val ipcJobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    @Volatile
    private var volumePercent: Int = 100
    private val shutdownHook = if (runtime === SystemDesktopPlaybackRuntime) {
        Thread({ shutdown() }, "kainos-mpv-shutdown").also(Runtime.getRuntime()::addShutdownHook)
    } else {
        null
    }

    init {
        scope.launch {
            for (command in spotifyCommands) {
                val result = runCatching { command.action() }
                command.pauseCommandId?.let { pauseCommandId ->
                    synchronized(lifecycleLock) {
                        if (userPaused && spotifyPauseCommandId == pauseCommandId) {
                            spotifyPauseCommandCompleted = true
                        }
                    }
                }
                command.completion?.complete(result)
                if (command.completion == null) {
                    result.onFailure { failure -> markSpotifyFailed(failure, command.name) }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (job in ipcJobs) {
                try {
                    job()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A failed IPC job must not stop the FIFO consumer.
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
            spotifyPauseAcknowledged = false
            clearPauseGuardLocked()
            noteLocalTransportLocked()
            mpvEpoch++
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
                    startSpotifyReconcileLocked(token)
                }
            }
        }
    }

    override fun pause() {
        if (spotifyIsActive()) {
            val pauseCommandId = synchronized(lifecycleLock) {
                if (_state.value.status != EngineStatus.PLAYING &&
                    _state.value.status != EngineStatus.BUFFERING
                ) return
                elapsedOffset = _state.value.positionMs
                cancelTickerLocked()
                userPaused = true
                spotifyPauseAcknowledged = false
                clearPauseGuardLocked()
                noteLocalTransportLocked()
                publishState(_state.value.copy(status = EngineStatus.PAUSED, error = null))
                ++spotifyPauseCommandId
            }
            enqueueSpotifyCommand("Spotify Connect pause", spotify.pause, pauseCommandId)
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
            mpvEpoch++
            val active = activeProcess
            // Publish PAUSED now so the caller (UI / MPRIS) never waits on mpv; the IPC runs on the
            // FIFO worker and falls back to killing the process if mpv does not acknowledge.
            publishState(_state.value.copy(status = EngineStatus.PAUSED, error = null))
            if (active != null) {
                enqueueIpcLocked {
                    if (sendIpcIfActive(active, """["set_property","pause",true]""")) return@enqueueIpcLocked
                    synchronized(lifecycleLock) {
                        // Only act if this process is still ours and the user still wants it paused.
                        if (activeProcess !== active || !userPaused) return@synchronized
                        if (!stopProcessLocked()) {
                            publishState(_state.value.copy(
                                status = EngineStatus.FAILED,
                                error = "Player could not be paused or stopped",
                            ))
                        }
                    }
                }
            }
        }
    }

    override fun resume() {
        if (spotifyIsActive()) {
            synchronized(lifecycleLock) {
                if (_state.value.status != EngineStatus.PAUSED) return
                userPaused = false
                spotifyPauseAcknowledged = false
                clearPauseGuardLocked()
                noteLocalTransportLocked()
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
            val epoch = ++mpvEpoch
            val active = activeProcess
            if (active?.process?.isAlive == true) {
                // Optimistically resume so a quick pause() after resume() is honoured; if mpv does
                // not acknowledge, the worker falls back to the restart path below.
                startedAt = System.currentTimeMillis()
                publishState(current.copy(status = EngineStatus.PLAYING, error = null))
                startTickerLocked()
                enqueueIpcLocked {
                    if (sendIpcIfActive(active, """["set_property","pause",false]""")) return@enqueueIpcLocked
                    val fallback = synchronized(lifecycleLock) {
                        if (mpvEpoch != epoch || activeProcess !== active) return@enqueueIpcLocked
                        cancelTickerLocked()
                        resumeRestartLocked(_state.value)
                    } ?: return@enqueueIpcLocked
                    launchRestart(fallback)
                }
                return
            }
            resumeRestartLocked(current) ?: return
        }
        launchRestart(restart)
    }

    private fun resumeRestartLocked(current: EngineState): Restart? {
        if (!stopProcessLocked()) {
            publishState(current.copy(
                status = EngineStatus.FAILED,
                error = "Player did not terminate before resume",
            ))
            return null
        }
        val handle = lastHandle ?: return null
        invalidatePendingStartLocked()
        publishState(current.copy(status = EngineStatus.BUFFERING, error = null))
        return Restart(handle, lastQuality, elapsedOffset / 1000, requestToken, EngineStatus.PLAYING)
    }

    private fun launchRestart(restart: Restart) {
        scope.launch {
            when (val handle = restart.handle) {
                is PlaybackHandle.Url -> startUrl(
                    url = handle.url,
                    startSeconds = restart.startSeconds,
                    token = restart.token,
                    targetStatus = restart.targetStatus,
                )
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
                noteLocalTransportLocked()
                publishState(_state.value.copy(positionMs = target, error = null))
            }
            enqueueSpotifyCommand("Spotify Connect seek", action = { spotify.seekTo(target) })
            return
        }
        val restart = synchronized(lifecycleLock) {
            elapsedOffset = positionMs.coerceAtLeast(0)
            startedAt = System.currentTimeMillis()
            val current = _state.value
            publishState(current.copy(positionMs = elapsedOffset))
            val handle = lastHandle
            if (handle !is PlaybackHandle.Url) return
            val epoch = ++mpvEpoch
            val active = activeProcess
            if (active?.process?.isAlive == true) {
                val seconds = elapsedOffset / 1000.0
                enqueueIpcLocked {
                    val ok = sendIpcIfActive(active, """["set_property","time-pos",$seconds]""")
                    val fallback = synchronized(lifecycleLock) {
                        // A newer transport command (or track) owns the state now.
                        if (mpvEpoch != epoch || lastHandle !== handle) return@enqueueIpcLocked
                        if (ok) {
                            if (_state.value.status == EngineStatus.PLAYING) {
                                startedAt = System.currentTimeMillis()
                            }
                            return@enqueueIpcLocked
                        }
                        seekRestartLocked(handle, _state.value)
                    } ?: return@enqueueIpcLocked
                    launchRestart(fallback)
                }
                return
            }
            seekRestartLocked(handle, current) ?: return
        }
        launchRestart(restart)
    }

    private fun seekRestartLocked(handle: PlaybackHandle.Url, current: EngineState): Restart? {
        if (current.status != EngineStatus.PLAYING && current.status != EngineStatus.PAUSED) return null
        if (!stopProcessLocked()) {
            publishState(current.copy(
                status = EngineStatus.FAILED,
                error = "Player did not terminate before seek",
            ))
            return null
        }
        invalidatePendingStartLocked()
        val targetStatus = current.status
        userPaused = targetStatus == EngineStatus.PAUSED
        publishState(_state.value.copy(status = EngineStatus.BUFFERING))
        return Restart(handle, lastQuality, elapsedOffset / 1000, requestToken, targetStatus)
    }

    override fun stop() {
        val pauseSpotify = spotifyIsActive()
        synchronized(lifecycleLock) {
            invalidatePendingStartLocked()
            userPaused = false
            spotifyPauseAcknowledged = false
            clearPauseGuardLocked()
            noteLocalTransportLocked()
            mpvEpoch++
            cancelTickerLocked()
            cancelSpotifyReconcileLocked()
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
        volumePercent = percent
        synchronized(lifecycleLock) {
            val active = activeProcess ?: return
            enqueueIpcLocked { sendIpcIfActive(active, """["set_property","volume",$percent]""") }
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
                add("--volume=$volumePercent")
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
        // Once mpv is launched, finish publishing its state even if the caller is cancelled
        // (matches the previous blocking behaviour); socket I/O never runs under the lock.
        val pauseAcknowledged = withContext(NonCancellable + Dispatchers.IO) {
            runtime.waitForIpc(active.socket)
            val needsIpc = synchronized(lifecycleLock) {
                if (token != requestToken || activeProcess !== active) return@withContext false
                if (targetStatus == EngineStatus.PAUSED) userPaused = true
                startSeconds > 0 || targetStatus == EngineStatus.PAUSED
            }
            if (!needsIpc) return@withContext true
            awaitIpc {
                if (startSeconds > 0) {
                    sendIpcIfActive(active, """["set_property","time-pos",$startSeconds]""")
                }
                targetStatus != EngineStatus.PAUSED ||
                    sendIpcIfActive(active, """["set_property","pause",true]""")
            }
        }
        synchronized(lifecycleLock) {
            if (token != requestToken || activeProcess !== active) return
            if (targetStatus == EngineStatus.PAUSED) {
                userPaused = true
                if (!pauseAcknowledged) {
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
                val snapshot = synchronized(lifecycleLock) {
                    if (token != tickerToken || _state.value.status != EngineStatus.PLAYING) {
                        return@launch
                    }
                    TickerSnapshot(activeProcess, mpvEpoch)
                }
                // Socket reads happen on the IPC worker, outside the lock.
                val sample = snapshot.active?.let { active -> awaitIpc { readTickerSample(active) } }
                val ended = synchronized(lifecycleLock) {
                    if (token != tickerToken || _state.value.status != EngineStatus.PLAYING) {
                        return@launch
                    }
                    val active = snapshot.active
                    if (active != null && (activeProcess !== active || mpvEpoch != snapshot.epoch)) {
                        // Stale sample: the process was replaced or a seek/pause/resume happened
                        // while the read was in flight. Do not publish it.
                        return@synchronized false
                    }
                    if (sample != null) {
                        if (sample.eof) {
                            cancelTickerLocked()
                            publishState(_state.value.copy(
                                status = EngineStatus.ENDED,
                                positionMs = _state.value.durationMs ?: _state.value.positionMs,
                            ))
                            return@synchronized true
                        }
                        val timePos = sample.timePosSeconds
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

    private class TickerSnapshot(val active: ActiveProcess?, val epoch: Long)

    private class TickerSample(val eof: Boolean, val timePosSeconds: Double?)

    /** Runs on the IPC worker. Returns null when [active] is no longer the current process. */
    private fun readTickerSample(active: ActiveProcess): TickerSample? {
        if (!isActiveProcess(active)) return null
        val eof = runtime.readProperty("eof-reached", active.socket)
        if (eof == "yes" || eof == "true") return TickerSample(eof = true, timePosSeconds = null)
        val timePos = runtime.readProperty("time-pos", active.socket)?.toDoubleOrNull()
        return TickerSample(eof = false, timePosSeconds = timePos)
    }

    /** Must be called with [lifecycleLock] held so FIFO order matches transport decision order. */
    private fun enqueueIpcLocked(job: suspend () -> Unit) {
        ipcJobs.trySend(job)
    }

    /** Suspends (without holding the lock) until [block] has run in FIFO order on the IPC worker. */
    private suspend fun <T> awaitIpc(block: () -> T): T {
        val result = CompletableDeferred<T>()
        synchronized(lifecycleLock) {
            enqueueIpcLocked { result.completeWith(runCatching(block)) }
        }
        return result.await()
    }

    private fun isActiveProcess(active: ActiveProcess): Boolean =
        synchronized(lifecycleLock) { activeProcess === active }

    /** Socket I/O for [active]; skipped (false, like a failed IPC) once it is no longer current. */
    private fun sendIpcIfActive(active: ActiveProcess, commandArrayJson: String): Boolean =
        isActiveProcess(active) && runtime.sendIpc(commandArrayJson, active.socket)

    private fun cancelTickerLocked() {
        tickerToken++
        ticker?.cancel()
        ticker = null
    }

    private fun cancelSpotifyReconcileLocked() {
        spotifyReconcile?.cancel()
        spotifyReconcile = null
        spotifyMisses = 0
    }

    private fun startSpotifyReconcileLocked(token: Long) {
        cancelSpotifyReconcileLocked()
        val observe = spotify.observe ?: return
        spotifyReconcile = scope.launch {
            while (true) {
                delay(SPOTIFY_OBSERVE_INTERVAL_MS)
                val snapshot = synchronized(lifecycleLock) {
                    if (token != requestToken || !spotifyIsActive()) return@launch
                    SpotifyReconcileSnapshot(
                        status = _state.value.status,
                        positionMs = _state.value.positionMs,
                        durationMs = _state.value.durationMs,
                        expectedTrackId = (lastHandle as? PlaybackHandle.ProviderPlayback)?.trackId,
                        userPaused = userPaused,
                        pauseAcknowledged = spotifyPauseAcknowledged,
                        pauseGuardOpen = spotifyPauseGuardOpen(
                            pauseCommandCompleted = spotifyPauseCommandCompleted,
                            observationsSincePause = spotifyObservationsSincePause,
                        ),
                        transportGeneration = transportGeneration,
                        misses = spotifyMisses,
                    )
                }
                val observed = runCatching { observe() }.getOrNull()
                val decision = reconcileSpotifyObservation(
                    status = snapshot.status,
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    expectedTrackId = snapshot.expectedTrackId,
                    userPaused = snapshot.userPaused,
                    misses = snapshot.misses,
                    observed = observed,
                    pauseAcknowledged = snapshot.pauseAcknowledged,
                    pauseGuardOpen = snapshot.pauseGuardOpen,
                )
                synchronized(lifecycleLock) {
                    val currentGate = SpotifyObservationGate(
                        requestToken = requestToken,
                        transportGeneration = transportGeneration,
                        userPaused = userPaused,
                    )
                    val capturedGate = SpotifyObservationGate(
                        requestToken = token,
                        transportGeneration = snapshot.transportGeneration,
                        userPaused = snapshot.userPaused,
                    )
                    if (!spotifyObservationStillValid(capturedGate, currentGate, spotifyIsActive())) {
                        return@synchronized
                    }
                    spotifyPauseAcknowledged = decision.pauseAcknowledged
                    if (decision.clearUserPause) {
                        userPaused = false
                        clearPauseGuardLocked()
                    } else if (snapshot.userPaused && observed != null) {
                        spotifyObservationsSincePause++
                    }
                    spotifyMisses = decision.misses
                    if (decision.snapClock) {
                        elapsedOffset = decision.positionMs
                        startedAt = System.currentTimeMillis()
                    }
                    val previous = _state.value.status
                    publishState(_state.value.copy(
                        status = decision.status,
                        positionMs = decision.positionMs,
                        syncWarning = decision.syncWarning,
                    ))
                    when (decision.status) {
                        EngineStatus.PLAYING -> if (previous != EngineStatus.PLAYING) startTickerLocked()
                        EngineStatus.PAUSED, EngineStatus.ENDED, EngineStatus.FAILED -> cancelTickerLocked()
                        else -> Unit
                    }
                }
            }
        }
    }

    private data class SpotifyReconcileSnapshot(
        val status: EngineStatus,
        val positionMs: Long,
        val durationMs: Long?,
        val expectedTrackId: String?,
        val userPaused: Boolean,
        val pauseAcknowledged: Boolean,
        val pauseGuardOpen: Boolean,
        val transportGeneration: Long,
        val misses: Int,
    )

    private fun noteLocalTransportLocked() {
        transportGeneration++
    }

    private fun clearPauseGuardLocked() {
        spotifyPauseCommandCompleted = false
        spotifyObservationsSincePause = 0
    }

    private fun spotifyIsActive(): Boolean = synchronized(lifecycleLock) {
        (lastHandle as? PlaybackHandle.ProviderPlayback)?.provider == ProviderId.SPOTIFY
    }

    private suspend fun runSpotifyCommand(name: String, action: suspend () -> Unit) {
        val completion = CompletableDeferred<Result<Unit>>()
        spotifyCommands.send(SpotifyCommand(name, action, completion))
        completion.await().getOrThrow()
    }

    private fun enqueueSpotifyCommand(
        name: String,
        action: suspend () -> Unit,
        pauseCommandId: Long? = null,
    ) {
        spotifyCommands.trySend(SpotifyCommand(name, action, pauseCommandId = pauseCommandId))
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
        val pauseCommandId: Long? = null,
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

    private companion object {
        const val SPOTIFY_OBSERVE_INTERVAL_MS = 4_000L
    }

    private fun shutdown() {
        synchronized(lifecycleLock) {
            invalidatePendingStartLocked()
            cancelTickerLocked()
            cancelSpotifyReconcileLocked()
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

    override fun sendIpc(commandArrayJson: String, socket: Path): Boolean =
        // Drain one response line so the socket stays healthy; a timeout counts as a failed IPC.
        exchange(socket, """{"command":$commandArrayJson}""") !is IpcReply.Failed

    override fun readProperty(name: String, socket: Path): String? =
        when (val reply = exchange(socket, """{"command":["get_property","$name"]}""")) {
            is IpcReply.Line -> parseMpvData(reply.text)
            else -> null
        }

    private sealed interface IpcReply {
        class Line(val text: String) : IpcReply
        /** mpv closed the connection without a reply line (treated as sent, as before). */
        object Closed : IpcReply
        /** Missing socket, connect/write/read failure, or deadline exceeded. */
        object Failed : IpcReply
    }

    /**
     * One request/response over a fresh non-blocking unix socket, bounded by
     * [IPC_CONNECT_TIMEOUT_MS] for connect and [IPC_IO_TIMEOUT_MS] for write + read.
     * SocketChannel has no SO_TIMEOUT, so readiness is awaited with a [Selector].
     */
    private fun exchange(socket: Path, requestJson: String): IpcReply {
        if (!Files.exists(socket)) return IpcReply.Failed
        return runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                Selector.open().use { selector ->
                    channel.configureBlocking(false)
                    val key = channel.register(selector, 0)
                    if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                        val connectDeadline = System.currentTimeMillis() + IPC_CONNECT_TIMEOUT_MS
                        key.interestOps(SelectionKey.OP_CONNECT)
                        while (!channel.finishConnect()) {
                            if (!awaitReady(selector, connectDeadline)) return IpcReply.Failed
                        }
                    }
                    val ioDeadline = System.currentTimeMillis() + IPC_IO_TIMEOUT_MS
                    val payload = ByteBuffer.wrap((requestJson + "\n").toByteArray(Charsets.UTF_8))
                    key.interestOps(SelectionKey.OP_WRITE)
                    while (payload.hasRemaining()) {
                        if (channel.write(payload) == 0 && !awaitReady(selector, ioDeadline)) {
                            return IpcReply.Failed
                        }
                    }
                    key.interestOps(SelectionKey.OP_READ)
                    readIpcReply(channel, selector, ioDeadline)
                }
            }
        }.getOrDefault(IpcReply.Failed)
    }

    /** Waits for the registered interest op until [deadline]; false once the deadline passed. */
    private fun awaitReady(selector: Selector, deadline: Long): Boolean {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return false
        selector.select(remaining)
        selector.selectedKeys().clear()
        return true
    }

    private fun readIpcReply(channel: SocketChannel, selector: Selector, deadline: Long): IpcReply {
        val buffer = ByteBuffer.allocate(4096)
        val line = java.io.ByteArrayOutputStream()
        while (true) {
            val read = channel.read(buffer)
            if (read < 0) {
                val partial = line.toString(Charsets.UTF_8)
                return if (partial.isNotBlank()) IpcReply.Line(partial) else IpcReply.Closed
            }
            if (read == 0) {
                if (!awaitReady(selector, deadline)) return IpcReply.Failed
                continue
            }
            buffer.flip()
            while (buffer.hasRemaining()) {
                val byte = buffer.get()
                if (byte == '\n'.code.toByte()) {
                    val text = line.toString(Charsets.UTF_8)
                    line.reset()
                    // mpv broadcasts events (pause, seek, ...) to every client; skip to our reply.
                    if (!isMpvEventLine(text)) return IpcReply.Line(text)
                } else {
                    line.write(byte.toInt())
                }
            }
            buffer.clear()
        }
    }

    private fun isMpvEventLine(line: String): Boolean =
        line.contains("\"event\"") && !line.contains("\"error\"")

    private const val IPC_CONNECT_TIMEOUT_MS = 1_000L
    private const val IPC_IO_TIMEOUT_MS = 1_500L

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
