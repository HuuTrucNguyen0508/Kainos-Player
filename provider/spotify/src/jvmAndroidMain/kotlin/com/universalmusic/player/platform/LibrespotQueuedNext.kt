package com.universalmusic.player.platform

/** One edit of the receiver's Connect queue: drop [remove], then queue [add]. */
internal data class LibrespotQueueEdit(val remove: String?, val add: String?)

/**
 * The edit that leaves [wanted] queued after [current], given that [queued] is queued now. Null
 * when nothing changes. The playing track is never queued: librespot would play it twice.
 */
internal fun planLibrespotQueue(queued: String?, wanted: String?, current: String?): LibrespotQueueEdit? {
    val target = wanted?.takeIf { it != current }
    if (target == queued) return null
    return LibrespotQueueEdit(remove = queued, add = target)
}

/** The next step towards playing a track the receiver may already have queued or opened. */
internal enum class LibrespotAdoptStep {
    /** The receiver is on the track already; make sure it plays. */
    TAKE_CURRENT,
    /** The current track ends within moments; librespot moves on to the queued one by itself. */
    WAIT_FOR_ADVANCE,
    /** Skip to the queued track, which librespot may have opened already. */
    SKIP_TO_QUEUED,
    /** The track is not queued here: load it. */
    LOAD,
}

/** How close to its end the current track must be for a start to wait for librespot's own advance. */
internal const val LIBRESPOT_ADVANCE_WAIT_MS = 3_000L

/**
 * [trackId] was queued ([queued]) or librespot already advanced into it ([advancedTo]). A track
 * that is merely current, without either, is a replay and loads from the start.
 */
internal fun librespotAdoptStep(
    trackId: String,
    current: String?,
    queued: String?,
    advancedTo: String?,
    receiverNext: String?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long?,
    waited: Boolean,
): LibrespotAdoptStep = when {
    trackId != queued && trackId != advancedTo -> LibrespotAdoptStep.LOAD
    current == trackId -> LibrespotAdoptStep.TAKE_CURRENT
    receiverNext != trackId -> LibrespotAdoptStep.LOAD
    // A paused track never ends by itself; the start pauses it first (stopForTransition).
    !waited && playing && durationMs != null && durationMs > 0 && durationMs - positionMs <= LIBRESPOT_ADVANCE_WAIT_MS ->
        LibrespotAdoptStep.WAIT_FOR_ADVANCE
    else -> LibrespotAdoptStep.SKIP_TO_QUEUED
}

/** Android's default network for traces: transports joined by '+', or `none`, then whether it is validated. */
internal fun spotifyNetworkLabel(
    present: Boolean,
    vpn: Boolean = false,
    wifi: Boolean = false,
    cellular: Boolean = false,
    ethernet: Boolean = false,
    validated: Boolean = false,
): String {
    if (!present) return "none"
    val transports = listOfNotNull(
        "vpn".takeIf { vpn }, "wifi".takeIf { wifi }, "cellular".takeIf { cellular }, "ethernet".takeIf { ethernet },
    ).ifEmpty { listOf("other") }
    return transports.joinToString("+") + if (validated) " validated" else " unvalidated"
}
