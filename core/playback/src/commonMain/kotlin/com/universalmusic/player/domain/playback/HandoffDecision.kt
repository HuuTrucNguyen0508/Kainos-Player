package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningEntry
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.StoredPeerListening
import com.universalmusic.player.data.sync.LocalSyncIdentityIndex
import com.universalmusic.player.data.sync.localSyncIdentity

const val HANDOFF_MAX_AGE_MS = 24L * 60 * 60 * 1000

enum class HandoffSkipReason {
    LOCAL_PLAYING,
    LOCAL_BUFFERING,
    /** The peer could not export its queue. */
    UNAVAILABLE,
    ALREADY_APPLIED,
    TOO_OLD,
    NOT_NEWER,
    /** The row arrived but its queue has not. */
    NO_QUEUE,
    /** The peer has no current entry. */
    NOTHING_TO_LOAD,
    /** The peer's current entry is not the entry its queue holds under that id. */
    INCONSISTENT,
    /** This device acted after the decision. */
    LOCAL_CHANGED,
    /** A newer peer row replaced the one this load read. */
    PEER_CHANGED,
}

/** [lastActiveAtMs] is null when this device has never had local listening activity. */
data class LocalHandoffState(val isPlaying: Boolean, val buffering: Boolean, val lastActiveAtMs: Long?)

data class PeerHandoff(
    val deviceId: String,
    val revision: PublicationRevision,
    val lastActiveAtMs: Long,
    val state: ListeningState,
) {
    val current: PortableListeningEntry = requireNotNull(state.current) { "A handoff needs a current entry" }
}

sealed interface HandoffDecision {
    data class Load(val peer: PeerHandoff) : HandoffDecision
    data class Skip(val reason: HandoffSkipReason) : HandoffDecision
}

fun decideHandoff(
    local: LocalHandoffState,
    peer: StoredPeerListening,
    applied: PublicationRevision?,
    nowMs: Long,
): HandoffDecision {
    val listening = peer.listening
    val reported = listening.state
    val skip = when {
        local.isPlaying -> HandoffSkipReason.LOCAL_PLAYING
        local.buffering -> HandoffSkipReason.LOCAL_BUFFERING
        reported !is PeerListeningState.Available -> HandoffSkipReason.UNAVAILABLE
        applied != null && listening.revision.value <= applied.value -> HandoffSkipReason.ALREADY_APPLIED
        nowMs - listening.lastActiveAtMs > HANDOFF_MAX_AGE_MS -> HandoffSkipReason.TOO_OLD
        local.lastActiveAtMs != null && listening.lastActiveAtMs <= local.lastActiveAtMs -> HandoffSkipReason.NOT_NEWER
        else -> null
    }
    if (skip != null) return HandoffDecision.Skip(skip)
    reported as PeerListeningState.Available
    val queue = peer.queue ?: return HandoffDecision.Skip(HandoffSkipReason.NO_QUEUE)
    val current = reported.current ?: return HandoffDecision.Skip(HandoffSkipReason.NOTHING_TO_LOAD)
    val state = runCatching {
        ListeningState.create(queue, current.itemId, reported.positionMs, reported.positionAtMs, reported.isPlaying)
    }.getOrNull()
    if (state?.current != current) return HandoffDecision.Skip(HandoffSkipReason.INCONSISTENT)
    return HandoffDecision.Load(PeerHandoff(listening.deviceId, listening.revision, listening.lastActiveAtMs, state))
}

/**
 * The peer's queue as this device's paused session. Local entries become the matching library
 * track; an entry with no match stays as the peer sent it.
 */
fun PeerHandoff.toSessionSnapshot(localTracks: LocalSyncIdentityIndex, nowMs: Long): SessionSnapshot {
    val entries = state.queue.entries
    val items = entries.map { entry ->
        val portable = entry.track.track
        val matched = portable.localSyncIdentity()?.let(localTracks::match)?.toPersisted(nowMs)
        PersistedQueueItem(entry.itemId, matched ?: portable)
    }
    val elapsedMs = if (state.isPlaying) (nowMs - state.positionAtMs).coerceAtLeast(0L) else 0L
    val durationMs = current.track.track.durationMs
    val positionMs = (state.positionMs + elapsedMs).let { position -> durationMs?.let(position::coerceAtMost) ?: position }
    return SessionSnapshot(
        items = items,
        currentIndex = entries.indexOfFirst { it.itemId == current.itemId },
        positionMs = positionMs,
    )
}
