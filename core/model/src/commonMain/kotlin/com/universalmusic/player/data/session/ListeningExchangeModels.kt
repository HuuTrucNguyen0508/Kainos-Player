package com.universalmusic.player.data.session

/** Another device's latest committed export, with its times converted to this device's clock. */
data class PeerListening(
    val deviceId: String,
    val revision: PublicationRevision,
    val lastActiveAtMs: Long,
    val state: PeerListeningState,
)

sealed interface PeerListeningState {
    data class Available(
        val queueHash: QueueHash,
        val current: PortableListeningEntry?,
        val positionMs: Long,
        val positionAtMs: Long,
        val isPlaying: Boolean,
    ) : PeerListeningState

    data class Unavailable(val reason: ProjectionFailure) : PeerListeningState
}

/** A stored peer row; [queue] stays null until a queue matching its hash is verified. */
data class StoredPeerListening(val listening: PeerListening, val queue: PortableListeningQueue?)

/** [stored] is false when a newer or equal revision was already held for that device. */
data class PeerReceipt(val stored: Boolean, val needsQueue: Boolean)

interface ListeningExchangeStore {
    suspend fun readLocalExport(): LocalListeningExport?

    /** This device's published queue, when it still has [hash]. */
    suspend fun readLocalQueue(hash: QueueHash): PortableListeningQueue?

    /** Keeps the newest revision per device; a repeated revision is answered from what is stored. */
    suspend fun receivePeer(peer: PeerListening): PeerReceipt

    /** Attaches [queue] to [deviceId]'s row with the same hash; false when no stored row has it. */
    suspend fun attachPeerQueue(deviceId: String, queue: PortableListeningQueue): Boolean

    suspend fun readPeer(deviceId: String): StoredPeerListening?

    /** The highest local revision [hubDeviceId] has acknowledged with its queue. */
    suspend fun deliveredRevision(hubDeviceId: String): PublicationRevision?

    /** Raises the delivered revision; returns false and changes nothing when [revision] is not higher. */
    suspend fun markDelivered(hubDeviceId: String, revision: PublicationRevision): Boolean
}
