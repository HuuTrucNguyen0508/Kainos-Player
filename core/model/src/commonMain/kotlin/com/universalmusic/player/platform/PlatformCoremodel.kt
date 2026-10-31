package com.universalmusic.player.platform

import com.universalmusic.player.domain.playback.SpotifyObservedPlayback
import kotlinx.coroutines.flow.Flow

expect fun currentTimeMillis(): Long

/** Best-effort network reachability for download queue / availability labels. */

expect fun isNetworkAvailable(): Boolean

/**
 * Monotonic elapsed time in milliseconds while the process is alive.
 * Prefer this over wall-clock for timers (sleep timer) so NTP/clock skew cannot fire early.
 * Resets across process death; timers that rely on it intentionally cancel on reboot.
 */

expect fun monotonicElapsedRealtimeMs(): Long

expect fun sha256Bytes(bytes: ByteArray): ByteArray

expect fun secureRandomBytes(size: Int): ByteArray

expect fun encodeUrl(value: String): String

data class SpotifyPlaybackController(
    val play: suspend (trackId: String) -> Unit,
    val pause: suspend () -> Unit,
    val resume: suspend () -> Unit,
    val seekTo: suspend (positionMs: Long) -> Unit,
    /** Bounded Connect state read. Null when the platform cannot observe the receiver. */
    val observe: (suspend () -> SpotifyObservedPlayback?)? = null,
    val receiverEvents: SpotifyReceiverEvents? = null,
    val setVolume: suspend (Float) -> Unit = {},
)

/** Events from the receiver owned by this app, rather than Spotify's delayed Connect snapshot. */
interface SpotifyReceiverEvents {
    val events: Flow<SpotifyReceiverEvent>
    val isAvailable: Boolean
}

data class SpotifyReceiverEvent(
    val kind: Kind,
    val trackId: String,
    val positionMs: Long? = null,
) {
    enum class Kind { PLAYING, BUFFERING, PAUSED, SEEKED, POSITION_CORRECTION, END_OF_TRACK, STOPPED, UNAVAILABLE }
}

expect fun openUrl(url: String)

expect fun platformLabel(): String
