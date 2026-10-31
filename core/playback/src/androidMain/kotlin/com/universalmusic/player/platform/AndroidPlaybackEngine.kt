package com.universalmusic.player.platform

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.UnsupportedPlaybackException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidPlaybackEngine(
    context: Context,
    private val spotify: SpotifyPlaybackController,
) : PlaybackEngine {
    private val appContext = context.applicationContext
    /** Default scope for Spotify I/O and service connect. ExoPlayer work uses Main.immediate. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val spotifyFocus = SpotifyAudioFocus(appContext)
    private val spotifyCommands = Channel<SpotifyCommand>(Channel.UNLIMITED)
    private val mediaCommands = Channel<MediaCommand>(Channel.UNLIMITED)
    private val controller = CompletableDeferred<MediaController>()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaPlayer: MediaController? = null
    private var pendingSpotifyStop: CompletableDeferred<Result<Unit>>? = null
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    @Volatile private var activePlayGeneration: Long = 0L

    private fun publishState(state: EngineState) {
        _state.update { state.copy(playGeneration = activePlayGeneration) }
    }
    private var receiverJob: Job? = null
    @Volatile private var receiverConfirmed = false
    private var ticker: Job? = null
    @Volatile private var activeBackend = ActiveBackend.NONE
    @Volatile private var spotifyStartedAt = 0L
    @Volatile private var spotifyOffset = 0L
    /** Wall clock of the last Media3 `play()` we issued; focus loss right after it is retried once. */
    @Volatile private var media3PlayRequestedAt = 0L
    private var focusRetryGeneration = -1L
    /** Bumped by explicit pause/stop so a pending focus retry cannot restart audio the user paused. */
    @Volatile private var transportIntent = 0L

    /**
     * A focus request refused within [FOCUS_RETRY_WINDOW_MS] of our own play() is almost
     * always the abandon/re-request glitch, not a phone call or another player. Ask once more;
     * if it fails again the track stays paused so a genuine loss is still honoured.
     */
    private fun retryAfterStartupFocusLoss() {
        if (activeBackend != ActiveBackend.MEDIA3) return
        val sincePlay = System.currentTimeMillis() - media3PlayRequestedAt
        if (sincePlay > FOCUS_RETRY_WINDOW_MS) return
        if (focusRetryGeneration == activePlayGeneration) {
            trace("focus lost again ${sincePlay}ms after play; not retrying")
            return
        }
        focusRetryGeneration = activePlayGeneration
        val generation = activePlayGeneration
        val intent = transportIntent
        trace("focus refused ${sincePlay}ms after play -> retry once in ${FOCUS_RETRY_DELAY_MS}ms")
        scope.launch {
            delay(FOCUS_RETRY_DELAY_MS)
            launchMediaCommand { player ->
                if (activeBackend != ActiveBackend.MEDIA3 || activePlayGeneration != generation) return@launchMediaCommand
                if (transportIntent != intent) {
                    trace("focus retry dropped: user paused/stopped since focus loss")
                    return@launchMediaCommand
                }
                if (player.playWhenReady) return@launchMediaCommand
                trace("focus retry -> play()")
                player.play()
            }
        }
    }

    private fun trace(message: String) =
        PlaybackTrace.log("Engine", "$message | backend=$activeBackend gen=$activePlayGeneration")

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = mediaPlayer
            trace(
                "controller state=${playbackStateName(playbackState)} isPlaying=${player?.isPlaying} " +
                    "playWhenReady=${player?.playWhenReady} suppression=${player?.let { suppressionName(it.playbackSuppressionReason) }}",
            )
            if (activeBackend != ActiveBackend.MEDIA3) return
            if (player == null) return
            val status = when (playbackState) {
                Player.STATE_BUFFERING -> if (player.playWhenReady) EngineStatus.BUFFERING else EngineStatus.PAUSED
                Player.STATE_READY -> if (player.isPlaying) EngineStatus.PLAYING else EngineStatus.PAUSED
                Player.STATE_ENDED -> EngineStatus.ENDED
                else -> return
            }
            _state.update { current -> current.copy(
                status = status,
                durationMs = player.duration.takeIf { it > 0 },
                positionMs = player.currentPosition,
                error = null,
            ) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = mediaPlayer
            trace(
                "controller isPlaying=$isPlaying state=${player?.let { playbackStateName(it.playbackState) }} " +
                    "playWhenReady=${player?.playWhenReady} suppression=${player?.let { suppressionName(it.playbackSuppressionReason) }} " +
                    "error=${player?.playerError?.errorCodeName} pos=${player?.currentPosition}",
            )
            if (activeBackend != ActiveBackend.MEDIA3) return
            if (player == null) return
            if (!isPlaying &&
                (player.playerError != null || _state.value.status == EngineStatus.FAILED)
            ) return
            val status = when {
                player.playbackState == Player.STATE_ENDED -> EngineStatus.ENDED
                isPlaying -> EngineStatus.PLAYING
                player.playbackState == Player.STATE_BUFFERING -> if (player.playWhenReady) EngineStatus.BUFFERING else EngineStatus.PAUSED
                else -> EngineStatus.PAUSED
            }
            _state.update { current -> current.copy(status = status, error = null) }
            if (isPlaying) startTicker() else ticker?.cancel()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            trace("controller playWhenReady=$playWhenReady reason=${playWhenReadyReasonName(reason)}")
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                retryAfterStartupFocusLoss()
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            trace("controller suppression=${suppressionName(playbackSuppressionReason)}")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            trace(
                "controller mediaItemTransition id=${mediaItem?.mediaId} " +
                    "reason=${mediaItemTransitionReasonName(reason)}",
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            trace("controller error=${error.errorCodeName} msg=${error.message} cause=${error.cause}")
            if (activeBackend != ActiveBackend.MEDIA3) return
            ticker?.cancel()
            _state.update { current -> current.copy(
                status = EngineStatus.FAILED,
                error = error.message ?: error.errorCodeName,
            ) }
        }
    }

    init {
        val power = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Kainos:playback")
        wakeLock.setReferenceCounted(false)
        scope.launch {
            state.map { current ->
                com.universalmusic.player.domain.playback.playbackNeedsWakeLock(activeBackend == ActiveBackend.SPOTIFY, current.status)
            }.distinctUntilChanged().collectLatest { needed ->
                try {
                    if (needed) {
                        while (true) {
                            wakeLock.acquire(120_000L)
                            delay(60_000L)
                        }
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            for (command in spotifyCommands) {
                val result = runCatching { command.action() }
                command.completion?.complete(result)
                if (command.completion == null) {
                    result.onFailure { failure -> markSpotifyFailed(failure, command.name) }
                }
            }
        }
        // Single consumer on Main: MediaController mutations execute in submission order.
        // stop() and the next play() used to race each other onto the main looper from
        // different Default threads; when play won, the stale clear killed the new track.
        scope.launch(Dispatchers.Main.immediate) {
            for (command in mediaCommands) {
                if (command.cancelled) continue
                val result = runCatching { command.action(controller.await()) }
                if (command.completion != null) {
                    command.completion.complete(result)
                } else {
                    result.onFailure { failure -> markMediaCommandFailed(failure) }
                }
            }
        }
        connectToPlaybackService()
    }

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        try {
            playInternal(handle, quality, playGeneration)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (activePlayGeneration == playGeneration) {
                _state.update { it.copy(status = EngineStatus.FAILED, error = failure.message) }
            }
            throw failure
        }
    }

    private suspend fun playInternal(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        val previousBackend = activeBackend
        activePlayGeneration = playGeneration
        when (handle) {
            is PlaybackHandle.Url -> {
                trace("play(Url ${handle.url.hostForTrace()}) from=$previousBackend pendingSpotifyStop=${pendingSpotifyStop != null}")
                awaitPendingSpotifyStop()
                stopSpotifyIfActive()
                // Same FIFO as stop(): a pending clearRetainedMedia from the previous track
                // always runs before this setMediaItem, never after it.
                runMediaCommand { player ->
                    spotifyFocus.release()
                    AndroidMediaControls.forwardingPlayer?.useSpotifyAudioFocus(false)
                    activeBackend = ActiveBackend.MEDIA3
                    AndroidMediaControls.forwardingPlayer?.setSpotifyActive(false)
                    ticker?.cancel()
                    publishState(EngineState(status = EngineStatus.BUFFERING))
                    val track = AndroidMediaControls.currentTrack()
                    trace(
                        "controller setMediaItem+prepare+play track='${track?.title}' " +
                            "(before: state=${playbackStateName(player.playbackState)} items=${player.mediaItemCount})",
                    )
                    player.setMediaItem(
                        mediaItemForUrl(
                            url = handle.url,
                            track = track,
                            durationMs = track?.durationMs,
                        ),
                    )
                    player.prepare()
                    player.play()
                    media3PlayRequestedAt = System.currentTimeMillis()
                }
            }
            is PlaybackHandle.ProviderPlayback -> {
                if (handle.provider != ProviderId.SPOTIFY) {
                    throw UnsupportedPlaybackException(
                        "${handle.provider.displayName} does not expose a supported playback handle on Android.",
                    )
                }
                trace("play(Spotify ${handle.trackId}) from=$previousBackend dur=${handle.durationMs}")
                // Queued behind any pending stop() clear so the silence item is not wiped
                // right after it is published. Do not drive silence setup through
                // MediaController.pause — that re-enters PlayerSession.pauseTransport and
                // cancels buffering Spotify startup.
                runMediaCommand {
                    AndroidMediaControls.forwardingPlayer?.useSpotifyAudioFocus(true)
                    spotifyFocus.play()
                    activeBackend = ActiveBackend.SPOTIFY
                    ticker?.cancel()
                    val track = AndroidMediaControls.currentTrack()
                    publishState(EngineState(
                        status = EngineStatus.BUFFERING,
                        durationMs = handle.durationMs ?: track?.durationMs,
                    ))
                    AndroidMediaControls.forwardingPlayer?.publishSessionState(
                        mediaId = track?.canonicalId ?: handle.trackId,
                        metadata = track?.toMediaMetadata(handle.durationMs ?: track?.durationMs)
                            ?: androidx.media3.common.MediaMetadata.Builder()
                                .setTitle("Kainos Player")
                                .setIsPlayable(true)
                                .build(),
                        artworkUri = track?.artwork?.url?.let(android.net.Uri::parse),
                        isPlaying = true,
                        buffering = true,
                        positionMs = 0L,
                        durationMs = handle.durationMs ?: track?.durationMs,
                        canSeek = true,
                        canSkipNext = AndroidMediaControls.canSkipNext(),
                        canSkipPrevious = AndroidMediaControls.canSkipPrevious(),
                        spotifyActive = true,
                    )
                }
                receiverJob?.cancel()
                receiverConfirmed = false
                val generation = activePlayGeneration
                receiverJob = spotify.receiverEvents?.let { receiver ->
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        receiver.events.collect { event ->
                            if (activeBackend != ActiveBackend.SPOTIFY || activePlayGeneration != generation) return@collect
                            val status = _state.value.status
                            if (status == EngineStatus.ENDED || status == EngineStatus.FAILED) return@collect
                            val sameTrack = event.trackId == handle.trackId.removePrefix("spotify:track:")
                            val finished = com.universalmusic.player.domain.playback.spotifyTrackFinished(
                                event.kind, sameTrack, receiverConfirmed, _state.value.positionMs, _state.value.durationMs,
                            )
                            if (finished) {
                                finishSpotifyTrack()
                                return@collect
                            }
                            if (!sameTrack || event.kind == SpotifyReceiverEvent.Kind.END_OF_TRACK) return@collect
                            receiverConfirmed = true
                            spotifyOffset = event.positionMs ?: _state.value.positionMs
                            spotifyStartedAt = System.currentTimeMillis()
                            _state.update { current -> current.copy(
                                status = com.universalmusic.player.domain.playback.spotifyReceiverStatus(event.kind, current.status),
                                positionMs = spotifyOffset,
                                error = if (event.kind == SpotifyReceiverEvent.Kind.UNAVAILABLE) "Spotify track is unavailable" else null) }
                            if (_state.value.status == EngineStatus.PLAYING) startTicker() else ticker?.cancel()
                        }
                    }
                }
                try {
                    runSpotifyCommand("Spotify Connect playback") { spotify.play(handle.trackId) }
                } catch (failure: Throwable) {
                    trace("Spotify Connect playback failed: ${failure.message}")
                    activeBackend = ActiveBackend.NONE
                    launchMediaCommand {
                        AndroidMediaControls.forwardingPlayer?.setSpotifyActive(false)
                    }
                    throw failure
                }
                if (!receiverConfirmed) {
                    spotifyOffset = 0
                    spotifyStartedAt = System.currentTimeMillis()
                }
                val duration = handle.durationMs
                    ?: AndroidMediaControls.currentDurationMs()
                    ?: AndroidMediaControls.currentTrack()?.durationMs
                if (!receiverConfirmed && spotify.receiverEvents?.isAvailable != true) publishState(EngineState(
                    status = EngineStatus.PLAYING,
                    positionMs = 0,
                    durationMs = duration,
                ))
                runMediaCommand {
                    AndroidMediaControls.forwardingPlayer?.publishSessionState(
                        mediaId = AndroidMediaControls.currentTrack()?.canonicalId ?: handle.trackId,
                        metadata = AndroidMediaControls.currentTrack()?.toMediaMetadata(duration)
                            ?: androidx.media3.common.MediaMetadata.Builder()
                                .setTitle("Kainos Player")
                                .setIsPlayable(true)
                                .build(),
                        artworkUri = AndroidMediaControls.currentTrack()?.artwork?.url?.let(android.net.Uri::parse),
                        isPlaying = _state.value.status == EngineStatus.PLAYING,
                        buffering = _state.value.status == EngineStatus.BUFFERING,
                        positionMs = _state.value.positionMs,
                        durationMs = duration,
                        canSeek = true,
                        canSkipNext = AndroidMediaControls.canSkipNext(),
                        canSkipPrevious = AndroidMediaControls.canSkipPrevious(),
                        spotifyActive = true,
                    )
                }
                if (_state.value.status == EngineStatus.PLAYING) startTicker()
                else {
                    val intent = transportIntent
                    scope.launch {
                        // Without receiver events the clock is all we have; never sit in buffering.
                        while (true) {
                            delay(RECEIVER_CONFIRM_TIMEOUT_MS)
                            if (activeBackend != ActiveBackend.SPOTIFY || activePlayGeneration != generation ||
                                receiverConfirmed || transportIntent != intent ||
                                _state.value.status != EngineStatus.BUFFERING) return@launch
                            val observed = runCatching { spotify.observe?.invoke() }.getOrNull()
                            if (observed?.isPlaying == false) continue
                            trace("no receiver event after ${RECEIVER_CONFIRM_TIMEOUT_MS}ms -> wall clock")
                            spotifyStartedAt = System.currentTimeMillis()
                            _state.update { it.copy(status = EngineStatus.PLAYING, durationMs = duration ?: it.durationMs) }
                            startTicker()
                            return@launch
                        }
                    }
                }
            }
        }
    }

    private fun finishSpotifyTrack() {
        trace("Spotify track finished")
        ticker?.cancel()
        // The receiver may roll into a track Kainos never queued; silence it.
        enqueueSpotifyCommand("Spotify Connect stop", spotify.pause)
        _state.update { it.copy(status = EngineStatus.ENDED, positionMs = it.durationMs ?: it.positionMs) }
    }

    override fun pause() {
        trace("pause() status=${_state.value.status} pos=${_state.value.positionMs}")
        transportIntent++
        if (activeBackend == ActiveBackend.SPOTIFY) {
            launchMediaCommand { spotifyFocus.pause() }
            spotifyOffset = state.value.positionMs
            ticker?.cancel()
            _state.update { current -> current.copy(status = EngineStatus.PAUSED) }
            enqueueSpotifyCommand("Spotify Connect pause", spotify.pause)
            return
        }
        _state.update { it.copy(status = EngineStatus.PAUSED) }
        launchMediaCommand { it.pause() }
    }

    override fun resume() {
        transportIntent++
        trace("resume() status=${_state.value.status} pos=${_state.value.positionMs}")
        if (activeBackend == ActiveBackend.SPOTIFY) {
            spotifyStartedAt = System.currentTimeMillis()
            _state.update { current -> current.copy(status = EngineStatus.PLAYING) }
            startTicker()
            launchMediaCommand {
                spotifyFocus.play()
                enqueueSpotifyCommand("Spotify Connect resume", spotify.resume)
            }
            return
        }
        launchMediaCommand { it.play() }
    }

    override fun seekTo(positionMs: Long) {
        transportIntent++
        trace("seekTo($positionMs)")
        if (activeBackend == ActiveBackend.SPOTIFY) {
            val target = positionMs.coerceAtLeast(0)
            spotifyOffset = target
            spotifyStartedAt = System.currentTimeMillis()
            _state.update { current -> current.copy(positionMs = target) }
            enqueueSpotifyCommand("Spotify Connect seek") { spotify.seekTo(target) }
            return
        }
        val target = positionMs.coerceAtLeast(0)
        _state.update { current -> current.copy(positionMs = target) }
        launchMediaCommand { it.seekTo(target) }
    }

    /**
     * Between two Media3 tracks the player is only paused, never stopped. `stop()` takes
     * ExoPlayer to IDLE, which abandons audio focus; the re-request a few ms later can be
     * refused (seen on HyperOS as `playWhenReady=false reason=AUDIO_FOCUS_LOSS`) and the new
     * track starts paused. Pausing keeps focus and the next `setMediaItem` replaces the item.
     */
    override fun stopForTransition() {
        if (activeBackend == ActiveBackend.SPOTIFY) {
            ticker?.cancel()
            enqueueSpotifyCommand("Spotify Connect transition", spotify.pause)
            publishState(EngineState(status = EngineStatus.BUFFERING))
            return
        }
        if (activeBackend != ActiveBackend.MEDIA3) {
            stop()
            publishState(EngineState(status = EngineStatus.BUFFERING))
            return
        }
        trace("stopForTransition() status=${_state.value.status} pos=${_state.value.positionMs} -> pause Exo, keep item + audio focus")
        activeBackend = ActiveBackend.NONE
        ticker?.cancel()
        publishState(EngineState(status = EngineStatus.BUFFERING))
        launchMediaCommand { it.pause() }
    }

    override fun stop() {
        transportIntent++
        val pauseSpotify = activeBackend == ActiveBackend.SPOTIFY
        trace("stop() status=${_state.value.status} pos=${_state.value.positionMs} (clearRetainedMedia queued on Main)")
        activeBackend = ActiveBackend.NONE
        ticker?.cancel()
        publishState(EngineState())
        launchMediaCommand {
            spotifyFocus.release()
            trace(
                "stop() -> clearRetainedMedia on Main; controller state=${playbackStateName(it.playbackState)} " +
                    "items=${it.mediaItemCount} id=${it.currentMediaItem?.mediaId}",
            )
            AndroidMediaControls.forwardingPlayer?.clearRetainedMedia()
                ?: run {
                    AndroidMediaControls.forwardingPlayer?.setSpotifyActive(false)
                    it.stop()
                    it.clearMediaItems()
                }
        }
        if (pauseSpotify) {
            pendingSpotifyStop = enqueueSpotifyCommandWithCompletion(
                "Spotify Connect stop",
                spotify.pause,
            )
        }
    }

    private var volumeJob: Job? = null
    override fun setVolume(volume: Float) {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(250)
            if (activeBackend == ActiveBackend.SPOTIFY) {
                runCatching { spotify.setVolume(volume.coerceIn(0f, 1f)) }
                    .onFailure { PlaybackTrace.log("Engine", "Spotify volume update failed") }
            }
        }

        launchMediaCommand { it.volume = volume.coerceIn(0f, 1f) }
    }

    private fun connectToPlaybackService() {
        scope.launch {
            val token = SessionToken(
                appContext,
                ComponentName(appContext, AndroidPlaybackService::class.java),
            )
            val future = MediaController.Builder(appContext, token).buildAsync()
            controllerFuture = future
            try {
                val player = future.await()
                withContext(Dispatchers.Main.immediate) {
                    mediaPlayer = player
                    player.addListener(playerListener)
                }
                controller.complete(player)
            } catch (failure: Throwable) {
                val cause = (failure as? ExecutionException)?.cause ?: failure
                trace("MediaController connect failed: ${cause.message}")
                controller.completeExceptionally(cause)
                publishState(EngineState(
                    status = EngineStatus.FAILED,
                    error = cause.message ?: "Could not connect to the playback service",
                ))
            }
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener(
                {
                    if (cont.isCancelled) return@addListener
                    try {
                        val value = get() as T
                        cont.resume(value)
                    } catch (failure: Throwable) {
                        val cause = (failure as? ExecutionException)?.cause ?: failure
                        cont.resumeWithException(cause)
                    }
                },
                Executor { runnable -> runnable.run() },
            )
            cont.invokeOnCancellation {
                runCatching { cancel(true) }
            }
        }

    /** Fire-and-forget MediaController mutation, executed in FIFO order on Main. */
    private fun launchMediaCommand(command: (MediaController) -> Unit) {
        mediaCommands.trySend(MediaCommand(command))
    }

    /**
     * Same FIFO as [launchMediaCommand], but suspends until the command ran and rethrows its
     * failure. If the caller is cancelled while waiting (PlayerSession superseded this play),
     * the queued command is skipped so a dead generation never touches the player.
     */
    private suspend fun runMediaCommand(command: (MediaController) -> Unit) {
        val queued = MediaCommand(command, CompletableDeferred())
        mediaCommands.send(queued)
        val result = try {
            queued.completion!!.await()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            queued.cancelled = true
            throw cancelled
        }
        result.getOrThrow()
    }

    private fun markMediaCommandFailed(failure: Throwable) {
        trace("media command failed: ${failure.message}")
        if (activeBackend == ActiveBackend.MEDIA3) {
            ticker?.cancel()
            _state.update { current -> current.copy(
                status = EngineStatus.FAILED,
                error = failure.message ?: "Playback service command failed",
            ) }
        }
    }

    private suspend fun stopSpotifyIfActive() {
        if (activeBackend != ActiveBackend.SPOTIFY) return
        trace("stopSpotifyIfActive -> Connect pause before URL playback")
        ticker?.cancel()
        runCatching { runSpotifyCommand("Spotify Connect stop", spotify.pause) }
            .onFailure { markSpotifyFailed(it, "Spotify Connect stop") }
        activeBackend = ActiveBackend.NONE
        runMediaCommand {
            AndroidMediaControls.forwardingPlayer?.setSpotifyActive(false)
        }
    }

    private suspend fun awaitPendingSpotifyStop() {
        val pending = pendingSpotifyStop ?: return
        try {
            pending.await().onFailure { markSpotifyFailed(it, "Spotify Connect stop") }
        } finally {
            if (pendingSpotifyStop === pending) pendingSpotifyStop = null
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        val generation = activePlayGeneration
        ticker = scope.launch {
            var lastPosition = Long.MIN_VALUE
            var stalledTicks = 0
            while (activePlayGeneration == generation) {
                val position = when (activeBackend) {
                    ActiveBackend.SPOTIFY -> spotifyOffset +
                        (System.currentTimeMillis() - spotifyStartedAt)
                    // MediaController must be touched on the application main looper.
                    ActiveBackend.MEDIA3 -> withContext(Dispatchers.Main.immediate) {
                        controller.await().currentPosition
                    }
                    ActiveBackend.NONE -> return@launch
                }
                // Media3 reports PLAYING but the clock is frozen: audio stalled without a state change.
                if (activeBackend == ActiveBackend.MEDIA3 && _state.value.status == EngineStatus.PLAYING) {
                    if (position == lastPosition) {
                        stalledTicks++
                        if (stalledTicks == STALL_TICKS_BEFORE_TRACE) {
                            trace("position stalled at $position while PLAYING for ~${stalledTicks * TICK_MS}ms")
                        }
                    } else {
                        if (stalledTicks >= STALL_TICKS_BEFORE_TRACE) trace("position resumed at $position")
                        stalledTicks = 0
                    }
                    lastPosition = position
                }
                val duration = _state.value.durationMs
                    ?: AndroidMediaControls.currentDurationMs()
                    ?: AndroidMediaControls.currentTrack()?.durationMs
                val capped = if (duration != null && activeBackend == ActiveBackend.SPOTIFY) {
                    position.coerceAtMost(duration)
                } else {
                    position
                }
                // A confirmed receiver ends its own track; the clock only backstops a lost event.
                val graceMs = if (receiverConfirmed) RECEIVER_END_GRACE_MS else 0L
                if (
                    activeBackend == ActiveBackend.SPOTIFY &&
                    duration != null &&
                    position >= duration + graceMs
                ) {
                    trace("Spotify ticker reached duration=$duration -> synthetic ENDED")
                    _state.update { it.copy(durationMs = duration) }
                    finishSpotifyTrack()
                    return@launch
                }
                _state.update { current ->
                    if (generation != activePlayGeneration) current else
                        current.copy(positionMs = capped, durationMs = duration ?: current.durationMs)
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun runSpotifyCommand(name: String, action: suspend () -> Unit) {
        val completion = CompletableDeferred<Result<Unit>>()
        val startedAt = System.currentTimeMillis()
        spotifyCommands.send(SpotifyCommand(name, action, completion))
        val result = completion.await()
        trace("$name ${if (result.isSuccess) "ok" else "failed: ${result.exceptionOrNull()?.message}"} in ${System.currentTimeMillis() - startedAt}ms")
        result.getOrThrow()
    }

    private fun enqueueSpotifyCommand(name: String, action: suspend () -> Unit) {
        spotifyCommands.trySend(SpotifyCommand(name, action))
    }

    private fun enqueueSpotifyCommandWithCompletion(
        name: String,
        action: suspend () -> Unit,
    ): CompletableDeferred<Result<Unit>> {
        val completion = CompletableDeferred<Result<Unit>>()
        spotifyCommands.trySend(SpotifyCommand(name, action, completion))
        return completion
    }

    private fun markSpotifyFailed(failure: Throwable, name: String) {
        PlaybackTrace.log("Engine", "$name warning: ${failure.message}")
        if (name.endsWith("stop") || name.endsWith("transition")) return
        if (activeBackend != ActiveBackend.SPOTIFY) return
        _state.update { it.copy(syncWarning = "$name failed; waiting for receiver state") }
        val generation = activePlayGeneration
        val intent = transportIntent
        scope.launch {
            val observed = runCatching { spotify.observe?.invoke() }.getOrNull() ?: return@launch
            if (activeBackend != ActiveBackend.SPOTIFY || activePlayGeneration != generation || transportIntent != intent) return@launch
            spotifyOffset = observed.progressMs ?: _state.value.positionMs
            spotifyStartedAt = System.currentTimeMillis()
            _state.update { it.copy(
                status = if (observed.isPlaying) EngineStatus.PLAYING else EngineStatus.PAUSED,
                positionMs = spotifyOffset,
            ) }
            if (observed.isPlaying) startTicker() else ticker?.cancel()
        }
    }

    private enum class ActiveBackend {
        NONE,
        MEDIA3,
        SPOTIFY,
    }

    private data class SpotifyCommand(
        val name: String,
        val action: suspend () -> Unit,
        val completion: CompletableDeferred<Result<Unit>>? = null,
    )

    private class MediaCommand(
        val action: (MediaController) -> Unit,
        val completion: CompletableDeferred<Result<Unit>>? = null,
    ) {
        @Volatile var cancelled: Boolean = false
    }

    private companion object {
        const val TICK_MS = 400L
        const val RECEIVER_CONFIRM_TIMEOUT_MS = 10_000L
        const val RECEIVER_END_GRACE_MS = 5_000L
        /** ~2 s of frozen position while PLAYING before the ticker writes a stall line. */
        const val STALL_TICKS_BEFORE_TRACE = 5
        const val FOCUS_RETRY_WINDOW_MS = 1_500L
        const val FOCUS_RETRY_DELAY_MS = 250L
    }
}

/** Stream URLs carry signed tokens; the trace keeps only scheme + host. */
private fun String.hostForTrace(): String {
    val uri = runCatching { android.net.Uri.parse(this) }.getOrNull() ?: return "<unparseable>"
    val scheme = uri.scheme ?: return "<no-scheme>"
    return if (uri.host != null) "$scheme://${uri.host}" else "$scheme:${uri.path?.substringAfterLast('/') ?: ""}"
}
