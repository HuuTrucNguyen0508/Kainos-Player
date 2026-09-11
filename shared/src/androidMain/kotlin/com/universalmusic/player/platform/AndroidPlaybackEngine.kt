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
    private val spotifyCommands = Channel<SpotifyCommand>(Channel.UNLIMITED)
    private val controller = CompletableDeferred<MediaController>()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaPlayer: MediaController? = null
    private var pendingSpotifyStop: CompletableDeferred<Result<Unit>>? = null
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private var activePlayGeneration: Long = 0L

    private fun publishState(state: EngineState) {
        _state.value = state.copy(playGeneration = activePlayGeneration)
    }
    private var ticker: Job? = null
    private var activeBackend = ActiveBackend.NONE
    private var spotifyStartedAt = 0L
    private var spotifyOffset = 0L

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (activeBackend != ActiveBackend.MEDIA3) return
            val player = mediaPlayer ?: return
            val status = when (playbackState) {
                Player.STATE_BUFFERING -> EngineStatus.BUFFERING
                Player.STATE_READY -> if (player.isPlaying) EngineStatus.PLAYING else EngineStatus.PAUSED
                Player.STATE_ENDED -> EngineStatus.ENDED
                else -> return
            }
            publishState(_state.value.copy(
                status = status,
                durationMs = player.duration.takeIf { it > 0 },
                positionMs = player.currentPosition,
                error = null,
            ))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (activeBackend != ActiveBackend.MEDIA3) return
            val player = mediaPlayer ?: return
            if (!isPlaying &&
                (player.playerError != null || _state.value.status == EngineStatus.FAILED)
            ) return
            val status = when {
                player.playbackState == Player.STATE_ENDED -> EngineStatus.ENDED
                isPlaying -> EngineStatus.PLAYING
                player.playbackState == Player.STATE_BUFFERING -> EngineStatus.BUFFERING
                else -> EngineStatus.PAUSED
            }
            publishState(_state.value.copy(status = status, error = null))
            if (isPlaying) startTicker() else ticker?.cancel()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (activeBackend != ActiveBackend.MEDIA3) return
            ticker?.cancel()
            publishState(_state.value.copy(
                status = EngineStatus.FAILED,
                error = error.message ?: error.errorCodeName,
            ))
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            for (command in spotifyCommands) {
                val result = runCatching { command.action() }
                command.completion?.complete(result)
                if (command.completion == null) {
                    result.onFailure { failure -> markSpotifyFailed(failure, command.name) }
                }
            }
        }
        connectToPlaybackService()
    }

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        activePlayGeneration = playGeneration
        when (handle) {
            is PlaybackHandle.Url -> {
                awaitPendingSpotifyStop()
                stopSpotifyIfActive()
                withContext(Dispatchers.Main.immediate) {
                    val player = controller.await()
                    activeBackend = ActiveBackend.MEDIA3
                    AndroidMediaControls.forwardingPlayer?.setSpotifyActive(false)
                    ticker?.cancel()
                    publishState(EngineState(status = EngineStatus.BUFFERING))
                    val track = AndroidMediaControls.currentTrack()
                    player.setMediaItem(
                        mediaItemForUrl(
                            url = handle.url,
                            track = track,
                            durationMs = track?.durationMs,
                        ),
                    )
                    player.prepare()
                    player.play()
                }
            }
            is PlaybackHandle.ProviderPlayback -> {
                if (handle.provider != ProviderId.SPOTIFY) {
                    throw UnsupportedPlaybackException(
                        "${handle.provider.displayName} does not expose a supported playback handle on Android.",
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    activeBackend = ActiveBackend.SPOTIFY
                    ticker?.cancel()
                    // Await the service connection so MediaSession exists, but do not drive
                    // silence setup through MediaController.pause — that re-enters
                    // PlayerSession.pauseTransport and cancels buffering Spotify startup.
                    controller.await()
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
                try {
                    runSpotifyCommand("Spotify Connect playback") { spotify.play(handle.trackId) }
                } catch (failure: Throwable) {
                    activeBackend = ActiveBackend.NONE
                    withContext(Dispatchers.Main.immediate) {
                        AndroidMediaControls.forwardingPlayer?.setSpotifyActive(false)
                    }
                    throw failure
                }
                spotifyOffset = 0
                spotifyStartedAt = System.currentTimeMillis()
                val duration = handle.durationMs
                    ?: AndroidMediaControls.currentDurationMs()
                    ?: AndroidMediaControls.currentTrack()?.durationMs
                publishState(EngineState(
                    status = EngineStatus.PLAYING,
                    positionMs = 0,
                    durationMs = duration,
                ))
                withContext(Dispatchers.Main.immediate) {
                    AndroidMediaControls.forwardingPlayer?.publishSessionState(
                        mediaId = AndroidMediaControls.currentTrack()?.canonicalId ?: handle.trackId,
                        metadata = AndroidMediaControls.currentTrack()?.toMediaMetadata(duration)
                            ?: androidx.media3.common.MediaMetadata.Builder()
                                .setTitle("Kainos Player")
                                .setIsPlayable(true)
                                .build(),
                        artworkUri = AndroidMediaControls.currentTrack()?.artwork?.url?.let(android.net.Uri::parse),
                        isPlaying = true,
                        buffering = false,
                        positionMs = 0L,
                        durationMs = duration,
                        canSeek = true,
                        canSkipNext = AndroidMediaControls.canSkipNext(),
                        canSkipPrevious = AndroidMediaControls.canSkipPrevious(),
                        spotifyActive = true,
                    )
                }
                startTicker()
            }
        }
    }

    override fun pause() {
        if (activeBackend == ActiveBackend.SPOTIFY) {
            spotifyOffset = state.value.positionMs
            ticker?.cancel()
            publishState(_state.value.copy(status = EngineStatus.PAUSED))
            enqueueSpotifyCommand("Spotify Connect pause", spotify.pause)
            return
        }
        launchMediaCommand { it.pause() }
    }

    override fun resume() {
        if (activeBackend == ActiveBackend.SPOTIFY) {
            spotifyStartedAt = System.currentTimeMillis()
            publishState(_state.value.copy(status = EngineStatus.PLAYING))
            startTicker()
            enqueueSpotifyCommand("Spotify Connect resume", spotify.resume)
            return
        }
        launchMediaCommand { it.play() }
    }

    override fun seekTo(positionMs: Long) {
        if (activeBackend == ActiveBackend.SPOTIFY) {
            val target = positionMs.coerceAtLeast(0)
            spotifyOffset = target
            spotifyStartedAt = System.currentTimeMillis()
            publishState(_state.value.copy(positionMs = target))
            enqueueSpotifyCommand("Spotify Connect seek") { spotify.seekTo(target) }
            return
        }
        val target = positionMs.coerceAtLeast(0)
        publishState(_state.value.copy(positionMs = target))
        launchMediaCommand { it.seekTo(target) }
    }

    override fun stop() {
        val pauseSpotify = activeBackend == ActiveBackend.SPOTIFY
        activeBackend = ActiveBackend.NONE
        ticker?.cancel()
        publishState(EngineState())
        launchMediaCommand {
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

    override fun setVolume(volume: Float) {
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

    private fun launchMediaCommand(command: (MediaController) -> Unit) {
        scope.launch {
            runCatching {
                val player = controller.await()
                withContext(Dispatchers.Main.immediate) { command(player) }
            }
                .onFailure { failure ->
                    if (activeBackend == ActiveBackend.MEDIA3) {
                        ticker?.cancel()
                        publishState(_state.value.copy(
                            status = EngineStatus.FAILED,
                            error = failure.message ?: "Playback service command failed",
                        ))
                    }
                }
        }
    }

    private suspend fun stopSpotifyIfActive() {
        if (activeBackend != ActiveBackend.SPOTIFY) return
        ticker?.cancel()
        runSpotifyCommand("Spotify Connect stop", spotify.pause)
        activeBackend = ActiveBackend.NONE
        withContext(Dispatchers.Main.immediate) {
            AndroidMediaControls.forwardingPlayer?.setSpotifyActive(false)
        }
    }

    private suspend fun awaitPendingSpotifyStop() {
        val pending = pendingSpotifyStop ?: return
        try {
            pending.await().getOrThrow()
        } finally {
            if (pendingSpotifyStop === pending) pendingSpotifyStop = null
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val position = when (activeBackend) {
                    ActiveBackend.SPOTIFY -> spotifyOffset +
                        (System.currentTimeMillis() - spotifyStartedAt)
                    // MediaController must be touched on the application main looper.
                    ActiveBackend.MEDIA3 -> withContext(Dispatchers.Main.immediate) {
                        controller.await().currentPosition
                    }
                    ActiveBackend.NONE -> return@launch
                }
                val duration = _state.value.durationMs
                    ?: AndroidMediaControls.currentDurationMs()
                    ?: AndroidMediaControls.currentTrack()?.durationMs
                val capped = if (duration != null && activeBackend == ActiveBackend.SPOTIFY) {
                    position.coerceAtMost(duration)
                } else {
                    position
                }
                if (
                    activeBackend == ActiveBackend.SPOTIFY &&
                    duration != null &&
                    position >= duration
                ) {
                    ticker?.cancel()
                    publishState(_state.value.copy(
                        status = EngineStatus.ENDED,
                        positionMs = duration,
                        durationMs = duration,
                    ))
                    return@launch
                }
                publishState(_state.value.copy(positionMs = capped, durationMs = duration ?: _state.value.durationMs))
                delay(400)
            }
        }
    }

    private suspend fun runSpotifyCommand(name: String, action: suspend () -> Unit) {
        val completion = CompletableDeferred<Result<Unit>>()
        spotifyCommands.send(SpotifyCommand(name, action, completion))
        completion.await().getOrThrow()
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
        if (activeBackend == ActiveBackend.SPOTIFY) {
            ticker?.cancel()
            publishState(_state.value.copy(
                status = EngineStatus.FAILED,
                error = failure.message ?: "$name failed",
            ))
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
}
