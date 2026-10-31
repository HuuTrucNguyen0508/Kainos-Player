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
    /** Non-fatal note when receiver state cannot be confirmed. */
    val syncWarning: String? = null,
    val retryAtPositionMs: Long? = null,
    val seamlessItemId: String? = null,
    val previousGeneration: Long? = null,
)

interface PlaybackEngine {
    val state: StateFlow<EngineState>

    suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long = 0L)
    val supportsGapless: Boolean get() = false

    fun configureGapless(enabled: Boolean) = Unit

    /** Replace or cancel the single local successor. Generation identifies its owning track. */
    fun prepareNext(next: PreparedPlayback?) = Unit

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

/** A resolved local file only; engines must decline other handles. */
data class PreparedPlayback(
    val itemId: String,
    val handle: PlaybackHandle.Url,
    val quality: AudioQuality?,
    val generation: Long,
)
