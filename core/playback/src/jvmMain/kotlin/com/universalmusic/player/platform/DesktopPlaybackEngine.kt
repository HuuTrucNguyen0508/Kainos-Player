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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
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
    private val transport = DesktopTransport()
    private val slot = MpvSlot(runtime, transport.lock, scope)
    override val state: StateFlow<EngineState> = transport.state
    override val supportsGapless: Boolean get() = runtime.supportsEvents
    private var gaplessEnabled = false
    private var preparedNext: PreparedPlayback? = null

    override fun configureGapless(enabled: Boolean) {
        synchronized(transport.lock) { gaplessEnabled = enabled }
        if (!enabled) prepareNext(null)
    }

    override fun prepareNext(next: PreparedPlayback?) {
        synchronized(transport.lock) {
            val candidate = next?.takeIf { gaplessEnabled && it.generation == transport.activeGeneration && localFile(it.handle.url) }
            preparedNext = candidate
            val active = slot.active ?: return
            if (!active.gapless) return
            if (active.crossing != null && candidate !== active.crossing) {
                val handle = transport.handle as? PlaybackHandle.Url ?: return
                elapsedOffset = transport.current.positionMs
                seekRestartLocked(handle, transport.current)?.let(::launchRestart)
                return
            }
            preparedNext = candidate
            slot.enqueueLocked {
                if (!slot.isActive(active)) return@enqueueLocked
                // Remove the old successor before probing a potentially incompatible replacement.
                runtime.sendIpc("""["playlist-clear"]""", active.socket)
                val currentUrl = synchronized(transport.lock) { (transport.handle as? PlaybackHandle.Url)?.url }
                val compatible = candidate == null || (currentUrl != null && runtime.canPrepare(currentUrl, candidate.handle.url))
                if (!compatible) {
                    synchronized(transport.lock) { if (preparedNext === candidate) preparedNext = null }
                    runtime.sendIpc("""["set_property","gapless-audio","no"]""", active.socket)
                    synchronized(transport.lock) {
                        if (slot.active === active && active.awaitingSuccessor) {
                            active.awaitingSuccessor = false
                            transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                        }
                    }
                    return@enqueueLocked
                }
                runtime.sendIpc("""["set_property","gapless-audio","${if (candidate == null) "no" else "yes"}"]""", active.socket)
                if (candidate == null) synchronized(transport.lock) {
                    if (slot.active === active && active.awaitingSuccessor) {
                        active.awaitingSuccessor = false
                        transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                    }
                }
                if (candidate != null) {
                    val duration = runtime.probeDurationMs(candidate.handle.url)
                    synchronized(transport.lock) {
                        if (preparedNext !== candidate || slot.active !== active) return@enqueueLocked
                        active.nextDuration = duration
                    }
                    val id = runtime.loadEntry("""["loadfile",${Json.encodeToString(candidate.handle.url)},"append-play"]""", active.socket)
                    synchronized(transport.lock) {
                        if (id != null) active.entries[id] = candidate to duration
                        else if (preparedNext === candidate) preparedNext = null
                    }
                }
            }
        }
    }

    private fun localFile(url: String): Boolean = url.startsWith("file:") || !url.contains("://")


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
    private var seekSequence = 0L
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
                    synchronized(transport.lock) {
                        if (transport.userPaused && spotifyPauseCommandId == pauseCommandId) {
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
    }

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        try {
            playInternal(handle, quality, playGeneration)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (transport.activeGeneration == playGeneration) {
                transport.publish(transport.current.copy(status = EngineStatus.FAILED, error = failure.message))
            }
            throw failure
        }
    }

    private suspend fun playInternal(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        val token = synchronized(transport.lock) {
            val retry = transport.current.status == EngineStatus.FAILED && transport.current.retryAtPositionMs != null &&
                (transport.handle as? PlaybackHandle.Url)?.url == (handle as? PlaybackHandle.Url)?.url
            preparedNext = if (retry) preparedNext?.copy(generation = playGeneration) else null
            transport.activeGeneration = playGeneration
            transport.invalidatePendingStartLocked()
            cancelTickerLocked()
            cancelSpotifyReceiverLocked()
            if (!slot.stopLocked()) {
                transport.publish(EngineState(
                    status = EngineStatus.FAILED,
                    error = "Previous player did not terminate",
                ))
                error("Previous player did not terminate")
            }
            transport.handle = handle
            transport.quality = quality
            transport.userPaused = false
            spotifyPauseAcknowledged = false
            clearPauseGuardLocked()
            noteLocalTransportLocked()
            slot.epoch++
            elapsedOffset = (handle as? PlaybackHandle.Url)?.startPositionMs ?: 0
            transport.publish(EngineState(status = EngineStatus.BUFFERING))
            transport.requestToken
        }
        when (handle) {
            is PlaybackHandle.Url -> startUrl(handle.url, startSeconds = handle.startPositionMs / 1000.0, token = token)
            is PlaybackHandle.ProviderPlayback -> {
                if (handle.provider != ProviderId.SPOTIFY) {
                    throw UnsupportedPlaybackException(
                        "${handle.provider.displayName} does not provide a supported Linux playback mechanism.",
                    )
                }
                synchronized(transport.lock) {
                    if (token != transport.requestToken) return
                    startSpotifyReceiverLocked(token)
                }
                runSpotifyCommand("Spotify Connect playback") { spotify.play(handle.trackId) }
                synchronized(transport.lock) {
                    if (token != transport.requestToken) return
                    if (transport.current.status == EngineStatus.BUFFERING) {
                        startedAt = System.currentTimeMillis()
                        transport.publish(EngineState(
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
        if (transport.spotifyIsActive()) {
            val pauseCommandId = synchronized(transport.lock) {
                if (transport.current.status != EngineStatus.PLAYING &&
                    transport.current.status != EngineStatus.BUFFERING
                ) return
                elapsedOffset = transport.current.positionMs
                cancelTickerLocked()
                transport.userPaused = true
                spotifyPauseAcknowledged = false
                clearPauseGuardLocked()
                noteLocalTransportLocked()
                transport.publish(transport.current.copy(status = EngineStatus.PAUSED, error = null))
                if (spotifyReceiverEndPending) return
                ++spotifyPauseCommandId
            }
            enqueueSpotifyCommand("Spotify Connect pause", spotify.pause, pauseCommandId)
            return
        }
        synchronized(transport.lock) {
            if (transport.current.status != EngineStatus.PLAYING &&
                transport.current.status != EngineStatus.BUFFERING
            ) {
                return
            }
            transport.invalidatePendingStartLocked()
            elapsedOffset = transport.current.positionMs
            cancelTickerLocked()
            transport.userPaused = true
            slot.epoch++
            val active = slot.active
            // Publish PAUSED now so the caller (UI / MPRIS) never waits on mpv; the IPC runs on the
            // FIFO worker and falls back to killing the process if mpv does not acknowledge.
            transport.publish(transport.current.copy(status = EngineStatus.PAUSED, error = null))
            if (active != null) {
                slot.enqueueLocked {
                    if (slot.sendIfActive(active, """["set_property","pause",true]""")) return@enqueueLocked
                    synchronized(transport.lock) {
                        // Only act if this process is still ours and the user still wants it paused.
                        if (slot.active !== active || !transport.userPaused) return@synchronized
                        if (!slot.stopLocked()) {
                            transport.publish(transport.current.copy(
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
        if (transport.spotifyIsActive()) {
            synchronized(transport.lock) {
                if (transport.current.status != EngineStatus.PAUSED) return
                transport.userPaused = false
                spotifyPauseAcknowledged = false
                clearPauseGuardLocked()
                noteLocalTransportLocked()
                if (spotifyReceiverEndPending) {
                    if (spotifyReceiverStopped) finishSpotifyReceiverTrackLocked()
                    else transport.publish(transport.current.copy(status = EngineStatus.PLAYING))
                    return
                }
                startedAt = System.currentTimeMillis()
                transport.publish(transport.current.copy(status = EngineStatus.PLAYING, error = null))
                startTickerLocked()
            }
            enqueueSpotifyCommand("Spotify Connect resume", spotify.resume)
            return
        }
        val restart = synchronized(transport.lock) {
            val current = transport.current
            if (current.status != EngineStatus.PAUSED) return
            transport.userPaused = false
            val epoch = ++slot.epoch
            val active = slot.active
            if (active?.process?.isAlive == true) {
                // Optimistically resume so a quick pause() after resume() is honoured; if mpv does
                // not acknowledge, the worker falls back to the restart path below.
                startedAt = System.currentTimeMillis()
                transport.publish(current.copy(status = EngineStatus.PLAYING, error = null))
                startTickerLocked()
                slot.enqueueLocked {
                    if (slot.sendIfActive(active, """["set_property","pause",false]""")) return@enqueueLocked
                    val fallback = synchronized(transport.lock) {
                        if (slot.epoch != epoch || slot.active !== active) return@enqueueLocked
                        cancelTickerLocked()
                        resumeRestartLocked(transport.current)
                    } ?: return@enqueueLocked
                    launchRestart(fallback)
                }
                return
            }
            resumeRestartLocked(current) ?: return
        }
        launchRestart(restart)
    }

    private fun resumeRestartLocked(current: EngineState): Restart? {
        if (!slot.stopLocked()) {
            transport.publish(current.copy(
                status = EngineStatus.FAILED,
                error = "Player did not terminate before resume",
            ))
            return null
        }
        val handle = transport.handle ?: return null
        transport.invalidatePendingStartLocked()
        transport.publish(current.copy(status = EngineStatus.BUFFERING, error = null))
        return Restart(handle, transport.quality, elapsedOffset / 1000.0, transport.requestToken, EngineStatus.PLAYING)
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
        if (transport.spotifyIsActive()) {
            val target = positionMs.coerceAtLeast(0)
            synchronized(transport.lock) {
                elapsedOffset = target
                startedAt = System.currentTimeMillis()
                noteLocalTransportLocked()
                transport.publish(transport.current.copy(positionMs = target, error = null))
            }
            enqueueSpotifyCommand("Spotify Connect seek", action = { spotify.seekTo(target) })
            return
        }
        val restart = synchronized(transport.lock) {
            elapsedOffset = positionMs.coerceAtLeast(0)
            startedAt = System.currentTimeMillis()
            val current = transport.current
            transport.publish(current.copy(positionMs = elapsedOffset))
            val handle = transport.handle
            if (handle !is PlaybackHandle.Url) return
            slot.epoch++
            val seekId = ++seekSequence
            val active = slot.active
            if (active?.crossing != null) preparedNext = active.crossing
            if (active?.process?.isAlive == true) {
                val seconds = elapsedOffset / 1000.0
                slot.enqueueLocked {
                    if (synchronized(transport.lock) { seekSequence != seekId || transport.handle !== handle || slot.active !== active }) return@enqueueLocked
                    val ok = if (active.gapless) seekGapless(active, handle, seconds) else
                        slot.sendIfActive(active, """["set_property","time-pos",$seconds]""")
                    val fallback = synchronized(transport.lock) {
                        // A newer transport command (or track) owns the state now.
                        if (seekSequence != seekId || transport.handle !== handle || slot.active !== active) return@enqueueLocked
                        if (ok) {
                            if (transport.current.status == EngineStatus.PLAYING) {
                                startedAt = System.currentTimeMillis()
                            }
                            return@enqueueLocked
                        }
                        seekRestartLocked(handle, transport.current)
                    } ?: return@enqueueLocked
                    launchRestart(fallback)
                }
                return
            }
            seekRestartLocked(handle, current) ?: return
        }
        launchRestart(restart)
    }

    /** Runs in the IPC FIFO. With no successor, EOF can only enter idle, never another file. */
    private fun seekGapless(active: ActiveProcess, handle: PlaybackHandle.Url, seconds: Double): Boolean {
        if (!slot.isActive(active)) return false
        val playlist = runCatching {
            Json.parseToJsonElement(runtime.readProperty("playlist", active.socket) ?: return false)
                as kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return false
        // mpv accepts playlist indices, not IDs. Resolve the bound IDs from this snapshot and
        // remove backwards so earlier indices remain stable. Automatic advance retains indices.
        var index = playlist.size - 1
        while (index >= 0) {
            val id = playlist[index].jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull()
            val successor = synchronized(transport.lock) { id in active.entries }
            if (successor && !runtime.sendIpc("""["playlist-remove",$index]""", active.socket)) return false
            index--
        }
        val playing = runCatching {
            (Json.parseToJsonElement(runtime.readProperty("playlist", active.socket) ?: return false)
                as kotlinx.serialization.json.JsonArray).firstOrNull {
                it.jsonObject["playing"]?.jsonPrimitive?.content == "true"
            }?.jsonObject?.get("id")?.jsonPrimitive?.content?.toLongOrNull()
        }.getOrNull()
        val ownsFile = synchronized(transport.lock) {
            slot.active === active && transport.handle === handle && active.crossing == null &&
                playing != null && playing == active.entryId
        }
        if (!ownsFile || !runtime.sendIpc("""["set_property","time-pos",$seconds]""", active.socket)) return false
        synchronized(transport.lock) {
            if (slot.active !== active || transport.handle !== handle) return true
            active.entries.clear()
            active.awaitingSuccessor = false
            preparedNext?.let { prepareNext(it) }
        }
        return true
    }

    private fun seekRestartLocked(handle: PlaybackHandle.Url, current: EngineState): Restart? {
        if (current.status != EngineStatus.PLAYING && current.status != EngineStatus.PAUSED) return null
        if (!slot.stopLocked()) {
            transport.publish(current.copy(
                status = EngineStatus.FAILED,
                error = "Player did not terminate before seek",
            ))
            return null
        }
        transport.invalidatePendingStartLocked()
        val targetStatus = current.status
        transport.userPaused = targetStatus == EngineStatus.PAUSED
        transport.publish(transport.current.copy(status = EngineStatus.BUFFERING))
        return Restart(handle, transport.quality, elapsedOffset / 1000.0, transport.requestToken, targetStatus)
    }

    override fun stopForTransition() {
        synchronized(transport.lock) {
            // The receiver has already stopped after its end-of-queue reset. A Web API pause
            // here races the following play and can leave Connect reporting paused at zero.
            if (spotifyReceiverConfirmed && transport.current.status == EngineStatus.ENDED) return
        }
        stop()
    }

    override fun stop() {
        val pauseSpotify = synchronized(transport.lock) {
            transport.spotifyIsActive() && !(spotifyReceiverConfirmed && transport.current.status == EngineStatus.ENDED)
        }
        synchronized(transport.lock) {
            transport.invalidatePendingStartLocked()
            transport.userPaused = false
            spotifyPauseAcknowledged = false
            clearPauseGuardLocked()
            noteLocalTransportLocked()
            slot.epoch++
            cancelTickerLocked()
            cancelSpotifyReconcileLocked()
            cancelSpotifyReceiverLocked()
            if (!slot.stopLocked()) {
                transport.publish(transport.current.copy(
                    status = EngineStatus.FAILED,
                    error = "Player did not terminate",
                ))
                return
            }
            preparedNext = null
            elapsedOffset = 0
            transport.handle = null
            transport.quality = null
            transport.publish(EngineState())
        }
        if (pauseSpotify) enqueueSpotifyCommand("Spotify Connect stop", spotify.pause)
    }

    private var volumeJob: Job? = null
    override fun setVolume(volume: Float) {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(250)
            if (transport.spotifyIsActive()) {
                runCatching { spotify.setVolume(volume.coerceIn(0f, 1f)) }
                    .logged("Engine", "Spotify volume update failed")
            }
        }

        val percent = (volume.coerceIn(0f, 1f) * 100).toInt()
        volumePercent = percent
        synchronized(transport.lock) {
            val active = slot.active ?: return
            slot.enqueueLocked { slot.sendIfActive(active, """["set_property","volume",$percent]""") }
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
        val suppliedDuration = synchronized(transport.lock) {
            if (token != transport.requestToken) return
            (transport.handle as? PlaybackHandle.Url)?.takeIf { it.url == url }?.durationMs
        }
        val durationMs = withContext(Dispatchers.IO) { runtime.probeDurationMs(url) } ?: suppliedDuration
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val active = synchronized(transport.lock) {
            if (token != transport.requestToken) return
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
            ActiveProcess(process, socket, seamless).also { slot.active = it }
        }
        watchProcess(active, durationMs)
        // Once mpv is launched, finish publishing its state even if the caller is cancelled
        // (matches the previous blocking behaviour); socket I/O never runs under the lock.
        val pauseAcknowledged = withContext(NonCancellable + Dispatchers.IO) {
            runtime.waitForIpc(active.socket)
            val needsIpc = synchronized(transport.lock) {
                if (token != transport.requestToken || slot.active !== active) return@withContext false
                if (targetStatus == EngineStatus.PAUSED) transport.userPaused = true
                startSeconds > 0 || targetStatus == EngineStatus.PAUSED
            }
            if (active.gapless) {
                slot.sendIfActive(active, """["set_property","volume",$volumePercent]""")
                if (targetStatus == EngineStatus.PAUSED) {
                    check(slot.sendIfActive(active, """["set_property","pause",true]"""))
                }
                val events = runtime.openEvents(active.socket) ?: error("mpv event connection unavailable")
                synchronized(transport.lock) {
                    if (slot.active !== active) { events.close(); return@withContext false }
                    active.events = events
                }
                scope.launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            val line = events.readLine() ?: break
                            synchronized(transport.lock) { slot.enqueueLocked { handleMpvEvent(active, line) } }
                        }
                    } catch (_: Exception) {
                        // Closing the channel during stop wakes this blocking reader.
                    } finally {
                        synchronized(transport.lock) {
                            if (slot.active === active && active.process.isAlive) {
                                transport.publish(transport.current.copy(status = EngineStatus.FAILED,
                                    retryAtPositionMs = transport.current.positionMs,
                                    error = "Player event connection lost"))
                            }
                        }
                    }
                }
                check(slot.awaitIpc {
                    val id = runtime.loadEntry("""["loadfile",${Json.encodeToString(url)},"replace",-1,{"start":"$startSeconds"}]""", active.socket)
                    synchronized(transport.lock) { active.entryId = id }
                    id != null
                })
            }
            if (!needsIpc) return@withContext true
            slot.awaitIpc {
                targetStatus != EngineStatus.PAUSED ||
                    slot.sendIfActive(active, """["set_property","pause",true]""")
            }
        }
        synchronized(transport.lock) {
            if (token != transport.requestToken || slot.active !== active) return
            if (targetStatus == EngineStatus.PAUSED) {
                transport.userPaused = true
                if (!pauseAcknowledged) {
                    if (!slot.stopLocked()) {
                        transport.publish(EngineState(
                            status = EngineStatus.FAILED,
                            positionMs = elapsedOffset,
                            durationMs = durationMs,
                            error = "Player could not be paused or stopped",
                        ))
                        return
                    }
                }
            } else {
                transport.userPaused = false
            }
            startedAt = System.currentTimeMillis()
            transport.publish(EngineState(
                status = targetStatus,
                positionMs = elapsedOffset,
                durationMs = durationMs,
            ))
            if (targetStatus == EngineStatus.PLAYING) startTickerLocked()
            preparedNext?.takeIf { gaplessEnabled && it.generation == transport.activeGeneration }?.let(::prepareNext)
        }
    }

    private fun handleMpvEvent(active: ActiveProcess, line: String) {
        val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        synchronized(transport.lock) {
            if (slot.active !== active) return
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
                        slot.stopLocked()
                        transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                        return
                    }
                    if (next.generation != transport.activeGeneration) return
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
                            val pts = slot.awaitIpc { runtime.readProperty("audio-pts", active.socket)?.toDoubleOrNull() }
                            val now = System.nanoTime()
                            val crossed = synchronized(transport.lock) {
                                if (slot.active !== active || active.crossing !== next) return@launch
                                if (!transport.userPaused) unresolvedMs += (now - lastSample) / 1_000_000
                                active.outputDrained || (pts != null && pts.isFinite() && pts >= 0)
                            }
                            if (crossed) break
                            if (unresolvedMs >= 2_000) {
                                synchronized(transport.lock) {
                                    if (slot.active !== active || active.crossing !== next) return@launch
                                    // No output evidence: discard lookahead and let the session
                                    // resolve the selected successor normally, without claiming it played.
                                    slot.stopLocked()
                                    cancelTickerLocked()
                                    transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                                }
                                return@launch
                            }
                            lastSample = now
                            delay(10)
                        }
                        synchronized(transport.lock) {
                            if (slot.active !== active || active.crossing !== next || next.generation != transport.activeGeneration) return@synchronized
                            active.entryId = active.crossingEntryId
                            active.entries.remove(active.entryId)
                            active.crossing = null
                            active.crossingRunning = false
                            val previous = transport.activeGeneration++
                            transport.handle = next.handle
                            transport.quality = next.quality
                            elapsedOffset = 0
                            startedAt = System.currentTimeMillis()
                            slot.epoch++
                            transport.publish(EngineState(status = if (active.crossingFailed) EngineStatus.FAILED else if (active.endedAfterCrossing) EngineStatus.ENDED else
                                if (transport.userPaused) EngineStatus.PAUSED else EngineStatus.PLAYING,
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
                        val generation = transport.activeGeneration
                        // The outgoing decoder reached EOF but its tail may still be in the AO.
                        // A broken successor must not cause session teardown to discard that tail.
                        scope.launch {
                            var remaining = (transport.current.durationMs?.minus(transport.current.positionMs) ?: 0).coerceAtLeast(0)
                            var last = System.nanoTime()
                            while (remaining > 0) {
                                delay(10)
                                val now = System.nanoTime()
                                val valid = synchronized(transport.lock) {
                                    if (!transport.userPaused) remaining -= (now - last) / 1_000_000
                                    slot.active === active && transport.activeGeneration == generation
                                }
                                if (!valid) return@launch
                                last = now
                            }
                            synchronized(transport.lock) {
                                if (slot.active === active && transport.activeGeneration == generation) {
                                    cancelTickerLocked()
                                    transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                                }
                            }
                        }
                        return
                    }
                    // Errors on the outgoing entry must also cancel any pending boundary.
                    active.crossing = null
                    active.crossingRunning = false
                    preparedNext = null
                    cancelTickerLocked()
                    transport.publish(transport.current.copy(status = if (reason == "error")
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
            synchronized(transport.lock) {
                if (slot.active !== active) return@synchronized
                slot.active = null
                if (preparedNext == null) preparedNext = active.crossing
                if (transport.userPaused) return@synchronized
                val position = transport.current.positionMs
                val truncated = code == 0 && mpvEndedEarly(position, if (active.gapless) transport.current.durationMs else durationMs)
                transport.publish(if (code == 0 && !truncated) {
                    transport.current.copy(
                        status = EngineStatus.ENDED,
                        positionMs = durationMs ?: transport.current.positionMs,
                    )
                } else {
                    transport.current.copy(
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
                val snapshot = synchronized(transport.lock) {
                    if (token != tickerToken || transport.current.status != EngineStatus.PLAYING) {
                        return@launch
                    }
                    TickerSnapshot(slot.active, slot.epoch)
                }
                // Socket reads happen on the IPC worker, outside the lock.
                val sample = snapshot.active?.let { active -> slot.awaitIpc { readTickerSample(active) } }
                val ended = synchronized(transport.lock) {
                    if (token != tickerToken || transport.current.status != EngineStatus.PLAYING) {
                        return@launch
                    }
                    val active = snapshot.active
                    if (active != null && (slot.active !== active || slot.epoch != snapshot.epoch)) {
                        // Stale sample: the process was replaced or a seek/pause/resume happened
                        // while the read was in flight. Do not publish it.
                        return@synchronized false
                    }
                    if (sample != null && active?.crossing == null) {
                        if (sample.eof && !active.gapless) {
                            cancelTickerLocked()
                            val current = transport.current
                            val truncated = mpvEndedEarly(current.positionMs, current.durationMs)
                            transport.publish(current.copy(
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
                            transport.publish(transport.current.copy(positionMs = position))
                            return@synchronized false
                        }
                    }
                    val position = elapsedOffset + (System.currentTimeMillis() - startedAt)
                    val duration = transport.current.durationMs
                        ?: (transport.handle as? PlaybackHandle.ProviderPlayback)?.durationMs
                    val capped = if (duration != null) position.coerceAtMost(duration) else position
                    transport.publish(transport.current.copy(
                        positionMs = capped,
                        durationMs = duration ?: transport.current.durationMs,
                    ))
                    if (duration != null && position >= duration && active?.gapless != true && !receiverOwnsPlaybackLocked()) {
                        cancelTickerLocked()
                        transport.publish(transport.current.copy(
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
        if (!slot.isActive(active)) return null
        val eof = runtime.readProperty("eof-reached", active.socket)
        if (eof == "yes" || eof == "true") return TickerSample(eof = true, timePosSeconds = null)
        val timePos = runtime.readProperty("time-pos", active.socket)?.toDoubleOrNull()
        return TickerSample(eof = false, timePosSeconds = timePos)
    }

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
                synchronized(transport.lock) {
                    if (token != transport.requestToken || !transport.spotifyIsActive() || !receiver.isAvailable) return@synchronized
                    val handle = transport.handle as PlaybackHandle.ProviderPlayback
                    if (event.trackId != handle.trackId.removePrefix("spotify:track:")) {
                        // Connect gives the receiver a context of its own, so it can roll past
                        // EndOfTrack into a track that was never in the Kainos queue.
                        if (spotifyReceiverEndPending && event.kind == SpotifyReceiverEvent.Kind.PLAYING) {
                            PlaybackTrace.log("Librespot", "receiver rolled over to ${event.trackId}")
                            silenceOverrunReceiverLocked()
                        }
                        return@synchronized
                    }
                    val current = transport.current
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
                                synchronized(transport.lock) {
                                    if (token == transport.requestToken && spotifyReceiverEndPending && !spotifyReceiverStopped) {
                                        silenceOverrunReceiverLocked("Receiver stop acknowledgement timed out")
                                    }
                                }
                            }
                        }
                        SpotifyReceiverEvent.Kind.STOPPED -> {
                            spotifyReceiverStopped = true
                            if (spotifyReceiverEndPending) {
                                if (!transport.userPaused) finishSpotifyReceiverTrackLocked()
                            } else if (!transport.userPaused) {
                                cancelTickerLocked()
                                transport.publish(current.copy(status = EngineStatus.PAUSED))
                            }
                        }
                        SpotifyReceiverEvent.Kind.UNAVAILABLE -> {
                            cancelTickerLocked()
                            transport.publish(current.copy(status = EngineStatus.FAILED, error = "Spotify track is unavailable"))
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
                                userPaused = transport.userPaused,
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
                                transport.userPaused = false
                                clearPauseGuardLocked()
                            } else if (transport.userPaused) spotifyObservationsSincePause++
                            val eventPosition = event.positionMs
                            if (decision.snapClock || eventPosition != null && !transport.userPaused) {
                                elapsedOffset = if (decision.snapClock) decision.positionMs else eventPosition ?: elapsedOffset
                                startedAt = System.currentTimeMillis()
                            }
                            transport.publish(current.copy(
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
        if (!transport.userPaused) finishSpotifyReceiverTrackLocked(warning)
    }

    private fun finishSpotifyReceiverTrackLocked(warning: String? = null) {
        spotifyReceiverEndPending = false
        spotifyReceiverEndTimeout?.cancel()
        spotifyReceiverEndTimeout = null
        cancelTickerLocked()
        val current = transport.current
        transport.publish(current.copy(
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
                val snapshot = synchronized(transport.lock) {
                    if (token != transport.requestToken || !transport.spotifyIsActive()) return@launch
                    if (transport.current.status == EngineStatus.ENDED || transport.current.status == EngineStatus.FAILED) return@launch
                    if (receiverOwnsPlaybackLocked()) return@synchronized null
                    SpotifyReconcileSnapshot(
                        status = transport.current.status,
                        positionMs = transport.current.positionMs,
                        durationMs = transport.current.durationMs,
                        expectedTrackId = (transport.handle as? PlaybackHandle.ProviderPlayback)?.trackId,
                        userPaused = transport.userPaused,
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
                synchronized(transport.lock) {
                    val currentGate = SpotifyObservationGate(
                        requestToken = transport.requestToken,
                        transportGeneration = transportGeneration,
                        userPaused = transport.userPaused,
                    )
                    val capturedGate = SpotifyObservationGate(
                        requestToken = token,
                        transportGeneration = snapshot.transportGeneration,
                        userPaused = snapshot.userPaused,
                    )
                    if (receiverOwnsPlaybackLocked() ||
                        transport.current.status == EngineStatus.ENDED ||
                        !spotifyObservationStillValid(capturedGate, currentGate, transport.spotifyIsActive())) {
                        return@synchronized
                    }
                    spotifyPauseAcknowledged = decision.pauseAcknowledged
                    if (decision.clearUserPause) {
                        transport.userPaused = false
                        clearPauseGuardLocked()
                    } else if (snapshot.userPaused && observed != null) {
                        spotifyObservationsSincePause++
                    }
                    spotifyMisses = decision.misses
                    if (decision.snapClock) {
                        elapsedOffset = decision.positionMs
                        startedAt = System.currentTimeMillis()
                    }
                    val previous = transport.current.status
                    transport.publish(transport.current.copy(
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
        synchronized(transport.lock) {
            if (transport.spotifyIsActive()) transport.publish(transport.current.copy(syncWarning = "$name failed; waiting for receiver state"))
        }
    }

    private data class SpotifyCommand(
        val name: String,
        val action: suspend () -> Unit,
        val completion: CompletableDeferred<Result<Unit>>? = null,
        val pauseCommandId: Long? = null,
    )

    private companion object {
        const val SPOTIFY_OBSERVE_INTERVAL_MS = 4_000L
    }

    private fun shutdown() {
        synchronized(transport.lock) {
            transport.invalidatePendingStartLocked()
            cancelTickerLocked()
            cancelSpotifyReconcileLocked()
            cancelSpotifyReceiverLocked()
            slot.stopLocked()
        }
    }

    private data class Restart(
        val handle: PlaybackHandle,
        val quality: AudioQuality?,
        val startSeconds: Double,
        val token: Long,
        val targetStatus: EngineStatus,
    )
}
