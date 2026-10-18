package com.universalmusic.player.data.db

import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.HandoffRequest
import com.universalmusic.player.data.session.HandoffClaim
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.ListeningPairingIdentity
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.PeerReceipt
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningEntry
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.PortableListeningTrack
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueHash
import com.universalmusic.player.data.session.StoredPeerListening
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class DbListeningExchangeStore(
    private val storage: KainosStorage,
) : ListeningExchangeStore {
    override suspend fun refusedPairing(): ListeningPairingIdentity? = storage.read { db ->
        db.nowPlayingQueries.selectRefusal().executeAsOneOrNull()
            ?.let { storageJson.decodeFromString<ListeningPairingIdentity>(it) }
    }

    override suspend fun setRefusedPairing(pairing: ListeningPairingIdentity?) = storage.write { db ->
        if (pairing == null) db.nowPlayingQueries.clearRefusal()
        else db.nowPlayingQueries.upsertRefusal(storageJson.encodeToString(pairing))
        Unit
    }

    override suspend fun readLocalExport(): LocalListeningExport? = storage.read { it.readLocalExport() }

    override suspend fun readLocalQueue(hash: QueueHash): PortableListeningQueue? = storage.read { db ->
        db.nowPlayingQueries.selectLocalQueueByHash(hash.hex).executeAsOneOrNull()
            ?.let { PortableListeningQueue.decode(it, hash) }
    }

    override suspend fun receivePeer(peer: PeerListening): PeerReceipt = storage.write { db ->
        val queries = db.nowPlayingQueries
        val existing = queries.selectPeer(peer.deviceId).executeAsOneOrNull()
        val hash = (peer.state as? PeerListeningState.Available)?.queueHash?.hex
        if (existing != null && existing.revision >= peer.revision.value) {
            val same = existing.revision == peer.revision.value && hash != null && existing.queue_hash == hash
            return@write PeerReceipt(stored = false, needsQueue = same && existing.queue_json == null)
        }
        val queueJson = existing?.queue_json?.takeIf { hash != null && existing.queue_hash == hash }
        queries.upsertPeer(
            peer.deviceId,
            peer.revision.value,
            peer.lastActiveAtMs,
            storageJson.encodeToString(peer.state.toStored()),
            hash ?: UNAVAILABLE_QUEUE_HASH,
            queueJson,
        )
        PeerReceipt(stored = true, needsQueue = hash != null && queueJson == null)
    }

    override suspend fun attachPeerQueue(deviceId: String, queue: PortableListeningQueue): Boolean = storage.write { db ->
        val queries = db.nowPlayingQueries
        val existing = queries.selectPeer(deviceId).executeAsOneOrNull()
        if (existing == null || existing.queue_hash != queue.hash.hex) return@write false
        if (existing.queue_json == null) queries.attachPeerQueue(queue.encodeJson(), deviceId, queue.hash.hex)
        true
    }

    override suspend fun readPeer(deviceId: String): StoredPeerListening? = storage.read { db ->
        val row = db.nowPlayingQueries.selectPeer(deviceId).executeAsOneOrNull() ?: return@read null
        val state = storageJson.decodeFromString<StoredPeerState>(row.state_json).toState(row.queue_hash)
        val queue = (state as? PeerListeningState.Available)?.let { available ->
            row.queue_json?.let { PortableListeningQueue.decode(it, available.queueHash) }
        }
        StoredPeerListening(PeerListening(row.device_id, PublicationRevision(row.revision), row.last_active_at_ms, state), queue)
    }

    override suspend fun deliveredRevision(hubDeviceId: String): PublicationRevision? = storage.read { db ->
        db.nowPlayingQueries.selectDelivered(hubDeviceId).executeAsOneOrNull()?.let(::PublicationRevision)
    }

    override suspend fun markDelivered(hubDeviceId: String, revision: PublicationRevision): Boolean = storage.write { db ->
        val queries = db.nowPlayingQueries
        val delivered = queries.selectDelivered(hubDeviceId).executeAsOneOrNull()
        if (delivered != null && delivered >= revision.value) return@write false
        queries.upsertDelivered(hubDeviceId, revision.value)
        true
    }

    override suspend fun appliedHandoff(deviceId: String): PublicationRevision? = storage.read { db ->
        db.nowPlayingQueries.selectApplied(deviceId).executeAsOneOrNull()?.let(::PublicationRevision)
    }

    override suspend fun claimHandoff(request: HandoffRequest, accept: suspend () -> ListeningCommit?): HandoffClaim = storage.write { db ->
        val (deviceId, revision, expectedLocalRevision) = request
        val queries = db.nowPlayingQueries
        queries.lockHandoff(deviceId)
        val peer = queries.selectPeer(deviceId).executeAsOneOrNull()
        if (peer?.revision != revision.value) return@write HandoffClaim.PEER_CHANGED
        val applied = queries.selectApplied(deviceId).executeAsOneOrNull()
        if (applied != null && applied >= revision.value) return@write HandoffClaim.ALREADY_APPLIED
        val export = queries.selectExport().executeAsOneOrNull()
        if (export?.revision != expectedLocalRevision?.value ||
            export != null && export.last_active_at_ms >= peer.last_active_at_ms) return@write HandoffClaim.LOCAL_CHANGED
        val represented = db.readRemoteSession()
        if (represented != null && peer.last_active_at_ms <= represented.lastActiveAtMs) return@write HandoffClaim.NOT_NEWER
        val commit = runBlocking { accept() } ?: return@write HandoffClaim.INSTALL_REJECTED
        queries.upsertApplied(deviceId, revision.value, export?.revision ?: 0L)
        db.commitListening(commit, cached = null, pruneReceipts = false)
        HandoffClaim.CLAIMED
    }
}

private const val UNAVAILABLE_QUEUE_HASH = ""

@Serializable
private data class StoredPeerState(
    val current: PersistedQueueItem? = null,
    val positionMs: Long = 0,
    val positionAtMs: Long = 0,
    val isPlaying: Boolean = false,
    val unavailable: String? = null,
)

private fun PeerListeningState.toStored(): StoredPeerState = when (this) {
    is PeerListeningState.Available -> StoredPeerState(
        current = current?.let { PersistedQueueItem(it.itemId, it.track.track) },
        positionMs = positionMs,
        positionAtMs = positionAtMs,
        isPlaying = isPlaying,
    )
    is PeerListeningState.Unavailable -> StoredPeerState(unavailable = reason.name)
}

private fun StoredPeerState.toState(queueHash: String): PeerListeningState {
    unavailable?.let { return PeerListeningState.Unavailable(ProjectionFailure.valueOf(it)) }
    val entry = current?.let { item ->
        val track = requireNotNull(PortableListeningTrack.parse(item.track)) { "Stored peer track ${item.id} is not portable" }
        PortableListeningEntry(item.id, track)
    }
    return PeerListeningState.Available(QueueHash.parse(queueHash), entry, positionMs, positionAtMs, isPlaying)
}
