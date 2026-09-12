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
    /** Playback attempt id from [PlaybackEngine.play]; stale events from older attempts are ignored. */
    val playGeneration: Long = 0L,
)

interface PlaybackEngine {
    val state: StateFlow<EngineState>

    suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long = 0L)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()

    /**
     * Silence the current track because another one is about to start. Engines may keep the
     * output open (audio focus, media session item) instead of a full teardown; a real stop
     * still goes through [stop]. Defaults to [stop].
     */
    fun stopForTransition() = stop()

    fun setVolume(volume: Float)
}

class UnsupportedPlaybackException(
    override val message: String,
) : IllegalStateException(message)
