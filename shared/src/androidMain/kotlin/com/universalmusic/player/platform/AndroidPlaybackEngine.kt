package com.universalmusic.player.platform

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.UnsupportedPlaybackException
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
import kotlinx.coroutines.channels.Channel

class AndroidPlaybackEngine(
    context: Context,
    private val spotify: SpotifyPlaybackController,
) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val spotifyCommands = Channel<SpotifyCommand>(Channel.UNLIMITED)
    private val player = ExoPlayer.Builder(context).build()
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private var ticker: Job? = null
    private var spotifyActive = false
    private var spotifyStartedAt = 0L
    private var spotifyOffset = 0L

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
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (spotifyActive) return
                val status = when (playbackState) {
                    Player.STATE_BUFFERING -> EngineStatus.BUFFERING
                    Player.STATE_READY -> if (player.isPlaying) EngineStatus.PLAYING else EngineStatus.PAUSED
                    Player.STATE_ENDED -> EngineStatus.ENDED
                    else -> _state.value.status
                }
                _state.value = _state.value.copy(
                    status = status,
                    durationMs = player.duration.takeIf { it > 0 },
                    positionMs = player.currentPosition,
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (spotifyActive) return
                _state.value = _state.value.copy(
                    status = if (isPlaying) EngineStatus.PLAYING else EngineStatus.PAUSED,
                )
                if (isPlaying) startTicker() else ticker?.cancel()
            }
        })
    }

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?) {
        when (handle) {
            is PlaybackHandle.Url -> {
                spotifyActive = false
                player.setMediaItem(MediaItem.fromUri(handle.url))
                player.prepare()
                player.play()
                _state.value = EngineState(status = EngineStatus.BUFFERING)
            }
            is PlaybackHandle.ProviderPlayback -> {
                if (handle.provider != ProviderId.SPOTIFY) {
                    throw UnsupportedPlaybackException(
                        "${handle.provider.displayName} does not expose a supported playback handle on Android.",
                    )
                }
                player.stop()
                runSpotifyCommand("Spotify Connect playback") { spotify.play(handle.trackId) }
                spotifyActive = true
                spotifyOffset = 0
                spotifyStartedAt = System.currentTimeMillis()
                _state.value = EngineState(status = EngineStatus.PLAYING)
                startTicker()
            }
        }
    }

    override fun pause() {
        if (spotifyActive) {
            spotifyOffset = state.value.positionMs
            ticker?.cancel()
            _state.value = _state.value.copy(status = EngineStatus.PAUSED)
            enqueueSpotifyCommand("Spotify Connect pause", spotify.pause)
            return
        }
        player.pause()
    }

    override fun resume() {
        if (spotifyActive) {
            spotifyStartedAt = System.currentTimeMillis()
            _state.value = _state.value.copy(status = EngineStatus.PLAYING)
            startTicker()
            enqueueSpotifyCommand("Spotify Connect resume", spotify.resume)
            return
        }
        player.play()
    }

    override fun seekTo(positionMs: Long) {
        if (spotifyActive) {
            val target = positionMs.coerceAtLeast(0)
            spotifyOffset = target
            spotifyStartedAt = System.currentTimeMillis()
            _state.value = _state.value.copy(positionMs = target)
            enqueueSpotifyCommand("Spotify Connect seek") { spotify.seekTo(target) }
            return
        }
        player.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    override fun stop() {
        val pauseSpotify = spotifyActive
        spotifyActive = false
        ticker?.cancel()
        player.stop()
        _state.value = EngineState()
        if (pauseSpotify) enqueueSpotifyCommand("Spotify Connect stop", spotify.pause)
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val position = if (spotifyActive) {
                    spotifyOffset + (System.currentTimeMillis() - spotifyStartedAt)
                } else {
                    player.currentPosition
                }
                _state.value = _state.value.copy(positionMs = position)
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

    private fun markSpotifyFailed(failure: Throwable, name: String) {
        if (spotifyActive) {
            ticker?.cancel()
            _state.value = _state.value.copy(
                status = EngineStatus.FAILED,
                error = failure.message ?: "$name failed",
            )
        }
    }

    private data class SpotifyCommand(
        val name: String,
        val action: suspend () -> Unit,
        val completion: CompletableDeferred<Result<Unit>>? = null,
    )
}
