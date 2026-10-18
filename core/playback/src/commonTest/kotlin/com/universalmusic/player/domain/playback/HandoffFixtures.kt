package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.SessionSnapshot

internal fun peerHandoffOf(
    snapshot: SessionSnapshot,
    revision: Long,
    deviceId: String = "phone",
    lastActiveAtMs: Long = 0,
): PeerHandoff {
    val queue = (PortableListeningQueue.project(snapshot.items) as QueueProjection.Complete).queue
    val state = ListeningState.create(queue, snapshot.items[snapshot.currentIndex].id, snapshot.positionMs, lastActiveAtMs, false)
    return PeerHandoff(deviceId, PublicationRevision(revision), lastActiveAtMs, state)
}
