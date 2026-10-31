package com.universalmusic.player.platform

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PreparedPlayback
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.universalmusic.player.domain.playback.SpotifyObservationGate
import com.universalmusic.player.domain.playback.SpotifyObservedPlayback
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    private val spotifyObserveIntervalMs: Long = SPOTIFY_OBSERVE_INTERVAL_MS,
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
    override val supportsGapless: Boolean get() = runtime.supportsEvents
    private var gaplessEnabled = false
    private var preparedNext: PreparedPlayback? = null

    override fun configureGapless(enabled: Boolean) {
        synchronized(lifecycleLock) { gaplessEnabled = enabled }
        if (!enabled) prepareNext(null)
    }

    override fun prepareNext(next: PreparedPlayback?) {
        synchronized(lifecycleLock) {
            val active = activeProcess ?: return
            if (!active.gapless) return
            val candidate = next?.takeIf { gaplessEnabled && it.generation == activePlayGeneration && localFile(it.handle.url) }
            preparedNext = candidate
            enqueueIpcLocked {
                if (!isActiveProcess(active)) return@enqueueIpcLocked
                // Remove the old successor before probing a potentially incompatible replacement.
                runtime.sendIpc("""["playlist-clear"]""", active.socket)
                val currentUrl = synchronized(lifecycleLock) { (lastHandle as? PlaybackHandle.Url)?.url }
                val compatible = candidate == null || (currentUrl != null && runtime.canPrepare(currentUrl, candidate.handle.url))
                if (!compatible) {
                    synchronized(lifecycleLock) { if (preparedNext === candidate) preparedNext = null }
                    runtime.sendIpc("""["set_property","gapless-audio","no"]""", active.socket)
                    synchronized(lifecycleLock) {
                        if (activeProcess === active && active.awaitingSuccessor) {
                            active.awaitingSuccessor = false
                            publishState(_state.value.copy(status = EngineStatus.ENDED))
                        }
                    }
                    return@enqueueIpcLocked
                }
                runtime.sendIpc("""["set_property","gapless-audio","${if (candidate == null) "no" else "yes"}"]""", active.socket)
                if (candidate == null) synchronized(lifecycleLock) {
                    if (activeProcess === active && active.awaitingSuccessor) {
                        active.awaitingSuccessor = false
                        publishState(_state.value.copy(status = EngineStatus.ENDED))
                    }
                }
                if (candidate != null) {
                    val duration = runtime.probeDurationMs(candidate.handle.url)
                    synchronized(lifecycleLock) {
                        if (preparedNext !== candidate || activeProcess !== active) return@enqueueIpcLocked
                        active.nextDuration = duration
                    }
                    val id = runtime.loadEntry("""["loadfile",${Json.encodeToString(candidate.handle.url)},"append-play"]""", active.socket)
                    synchronized(lifecycleLock) {
                        if (id != null) active.entries[id] = candidate to duration
                        else if (preparedNext === candidate) preparedNext = null
                    }
                }
            }
        }
    }

    private fun localFile(url: String): Boolean = url.startsWith("file:") || !url.contains("://")


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
    private var spotifyReceiver: Job? = null
    private var spotifyReceiverEndTimeout: Job? = null
    private var spotifyReceiverConfirmed = false
    private var spotifyReceiverEndPending = false
    private var spotifyReceiverStopped = false
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
        try {
            playInternal(handle, quality, playGeneration)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (activePlayGeneration == playGeneration) {
                publishState(_state.value.copy(status = EngineStatus.FAILED, error = failure.message))
            }
            throw failure
        }
    }

    private suspend fun playInternal(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        val token = synchronized(lifecycleLock) {
            activePlayGeneration = playGeneration
            invalidatePendingStartLocked()
            cancelTickerLocked()
            cancelSpotifyReceiverLocked()
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
            elapsedOffset = (handle as? PlaybackHandle.Url)?.startPositionMs ?: 0
            publishState(EngineState(status = EngineStatus.BUFFERING))
            requestToken
        }
        when (handle) {
            is PlaybackHandle.Url -> startUrl(handle.url, startSeconds = handle.startPositionMs / 1000.0, token = token)
            is PlaybackHandle.ProviderPlayback -> {
                if (handle.provider != ProviderId.SPOTIFY) {
                    throw UnsupportedPlaybackException(
                        "${handle.provider.displayName} does not provide a supported Linux playback mechanism.",
                    )
                }
                synchronized(lifecycleLock) {
                    if (token != requestToken) return
                    startSpotifyReceiverLocked(token)
                }
                runSpotifyCommand("Spotify Connect playback") { spotify.play(handle.trackId) }
                synchronized(lifecycleLock) {
                    if (token != requestToken) return
                    if (_state.value.status == EngineStatus.BUFFERING) {
                        startedAt = System.currentTimeMillis()
                        publishState(EngineState(
                            status = EngineStatus.PLAYING,
                            positionMs = 0,
                            durationMs = handle.durationMs,
                        ))
                        startTickerLocked()
                    }
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
                if (spotifyReceiverEndPending) return
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
                if (spotifyReceiverEndPending) {
                    if (spotifyReceiverStopped) finishSpotifyReceiverTrackLocked()
                    else publishState(_state.value.copy(status = EngineStatus.PLAYING))
                    return
                }
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
        return Restart(handle, lastQuality, elapsedOffset / 1000.0, requestToken, EngineStatus.PLAYING)
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
            // mpv may switch its decoder before start-file reaches our FIFO. There is no
            // atomic conditional seek in JSON IPC, so reload the owning local entry.
            if (active?.gapless == true) {
                seekRestartLocked(handle, current)?.let(::launchRestart)
                return
            }
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
        return Restart(handle, lastQuality, elapsedOffset / 1000.0, requestToken, targetStatus)
    }

    override fun stopForTransition() {
        synchronized(lifecycleLock) {
            // The receiver has already stopped after its end-of-queue reset. A Web API pause
            // here races the following play and can leave Connect reporting paused at zero.
            if (spotifyReceiverConfirmed && _state.value.status == EngineStatus.ENDED) return
        }
        stop()
    }

    override fun stop() {
        val pauseSpotify = synchronized(lifecycleLock) {
            spotifyIsActive() && !(spotifyReceiverConfirmed && _state.value.status == EngineStatus.ENDED)
        }
        synchronized(lifecycleLock) {
            invalidatePendingStartLocked()
            userPaused = false
            spotifyPauseAcknowledged = false
            clearPauseGuardLocked()
            noteLocalTransportLocked()
            mpvEpoch++
            cancelTickerLocked()
            cancelSpotifyReconcileLocked()
            cancelSpotifyReceiverLocked()
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

    private var volumeJob: Job? = null
    override fun setVolume(volume: Float) {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(250)
            if (spotifyIsActive()) {
                runCatching { spotify.setVolume(volume.coerceIn(0f, 1f)) }
                    .onFailure { PlaybackTrace.log("Engine", "Spotify volume update failed") }
            }
        }

        val percent = (volume.coerceIn(0f, 1f) * 100).toInt()
        volumePercent = percent
        synchronized(lifecycleLock) {
            val active = activeProcess ?: return
            enqueueIpcLocked { sendIpcIfActive(active, """["set_property","volume",$percent]""") }
        }
    }

    private suspend fun startUrl(
        url: String,
        startSeconds: Double,
        token: Long,
        targetStatus: EngineStatus = EngineStatus.PLAYING,
    ) {
        if (runtime === SystemDesktopPlaybackRuntime) DesktopPlaybackWarmup.playbackRequested()
        val mpv = runtime.findOnPath("mpv")
            ?: throw UnsupportedPlaybackException("Install mpv for in-app local playback on Linux.")
        val suppliedDuration = synchronized(lifecycleLock) {
            if (token != requestToken) return
            (lastHandle as? PlaybackHandle.Url)?.takeIf { it.url == url }?.durationMs
        }
        val durationMs = withContext(Dispatchers.IO) { runtime.probeDurationMs(url) } ?: suppliedDuration
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val active = synchronized(lifecycleLock) {
            if (token != requestToken) return
            val seamless = gaplessEnabled && localFile(url) && runtime.supportsEvents
            val warm = if (seamless && runtime === SystemDesktopPlaybackRuntime) DesktopPlaybackWarmup.take() else null
            val socket = warm?.second ?: runtime.createIpcPath()
            val command = buildList {
                add(mpv)
                add("--no-video")
                add("--force-window=no")
                add("--really-quiet")
                add("--no-terminal")
                add(if (seamless) "--idle=yes" else "--idle=no")
                if (seamless) {
                    add("--gapless-audio=no")
                    add("--prefetch-playlist=yes")
                }
                add("--keep-open=no")
                add("--volume=$volumePercent")
                add("--input-ipc-server=$socket")
                if (targetStatus == EngineStatus.PAUSED) add("--pause")
                if (startSeconds > 0 && !seamless) add("--start=$startSeconds")
                if (!seamless) add(url)
            }
            val process = try {
                warm?.first ?: runtime.startProcess(command)
            } catch (error: Throwable) {
                runtime.deleteIpcPath(socket)
                throw error
            }
            ActiveProcess(process, socket, seamless).also { activeProcess = it }
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
            if (active.gapless) {
                sendIpcIfActive(active, """["set_property","volume",$volumePercent]""")
                if (targetStatus == EngineStatus.PAUSED) {
                    check(sendIpcIfActive(active, """["set_property","pause",true]"""))
                }
                val events = runtime.openEvents(active.socket) ?: error("mpv event connection unavailable")
                synchronized(lifecycleLock) {
                    if (activeProcess !== active) { events.close(); return@withContext false }
                    active.events = events
                }
                scope.launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            val line = events.readLine() ?: break
                            synchronized(lifecycleLock) { enqueueIpcLocked { handleMpvEvent(active, line) } }
                        }
                    } catch (_: Exception) {
                        // Closing the channel during stop wakes this blocking reader.
                    } finally {
                        synchronized(lifecycleLock) {
                            if (activeProcess === active && active.process.isAlive) {
                                publishState(_state.value.copy(status = EngineStatus.FAILED,
                                    retryAtPositionMs = _state.value.positionMs,
                                    error = "Player event connection lost"))
                            }
                        }
                    }
                }
                check(awaitIpc {
                    val id = runtime.loadEntry("""["loadfile",${Json.encodeToString(url)},"replace",-1,{"start":"$startSeconds"}]""", active.socket)
                    synchronized(lifecycleLock) { active.entryId = id }
                    id != null
                })
            }
            if (!needsIpc) return@withContext true
            awaitIpc {
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

    private fun handleMpvEvent(active: ActiveProcess, line: String) {
        val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        synchronized(lifecycleLock) {
            if (activeProcess !== active) return
            when (event["event"]?.jsonPrimitive?.content) {
                "idle" -> active.outputDrained = true
                "start-file" -> {
                    active.outputDrained = false
                    active.decodingEntryId = event["playlist_entry_id"]?.jsonPrimitive?.content?.toLongOrNull()
                }
                "file-loaded" -> {
                    if (!active.loaded && (active.decodingEntryId == null || active.decodingEntryId == active.entryId)) {
                        active.loaded = true
                        return
                    }
                    val binding = active.entries[active.decodingEntryId]
                    val next = binding?.first?.takeIf { it === preparedNext } ?: run {
                        // Cancellation raced an already decoded file. Let the session select the
                        // current successor again rather than playing an unowned queue item.
                        cancelTickerLocked()
                        stopProcessLocked()
                        publishState(_state.value.copy(status = EngineStatus.ENDED))
                        return
                    }
                    if (next.generation != activePlayGeneration) return
                    preparedNext = null
                    active.awaitingSuccessor = false
                    active.crossing = next
                    active.crossingEntryId = active.decodingEntryId
                    active.nextDuration = binding.second
                }
                "playback-restart" -> {
                    val next = active.crossing ?: return
                    if (active.crossingRunning) return
                    active.crossingRunning = true
                    scope.launch {
                        // file-loaded means decoding changed, possibly while the previous audio
                        // is still buffered. audio-pts includes the output driver delay.
                        var unresolvedMs = 0L
                        var lastSample = System.nanoTime()
                        while (true) {
                            val pts = awaitIpc { runtime.readProperty("audio-pts", active.socket)?.toDoubleOrNull() }
                            val now = System.nanoTime()
                            val crossed = synchronized(lifecycleLock) {
                                if (activeProcess !== active || active.crossing !== next) return@launch
                                if (!userPaused) unresolvedMs += (now - lastSample) / 1_000_000
                                active.outputDrained || (pts != null && pts.isFinite() && pts >= 0)
                            }
                            if (crossed) break
                            if (unresolvedMs >= 2_000) {
                                synchronized(lifecycleLock) {
                                    if (activeProcess !== active || active.crossing !== next) return@launch
                                    // No output evidence: discard lookahead and let the session
                                    // resolve the selected successor normally, without claiming it played.
                                    stopProcessLocked()
                                    cancelTickerLocked()
                                    publishState(_state.value.copy(status = EngineStatus.ENDED))
                                }
                                return@launch
                            }
                            lastSample = now
                            delay(10)
                        }
                        synchronized(lifecycleLock) {
                            if (activeProcess !== active || active.crossing !== next || next.generation != activePlayGeneration) return@synchronized
                            active.entryId = active.crossingEntryId
                            active.entries.remove(active.entryId)
                            active.crossing = null
                            active.crossingRunning = false
                            val previous = activePlayGeneration++
                            lastHandle = next.handle
                            lastQuality = next.quality
                            elapsedOffset = 0
                            startedAt = System.currentTimeMillis()
                            mpvEpoch++
                            publishState(EngineState(status = if (active.crossingFailed) EngineStatus.FAILED else if (active.endedAfterCrossing) EngineStatus.ENDED else
                                if (userPaused) EngineStatus.PAUSED else EngineStatus.PLAYING,
                                error = if (active.crossingFailed) "Local file playback failed" else null,
                                durationMs = active.nextDuration, seamlessItemId = next.itemId, previousGeneration = previous))
                            active.endedAfterCrossing = false
                            active.crossingFailed = false
                        }
                    }
                }
                "end-file" -> {
                    val reason = event["reason"]?.jsonPrimitive?.content
                    val id = event["playlist_entry_id"]?.jsonPrimitive?.content?.toLongOrNull()
                    if (id != null && id != active.entryId && id != active.crossingEntryId && id !in active.entries) return
                    if (reason != "eof" && reason != "error") return
                    if (active.crossing != null && (id == null || id == active.crossingEntryId)) {
                        active.endedAfterCrossing = true
                        active.crossingFailed = reason == "error"
                        return
                    }
                    if (reason == "eof" && preparedNext != null && (id == null || id == active.entryId)) {
                        active.awaitingSuccessor = true
                        return
                    }
                    if (reason == "error" && active.awaitingSuccessor && (id == null || id != active.entryId)) {
                        active.awaitingSuccessor = false
                        preparedNext = null
                        val generation = activePlayGeneration
                        // The outgoing decoder reached EOF but its tail may still be in the AO.
                        // A broken successor must not cause session teardown to discard that tail.
                        scope.launch {
                            var remaining = (_state.value.durationMs?.minus(_state.value.positionMs) ?: 0).coerceAtLeast(0)
                            var last = System.nanoTime()
                            while (remaining > 0) {
                                delay(10)
                                val now = System.nanoTime()
                                val valid = synchronized(lifecycleLock) {
                                    if (!userPaused) remaining -= (now - last) / 1_000_000
                                    activeProcess === active && activePlayGeneration == generation
                                }
                                if (!valid) return@launch
                                last = now
                            }
                            synchronized(lifecycleLock) {
                                if (activeProcess === active && activePlayGeneration == generation) {
                                    cancelTickerLocked()
                                    publishState(_state.value.copy(status = EngineStatus.ENDED))
                                }
                            }
                        }
                        return
                    }
                    // Initial-file failures use the existing bounded session fallback.
                    preparedNext = null
                    cancelTickerLocked()
                    publishState(_state.value.copy(status = if (reason == "error")
                        EngineStatus.FAILED else EngineStatus.ENDED, error = if (reason == "error")
                        "Local file playback failed" else null))
                }
            }
        }
    }

    private fun watchProcess(active: ActiveProcess, durationMs: Long?) {
        scope.launch(Dispatchers.IO) {
            val code = runCatching { active.process.waitFor() }.getOrNull()
            runtime.deleteIpcPath(active.socket)
            synchronized(lifecycleLock) {
                if (activeProcess !== active) return@synchronized
                activeProcess = null
                if (active.gapless) preparedNext = null
                if (userPaused) return@synchronized
                val position = _state.value.positionMs
                val truncated = code == 0 && mpvEndedEarly(position, if (active.gapless) _state.value.durationMs else durationMs)
                publishState(if (code == 0 && !truncated) {
                    _state.value.copy(
                        status = EngineStatus.ENDED,
                        positionMs = durationMs ?: _state.value.positionMs,
                    )
                } else {
                    _state.value.copy(
                        status = EngineStatus.FAILED,
                        positionMs = position,
                        retryAtPositionMs = position.takeIf { truncated || active.gapless },
                        error = if (truncated) "Stream ended before the track finished" else
                            code?.let { "Player exited with $it" } ?: "Player process failed",
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
                    if (sample != null && active?.crossing == null) {
                        if (sample.eof && !active.gapless) {
                            cancelTickerLocked()
                            val current = _state.value
                            val truncated = mpvEndedEarly(current.positionMs, current.durationMs)
                            publishState(current.copy(
                                status = if (truncated) EngineStatus.FAILED else EngineStatus.ENDED,
                                positionMs = if (truncated) current.positionMs else current.durationMs ?: current.positionMs,
                                retryAtPositionMs = current.positionMs.takeIf { truncated },
                                error = if (truncated) "Stream ended before the track finished" else null,
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
                    if (duration != null && position >= duration && active?.gapless != true && !receiverOwnsPlaybackLocked()) {
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

    private fun receiverOwnsPlaybackLocked(): Boolean =
        spotifyReceiverConfirmed && spotify.receiverEvents?.isAvailable == true

    private fun cancelSpotifyReceiverLocked() {
        spotifyReceiver?.cancel()
        spotifyReceiver = null
        spotifyReceiverEndTimeout?.cancel()
        spotifyReceiverEndTimeout = null
        spotifyReceiverConfirmed = false
        spotifyReceiverEndPending = false
        spotifyReceiverStopped = false
    }

    private fun startSpotifyReceiverLocked(token: Long) {
        val receiver = spotify.receiverEvents ?: return
        // Subscribe before sending play: loading and playing can arrive before the HTTP reply.
        spotifyReceiver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            receiver.events.collect { event ->
                synchronized(lifecycleLock) {
                    if (token != requestToken || !spotifyIsActive() || !receiver.isAvailable) return@synchronized
                    val handle = lastHandle as PlaybackHandle.ProviderPlayback
                    if (event.trackId != handle.trackId.removePrefix("spotify:track:")) {
                        // Connect gives the receiver a context of its own, so it can roll past
                        // EndOfTrack into a track that was never in the Kainos queue.
                        if (spotifyReceiverEndPending && event.kind == SpotifyReceiverEvent.Kind.PLAYING) {
                            PlaybackTrace.log("Librespot", "receiver rolled over to ${event.trackId}")
                            silenceOverrunReceiverLocked()
                        }
                        return@synchronized
                    }
                    val current = _state.value
                    if (current.status == EngineStatus.ENDED || current.status == EngineStatus.FAILED) return@synchronized
                    PlaybackTrace.log("Librespot", "receiver ${event.kind} track=${event.trackId} pos=${event.positionMs}")
                    if (event.kind == SpotifyReceiverEvent.Kind.PLAYING) spotifyReceiverConfirmed = true
                    if (!spotifyReceiverConfirmed && event.kind != SpotifyReceiverEvent.Kind.UNAVAILABLE) return@synchronized
                    // A Web API request already in flight must not overwrite this receiver event.
                    noteLocalTransportLocked()
                    when (event.kind) {
                        SpotifyReceiverEvent.Kind.END_OF_TRACK -> {
                            if (spotifyReceiverEndPending) return@synchronized
                            spotifyReceiverEndPending = true
                            spotifyReceiverStopped = false
                            cancelTickerLocked()
                            // SPIRC handles EndOfTrack independently of --onevent. Its following
                            // Stopped event confirms handle_stop's queue/position reset has run.
                            // Bound the wait in case the event helper or receiver dies in between.
                            spotifyReceiverEndTimeout = scope.launch {
                                delay(2_000)
                                synchronized(lifecycleLock) {
                                    if (token == requestToken && spotifyReceiverEndPending && !spotifyReceiverStopped) {
                                        silenceOverrunReceiverLocked("Receiver stop acknowledgement timed out")
                                    }
                                }
                            }
                        }
                        SpotifyReceiverEvent.Kind.STOPPED -> {
                            spotifyReceiverStopped = true
                            if (spotifyReceiverEndPending) {
                                if (!userPaused) finishSpotifyReceiverTrackLocked()
                            } else if (!userPaused) {
                                cancelTickerLocked()
                                publishState(current.copy(status = EngineStatus.PAUSED))
                            }
                        }
                        SpotifyReceiverEvent.Kind.UNAVAILABLE -> {
                            cancelTickerLocked()
                            publishState(current.copy(status = EngineStatus.FAILED, error = "Spotify track is unavailable"))
                        }
                        else -> {
                            // A rewind/pause after EndOfTrack belongs to the receiver's reset.
                            if (spotifyReceiverEndPending) return@synchronized
                            val playing = when (event.kind) {
                                SpotifyReceiverEvent.Kind.PLAYING -> true
                                SpotifyReceiverEvent.Kind.PAUSED -> false
                                else -> current.status == EngineStatus.PLAYING
                            }
                            val decision = reconcileSpotifyObservation(
                                status = current.status,
                                positionMs = current.positionMs,
                                // Only EndOfTrack ends a local track; a user pause near its end is a pause.
                                durationMs = null,
                                expectedTrackId = handle.trackId,
                                userPaused = userPaused,
                                misses = 0,
                                observed = SpotifyObservedPlayback(
                                    playing, event.positionMs, handle.trackId,
                                ),
                                pauseAcknowledged = spotifyPauseAcknowledged,
                                pauseGuardOpen = spotifyPauseGuardOpen(
                                    spotifyPauseCommandCompleted, spotifyObservationsSincePause,
                                ),
                            )
                            spotifyPauseAcknowledged = decision.pauseAcknowledged
                            if (decision.clearUserPause) {
                                userPaused = false
                                clearPauseGuardLocked()
                            } else if (userPaused) spotifyObservationsSincePause++
                            val eventPosition = event.positionMs
                            if (decision.snapClock || eventPosition != null && !userPaused) {
                                elapsedOffset = if (decision.snapClock) decision.positionMs else eventPosition ?: elapsedOffset
                                startedAt = System.currentTimeMillis()
                            }
                            publishState(current.copy(
                                status = decision.status,
                                positionMs = elapsedOffset,
                                durationMs = handle.durationMs,
                                syncWarning = null,
                            ))
                            if (decision.status == EngineStatus.PLAYING) startTickerLocked() else cancelTickerLocked()
                        }
                    }
                }
            }
        }
    }

    /**
     * The receiver did not stop at the end of its track, and the transition skips its pause once
     * this track is ENDED, so anything played next would otherwise sound on top of it.
     */
    private fun silenceOverrunReceiverLocked(warning: String? = null) {
        enqueueSpotifyCommand("Spotify Connect pause", action = {
            runCatching { spotify.pause() }.onFailure { failure ->
                PlaybackTrace.log("Librespot", "pause after end of track failed: ${failure.message}")
            }
        })
        spotifyReceiverStopped = true
        if (!userPaused) finishSpotifyReceiverTrackLocked(warning)
    }

    private fun finishSpotifyReceiverTrackLocked(warning: String? = null) {
        spotifyReceiverEndPending = false
        spotifyReceiverEndTimeout?.cancel()
        spotifyReceiverEndTimeout = null
        cancelTickerLocked()
        val current = _state.value
        publishState(current.copy(
            status = EngineStatus.ENDED,
            positionMs = current.durationMs ?: current.positionMs,
            syncWarning = warning,
        ))
    }

    private fun startSpotifyReconcileLocked(token: Long) {
        cancelSpotifyReconcileLocked()
        val observe = spotify.observe ?: return
        spotifyReconcile = scope.launch {
            while (true) {
                delay(spotifyObserveIntervalMs)
                val snapshot = synchronized(lifecycleLock) {
                    if (token != requestToken || !spotifyIsActive()) return@launch
                    if (_state.value.status == EngineStatus.ENDED || _state.value.status == EngineStatus.FAILED) return@launch
                    if (receiverOwnsPlaybackLocked()) return@synchronized null
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
                } ?: continue
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
                    if (receiverOwnsPlaybackLocked() ||
                        _state.value.status == EngineStatus.ENDED ||
                        !spotifyObservationStillValid(capturedGate, currentGate, spotifyIsActive())) {
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
        PlaybackTrace.log("Engine", "$name warning: ${failure.message}")
        if (name.endsWith("stop")) return
        synchronized(lifecycleLock) {
            if (spotifyIsActive()) publishState(_state.value.copy(syncWarning = "$name failed; waiting for receiver state"))
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
        preparedNext = null
        runCatching { active.events?.close() }
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
            cancelSpotifyReceiverLocked()
            stopProcessLocked()
        }
    }

    private class ActiveProcess(
        val process: Process,
        val socket: Path,
        val gapless: Boolean = false,
        var events: MpvEventStream? = null,
        var loaded: Boolean = false,
        var entryId: Long? = null,
        var decodingEntryId: Long? = null,
        var crossingEntryId: Long? = null,
        val entries: MutableMap<Long, Pair<PreparedPlayback, Long?>> = mutableMapOf(),
        var nextDuration: Long? = null,
        var crossing: PreparedPlayback? = null,
        var crossingRunning: Boolean = false,
        var outputDrained: Boolean = false,
        var endedAfterCrossing: Boolean = false,
        var crossingFailed: Boolean = false,
        var awaitingSuccessor: Boolean = false,
    )

    private data class Restart(
        val handle: PlaybackHandle,
        val quality: AudioQuality?,
        val startSeconds: Double,
        val token: Long,
        val targetStatus: EngineStatus,
    )
}

internal interface MpvEventStream : AutoCloseable {
    fun readLine(): String?
}

internal interface DesktopPlaybackRuntime {
    fun loadEntry(command: String, socket: Path): Long? {
        if (!sendIpc(command, socket)) return null
        return runCatching {
            val playlist = Json.parseToJsonElement(readProperty("playlist", socket) ?: return null)
                as kotlinx.serialization.json.JsonArray
            playlist.last().jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull()
        }.getOrNull()
    }
    fun canPrepare(current: String, next: String): Boolean = true
    val supportsEvents: Boolean get() = false
    fun openEvents(socket: Path): MpvEventStream? = null
    fun findOnPath(name: String): String?
    fun probeDurationMs(url: String): Long?
    fun createIpcPath(): Path
    fun startProcess(command: List<String>): Process
    fun waitForIpc(socket: Path)
    fun sendIpc(commandArrayJson: String, socket: Path): Boolean
    fun readProperty(name: String, socket: Path): String? = null
    fun deleteIpcPath(path: Path)
}

internal object SystemDesktopPlaybackRuntime : DesktopPlaybackRuntime {
    override val supportsEvents = true
    override fun canPrepare(current: String, next: String): Boolean {
        fun format(url: String): String? {
            val path = mediaPathForProbe(url) ?: return null
            if (!Files.isRegularFile(Path.of(path)) || !Files.isReadable(Path.of(path))) return null
            val header = Files.newInputStream(Path.of(path)).use { it.readNBytes(42) }
            if (header.size == 42 && header.copyOfRange(0, 4).decodeToString() == "fLaC") {
                var packed = 0L
                for (i in 18..25) packed = (packed shl 8) or (header[i].toLong() and 0xff)
                return (packed ushr 36).toString() // sample rate, channels and bit depth, freshly read
            }
            val probe = findOnPath("ffprobe") ?: return null
            val process = ProcessBuilder(probe, "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=sample_rate,channels,sample_fmt", "-of", "csv=p=0", path)
                .redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return null }
            return process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 && it.isNotEmpty() }
        }
        return runCatching { val first = format(current); first != null && first == format(next) }.getOrDefault(false)
    }
    override fun openEvents(socket: Path): MpvEventStream {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(socket))
        val reader = java.nio.channels.Channels.newInputStream(channel).bufferedReader()
        return object : MpvEventStream {
            override fun readLine(): String? = reader.readLine()
            override fun close() { channel.close() }
        }
    }

    override fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(':').firstOrNull { dir ->
            java.io.File(dir, name).canExecute()
        }?.let { java.io.File(it, name).absolutePath }
    }

    override fun probeDurationMs(url: String): Long? {
        val media = mediaPathForProbe(url) ?: url
        if (url.startsWith("file:") || !url.contains("://")) {
            readLocalFlacDurationMs(media)?.let { return it }
        }
        val ffprobe = findOnPath("ffprobe") ?: return null
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

    /** Read fresh STREAMINFO duration directly, avoiding a subprocess for local FLACs.
     * RFC 9639 section 8.2: https://www.rfc-editor.org/rfc/rfc9639.html#section-8.2
     */
    private fun readLocalFlacDurationMs(media: String): Long? = runCatching {
        val path = Path.of(media)
        if (!Files.isRegularFile(path)) return null
        java.io.RandomAccessFile(path.toFile(), "r").use { file ->
            if (file.length() < 44) return null
            val header = ByteArray(42)
            file.readFully(header)
            if (header.copyOfRange(0, 4).decodeToString() != "fLaC") return null
            if ((header[4].toInt() and 0x7f) != 0 ||
                header[5] != 0.toByte() || header[6] != 0.toByte() || header[7] != 34.toByte()
            ) return null
            fun unsigned16(offset: Int) =
                ((header[offset].toInt() and 0xff) shl 8) or (header[offset + 1].toInt() and 0xff)
            val minBlock = unsigned16(8)
            val maxBlock = unsigned16(10)
            var packed = 0L
            for (index in 18..25) packed = (packed shl 8) or (header[index].toLong() and 0xff)
            val sampleRate = packed ushr 44
            val channels = ((packed ushr 41) and 7) + 1
            val bitsPerSample = ((packed ushr 36) and 31) + 1
            val samples = packed and ((1L shl 36) - 1)
            if (sampleRate !in 1L..655350L || channels !in 1L..8L ||
                bitsPerSample !in 4L..32L || minBlock < 16 || minBlock > maxBlock || samples == 0L
            ) return null
            var last = header[4].toInt() and 0x80 != 0
            while (!last) {
                // Seek over payloads (including large pictures); read only block headers.
                if (file.filePointer + 4 > file.length()) return null
                val type = file.readUnsignedByte()
                if ((type and 0x7f) !in 1..6) return null
                last = type and 0x80 != 0
                val length = (file.readUnsignedByte() shl 16) or
                    (file.readUnsignedByte() shl 8) or file.readUnsignedByte()
                val next = file.filePointer + length
                if (next + 2 > file.length()) return null
                file.seek(next)
            }
            if (file.read() != 0xff || file.read() !in 0xf8..0xf9) return null
            ((samples * 1_000) / sampleRate).takeIf { it > 0 }
        }
    }.getOrNull()

    private fun mediaPathForProbe(url: String): String? = runCatching {
        when {
            url.startsWith("file:") -> java.nio.file.Paths.get(java.net.URI(url)).toString()
            else -> url
        }
    }.getOrNull()

    override fun createIpcPath(): Path =
        Files.createTempFile("kainos-mpv-", ".sock").also { Files.deleteIfExists(it) }

    override fun startProcess(command: List<String>): Process =
        // The JVM owns stdin. mpv exits on EOF, including after SIGKILL of the JVM.
        ProcessBuilder(command + "--input-ipc-client=fd://0").redirectErrorStream(true).start()

    override fun waitForIpc(socket: Path) {
        val deadline = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(socket) && sendIpc("""["get_property","pause"]""", socket)) return
            Thread.sleep(40)
        }
    }

    override fun sendIpc(commandArrayJson: String, socket: Path): Boolean =
        // Drain one response line so the socket stays healthy; a timeout counts as a failed IPC.
        when (val reply = exchange(socket, """{"command":$commandArrayJson}""")) {
            is IpcReply.Line -> mpvCommandSucceeded(reply.text)
            else -> false
        }

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
        return runCatching {
            val value = Json.parseToJsonElement(line).jsonObject["data"] ?: return null
            if (value is kotlinx.serialization.json.JsonPrimitive) value.content else value.toString()
        }.getOrNull()
    }

    override fun deleteIpcPath(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }
}

internal fun mpvCommandSucceeded(reply: String): Boolean = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(reply).jsonObject["error"]?.jsonPrimitive?.content == "success"
}.getOrDefault(false)

internal fun mpvEndedEarly(positionMs: Long, durationMs: Long?): Boolean =
    durationMs != null && durationMs - positionMs > maxOf(5_000L, durationMs / 50)
