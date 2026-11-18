package com.universalmusic.player.platform

/**
 * What to do about a track Kainos wants playing on the in-app receiver. A VPN starting or stopping
 * closes every open connection: the AP connection and the song's audio stream die together, and
 * librespot either ends the song where it stopped or stalls on it for good (phone trace of
 * 2026-11-18 10:08: a Tailscale switch, then 3 minutes of silence until a manual skip).
 */
internal sealed interface LibrespotRecovery {
    /** Playing, paused on purpose, near its end, or not Kainos's track: leave it alone. */
    data object None : LibrespotRecovery
    /** Broken, but no validated network to reload on yet. */
    data object WaitForNetwork : LibrespotRecovery
    /** Broken and already reloaded [LIBRESPOT_RECOVERY_ATTEMPTS] times: pause where it stopped. */
    data object GiveUp : LibrespotRecovery
    /** Rebuild the receiver and load the track again at [positionMs]. */
    data class Reload(val positionMs: Long) : LibrespotRecovery
}

/** A stall this long is a dead stream, not a slow one: librespot never recovered one on its own. */
internal const val LIBRESPOT_STALL_RECOVERY_MS = 10_000L

/** Reloads of one track before Kainos stops trying and leaves it paused for the listener. */
internal const val LIBRESPOT_RECOVERY_ATTEMPTS = 3

/** A track this close to its end is left to finish; the queue moves on from there. */
internal const val LIBRESPOT_RECOVERY_END_MARGIN_MS = 5_000L

/**
 * [wanted]: Kainos last asked the receiver to play [trackId] and has not paused it since.
 * [receiverTrack] is librespot's current track; another one means something else took the
 * receiver over, which is not ours to undo. [force] is a receiver that must be replaced anyway.
 */
internal fun librespotRecovery(
    wanted: Boolean,
    trackId: String?,
    receiverTrack: String?,
    playing: Boolean,
    stalledForMs: Long?,
    positionMs: Long,
    durationMs: Long?,
    networkValidated: Boolean,
    attempts: Int,
    force: Boolean = false,
): LibrespotRecovery {
    if (!wanted || trackId == null) return LibrespotRecovery.None
    if (receiverTrack != null && receiverTrack != trackId) return LibrespotRecovery.None
    val remainingMs = durationMs?.takeIf { it > 0 }?.let { it - positionMs }
    if (remainingMs != null && remainingMs <= LIBRESPOT_RECOVERY_END_MARGIN_MS) return LibrespotRecovery.None
    val stalled = stalledForMs != null && stalledForMs >= LIBRESPOT_STALL_RECOVERY_MS
    if (!force && playing && !stalled) return LibrespotRecovery.None
    if (attempts >= LIBRESPOT_RECOVERY_ATTEMPTS) return LibrespotRecovery.GiveUp
    if (!networkValidated) return LibrespotRecovery.WaitForNetwork
    return LibrespotRecovery.Reload(positionMs.coerceAtLeast(0))
}

/**
 * Where the receiver is in its track, from its own events: librespot's clock is gone once its
 * player is closed, and a stream that died may have reset it.
 */
internal class LibrespotPositionClock(private val now: () -> Long) {
    private var anchorMs = 0L
    private var anchoredAt = 0L
    private var running = false

    @Synchronized
    fun anchor(positionMs: Long, playing: Boolean) {
        anchorMs = positionMs.coerceAtLeast(0)
        anchoredAt = now()
        running = playing
    }

    /** A seek moves the position and keeps the clock running or stopped. */
    @Synchronized
    fun moveTo(positionMs: Long) = anchor(positionMs, running)

    /** Freezes the clock where it is, for events that carry no position. */
    @Synchronized
    fun stop() = anchor(positionMs(), playing = false)

    @Synchronized
    fun positionMs(): Long = if (running) anchorMs + (now() - anchoredAt).coerceAtLeast(0) else anchorMs
}
