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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidPlaybackEngine(
    context: Context,
    private val spotifyStarter: suspend (String) -> Unit,
) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = ExoPlayer.Builder(context).build()
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private var ticker: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
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
                spotifyStarter(handle.trackId)
                _state.value = EngineState(status = EngineStatus.PLAYING, durationMs = quality?.let { null })
            }
        }
    }

    override fun pause() {
        player.pause()
    }

    override fun resume() {
        player.play()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    override fun stop() {
        player.stop()
        _state.value = EngineState()
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                _state.value = _state.value.copy(positionMs = player.currentPosition)
                delay(400)
            }
        }
    }
}
