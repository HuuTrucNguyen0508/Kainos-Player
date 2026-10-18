package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.HandoffClaim
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.ListeningPairingIdentity
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.LocalListeningPublication
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerReceipt
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueHash
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.StoredPeerListening
import kotlin.concurrent.Volatile

internal fun items(vararg ids: String) = ids.map { id ->
    PersistedQueueItem("q-$id", PersistedTrack("yt:$id", "Track $id", sources = listOf(PersistedSource("YOUTUBE_MUSIC", id))))
}

internal fun portable(items: List<PersistedQueueItem>): PortableListeningQueue =
    (PortableListeningQueue.project(items) as QueueProjection.Complete).queue

internal class FakeHub(
    private val needsQueue: Boolean = false,
    private val hubRow: ListeningReport? = null,
    private val hubQueue: PortableListeningQueue? = null,
    private val onReport: suspend (Long) -> Unit = {},
) : ListeningHubClient {
    val fetches = mutableListOf<String>()
    val reports = mutableListOf<Long>()
    val uploads = mutableListOf<String>()
    @Volatile private var hasQueue = !needsQueue

    override suspend fun report(report: ListeningReport): ListeningReply {
        reports += report.revision
        onReport(report.revision)
        return ListeningReply(needQueue = !hasQueue, hub = hubRow)
    }

    override suspend fun fetchListening(): ListeningReply = ListeningReply(false)

    override suspend fun uploadQueue(queue: PortableListeningQueue): Boolean {
        uploads += queue.hash.hex
        hasQueue = true
        return true
    }

    override suspend fun fetchQueue(hash: QueueHash): PortableListeningQueue? {
        fetches += hash.hex
        return hubQueue?.takeIf { it.hash == hash }
    }

    override fun close() = Unit
}

internal class PendingStore(items: List<PersistedQueueItem>) : ListeningExchangeStore {
    private var export = LocalListeningExport.Available(
        LocalListeningPublication(PublicationRevision(1), 100, ListeningState.create(portable(items), items[0].id, 0, 100, false)),
    )
    var delivered: PublicationRevision? = null
        private set

    fun publishRevision(revision: Long) {
        export = LocalListeningExport.Available(export.publication.copy(revision = PublicationRevision(revision)))
    }

    private var refusal: ListeningPairingIdentity? = null
    override suspend fun refusedPairing() = refusal
    override suspend fun setRefusedPairing(pairing: ListeningPairingIdentity?) { refusal = pairing }

    override suspend fun readLocalExport(): LocalListeningExport = export
    override suspend fun readLocalQueue(hash: QueueHash): PortableListeningQueue? = null
    override suspend fun receivePeer(peer: PeerListening): PeerReceipt = PeerReceipt(stored = true, needsQueue = false)
    override suspend fun attachPeerQueue(deviceId: String, queue: PortableListeningQueue) = false
    override suspend fun readPeer(deviceId: String): StoredPeerListening? = null
    override suspend fun deliveredRevision(hubDeviceId: String) = delivered
    override suspend fun markDelivered(hubDeviceId: String, revision: PublicationRevision): Boolean {
        delivered = revision
        return true
    }
    override suspend fun appliedHandoff(deviceId: String): PublicationRevision? = null
    override suspend fun claimHandoff(deviceId: String, revision: PublicationRevision) = HandoffClaim.PEER_CHANGED
}
