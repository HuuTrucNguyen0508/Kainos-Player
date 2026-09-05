package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import kotlinx.coroutines.flow.StateFlow

enum class EngineStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    FAILED,
}

data class EngineState(
    val status: EngineStatus = EngineStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val error: String? = null,
)

interface PlaybackEngine {
    val state: StateFlow<EngineState>

    suspend fun play(handle: PlaybackHandle, quality: AudioQuality?)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()
    fun setVolume(volume: Float)
}

class UnsupportedPlaybackException(
    override val message: String,
) : IllegalStateException(message)
