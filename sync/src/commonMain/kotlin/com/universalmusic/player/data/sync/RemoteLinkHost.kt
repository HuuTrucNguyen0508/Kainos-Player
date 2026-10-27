package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.currentTimeMillis
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val HOME_LAN_SYNC_PATH_V3_REMOTE_POLL = "/kainos-sync/v3/remote/poll"
const val HOME_LAN_SYNC_PATH_V3_REMOTE_COMMAND = "/kainos-sync/v3/remote/command"
const val HOME_LAN_SYNC_PATH_V3_REMOTE_TRANSFER = "/kainos-sync/v3/remote/transfer"
const val HOME_LAN_SYNC_PATH_V3_REMOTE_ARTWORK = "/kainos-sync/v3/remote/artwork"

/** How a command sent to a polling device ended. */
enum class RemoteSendOutcome {
    /** The device acknowledged it. */
    DELIVERED,

    /** No poll was held and none was answered in the last few seconds, so nothing was queued. */
    UNREACHABLE,

    /** It expired before the device fetched it, or the device dropped it as late. */
    NOT_DELIVERED,

    /** The device fetched it but no acknowledgement came. */
    UNCONFIRMED,

    /** The hub stopped. */
    STOPPED,
}

sealed interface RemoteHostTransfer {
    data class Received(val reply: RemoteTransferReply) : RemoteHostTransfer
    data class Failed(val outcome: RemoteSendOutcome) : RemoteHostTransfer
}

sealed interface RemoteHostReply<out T> {
    data class Ok<T>(val value: T) : RemoteHostReply<T>
    data class Refused(val status: Int, val reason: String) : RemoteHostReply<Nothing>
}

/** What the hub knows about one polling device; [statusAtMs] is hub time. */
data class RemotePeerView(
    val deviceId: String,
    val connected: Boolean,
    val status: RemoteStatus?,
    val statusAtMs: Long,
)

/**
 * The hub half of the remote link. Devices long-poll [poll]; this side queues commands with
 * [send] and they ride the held poll. Sequences, acknowledgements and [RemotePollReply.hubVersion]
 * belong to the epoch drawn by [start], so a hub restart invalidates every outstanding command:
 * delivery is at most once within an epoch.
 */
class RemoteLinkHost(
    private val hubName: () -> String,
    private val clock: () -> Long = ::currentTimeMillis,
    private val newEpoch: () -> String = { Random.nextLong().toULong().toString(36) },
    private val holdMs: Long = REMOTE_POLL_HOLD_MS,
    private val connectedGraceMs: Long = REMOTE_CONNECTED_GRACE_MS,
    private val deadlineMs: Long = REMOTE_COMMAND_DEADLINE_MS,
    private val ackGraceMs: Long = REMOTE_ACK_GRACE_MS,
    private val transferWaitMs: Long = REMOTE_TRANSFER_WAIT_MS,
) {
    private class Waiter {
        val wake = CompletableDeferred<Unit>()
        var superseded = false
    }

    private class Outgoing(val seq: Long, val command: RemoteCommand, val queuedAtMs: Long) {
        var delivered = false
        val result = CompletableDeferred<RemoteSendOutcome>()
    }

    private class Link {
        var linkId: String? = null
        var generation = 0L
        var waiter: Waiter? = null
        var lastReplyAtMs: Long? = null
        var status: RemoteStatus? = null
        var statusAtMs = 0L
        var nextSeq = 0L
        val outbox = ArrayList<Outgoing>()
        val transfers = HashMap<Long, CompletableDeferred<RemoteTransferReply>>()
        var artwork: Pair<String, ByteArray>? = null
    }

    private val lock = Mutex()
    private val links = HashMap<String, Link>()
    private var running = false
    private var epoch = ""
    private var hubVersion = 0L
    private var hubStatus: RemoteStatus? = null
    private var hubStatusAtMs = 0L
    private var hubHasPlayer = false
    private var hubArtwork: Pair<String, ByteArray>? = null

    private val _changes = MutableStateFlow(0L)

    /** Ticks whenever a device's status, connection or artwork may have changed. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun changed() = _changes.update { it + 1 }

    /** Draws a new epoch; every earlier sequence, acknowledgement and device link is forgotten. */
    suspend fun start() = lock.withLock {
        epoch = newEpoch()
        running = true
        links.clear()
        changed()
    }

    /** Wakes every held poll and ends every pending command, so the server can stop at once. */
    suspend fun stop() = lock.withLock {
        running = false
        for (link in links.values) {
            link.waiter?.let { it.superseded = true; it.wake.complete(Unit) }
            link.waiter = null
            link.outbox.forEach { it.result.complete(RemoteSendOutcome.STOPPED) }
            link.outbox.clear()
            link.transfers.clear()
        }
        links.clear()
        changed()
    }

    /**
     * This process's own player as polling devices see it. [hasPlayer] is false for a process with
     * no GUI player, which then shows nothing. A change that is more than a position tick wakes
     * every held poll.
     */
    suspend fun updateHubStatus(status: RemoteStatus?, hasPlayer: Boolean, artwork: Pair<String, ByteArray>? = null) = lock.withLock {
        val now = clock()
        val shown = status.takeIf { hasPlayer }
        val material = hubHasPlayer != hasPlayer || hubStatus.isMaterialChange(shown, now - hubStatusAtMs)
        hubStatus = shown
        hubStatusAtMs = now
        hubHasPlayer = hasPlayer
        hubArtwork = artwork?.takeIf { hasPlayer && it.first == shown?.artworkKey }
        if (material) {
            hubVersion++
            links.values.forEach { it.waiter?.wake?.complete(Unit) }
        }
    }

    suspend fun poll(caller: PairedPeer, request: RemotePollRequest): RemoteHostReply<RemotePollReply> {
        if (request.deviceId != caller.deviceId) return RemoteHostReply.Refused(403, "The poll names another device")
        val startedAt = clock()
        val link: Link
        val waiter: Waiter
        lock.withLock {
            if (!running) return RemoteHostReply.Refused(503, "The hub is stopping")
            val now = clock()
            link = links.getOrPut(caller.deviceId, ::Link)
            if (request.linkId != link.linkId) {
                // A restarted device: its old process may have run what it fetched, so never offer that again.
                link.linkId = request.linkId
                link.generation = 0
                link.outbox.removeAll { out -> out.delivered.also { if (it) out.result.complete(RemoteSendOutcome.UNCONFIRMED) } }
            }
            if (request.generation <= link.generation) return RemoteHostReply.Ok(emptyReply(heldMs = 0))
            link.generation = request.generation
            link.waiter?.let { it.superseded = true; it.wake.complete(Unit) }
            link.waiter = null
            if (request.epoch == epoch) acknowledge(link, request.ackSeq, request.droppedSeqs.toSet())
            link.status = request.status
            link.statusAtMs = now
            expire(link, now)
            val ready = request.epoch != epoch || request.knownHubVersion != hubVersion || link.deliverable(now).isNotEmpty()
            if (ready) return RemoteHostReply.Ok(reply(link, heldMs = 0)).also { changed() }
            waiter = Waiter()
            link.waiter = waiter
            changed()
        }
        try {
            withTimeoutOrNull(holdMs) { waiter.wake.await() }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (link.waiter === waiter) {
                        link.waiter = null
                        link.lastReplyAtMs = clock()
                        changed()
                    }
                }
            }
        }
        return lock.withLock {
            val heldMs = clock() - startedAt
            if (waiter.superseded || !running) return@withLock RemoteHostReply.Ok(emptyReply(heldMs))
            expire(link, clock())
            RemoteHostReply.Ok(reply(link, heldMs))
        }
    }

    /** Queues [command] for [deviceId] and waits for its acknowledgement or expiry. */
    suspend fun send(deviceId: String, command: RemoteCommand): RemoteSendOutcome {
        val out = lock.withLock { enqueue(deviceId, command) ?: return refusal() }
        return awaitOutcome(deviceId, out)
    }

    /**
     * Asks [deviceId] to pause and hand over its queue ("Play here" on this side). Waits up to
     * [transferWaitMs] for the snapshot it posts back.
     */
    suspend fun requestTransfer(deviceId: String): RemoteHostTransfer {
        val received = CompletableDeferred<RemoteTransferReply>()
        val out = lock.withLock {
            val queued = enqueue(deviceId, RemoteCommand.PauseForTransfer) ?: return RemoteHostTransfer.Failed(refusal())
            links.getValue(deviceId).transfers[queued.seq] = received
            queued
        }
        try {
            val reply = withTimeoutOrNull(transferWaitMs) {
                select<RemoteTransferReply?> {
                    received.onAwait { it }
                    out.result.onAwait { outcome -> if (outcome == RemoteSendOutcome.DELIVERED) received.await() else null }
                }
            }
            if (reply != null) return RemoteHostTransfer.Received(reply)
            val outcome = settle(deviceId, out)
            return RemoteHostTransfer.Failed(if (outcome == RemoteSendOutcome.DELIVERED) RemoteSendOutcome.UNCONFIRMED else outcome)
        } finally {
            withContext(NonCancellable) { lock.withLock { links[deviceId]?.transfers?.remove(out.seq) } }
        }
    }

    /** The snapshot a device posts after it ran `PauseForTransfer` with [seq] in [epochId]. */
    suspend fun receiveTransfer(caller: PairedPeer, epochId: String, seq: Long, reply: RemoteTransferReply): RemoteHostReply<Unit> = lock.withLock {
        if (epochId != epoch) return@withLock RemoteHostReply.Refused(409, "The hub restarted")
        val waiting = links[caller.deviceId]?.transfers?.remove(seq)
            ?: return@withLock RemoteHostReply.Refused(409, "No transfer is waiting")
        waiting.complete(reply)
        RemoteHostReply.Ok(Unit)
    }

    /** Keeps only the latest artwork per device. */
    suspend fun receiveArtwork(caller: PairedPeer, key: String, bytes: ByteArray): RemoteHostReply<Unit> = lock.withLock {
        if (bytes.size > REMOTE_ARTWORK_MAX_BYTES) return@withLock RemoteHostReply.Refused(413, "Artwork too large")
        val link = links[caller.deviceId] ?: return@withLock RemoteHostReply.Refused(409, "The device has no link")
        link.artwork = key to bytes
        changed()
        RemoteHostReply.Ok(Unit)
    }

    suspend fun hubArtwork(key: String): ByteArray? = lock.withLock { hubArtwork?.takeIf { it.first == key }?.second }

    suspend fun peerArtwork(deviceId: String, key: String): ByteArray? =
        lock.withLock { links[deviceId]?.artwork?.takeIf { it.first == key }?.second }

    suspend fun peer(deviceId: String): RemotePeerView = lock.withLock {
        val link = links[deviceId]
        RemotePeerView(deviceId, link?.connected(clock()) == true, link?.status, link?.statusAtMs ?: 0L)
    }

    private fun enqueue(deviceId: String, command: RemoteCommand): Outgoing? {
        if (!running) return null
        val now = clock()
        val link = links[deviceId]?.takeIf { it.connected(now) } ?: return null
        val out = Outgoing(++link.nextSeq, command, now)
        link.outbox += out
        link.waiter?.wake?.complete(Unit)
        return out
    }

    private fun refusal() = if (running) RemoteSendOutcome.UNREACHABLE else RemoteSendOutcome.STOPPED

    private suspend fun awaitOutcome(deviceId: String, out: Outgoing): RemoteSendOutcome =
        withTimeoutOrNull(deadlineMs + ackGraceMs) { out.result.await() } ?: settle(deviceId, out)

    /** Ends [out] now: unconfirmed when it was fetched, otherwise not delivered. */
    private suspend fun settle(deviceId: String, out: Outgoing): RemoteSendOutcome {
        lock.withLock {
            links[deviceId]?.let { link -> expire(link, clock(), force = out) }
            out.result.complete(RemoteSendOutcome.NOT_DELIVERED)
        }
        return out.result.await()
    }

    private fun Link.connected(now: Long): Boolean =
        waiter != null || lastReplyAtMs?.let { now - it <= connectedGraceMs } == true

    private fun Link.deliverable(now: Long): List<Outgoing> = outbox.filter { now - it.queuedAtMs < deadlineMs }

    private fun acknowledge(link: Link, ackSeq: Long, dropped: Set<Long>) {
        link.outbox.removeAll { out ->
            (out.seq <= ackSeq).also { acked ->
                if (acked) out.result.complete(if (out.seq in dropped) RemoteSendOutcome.NOT_DELIVERED else RemoteSendOutcome.DELIVERED)
            }
        }
    }

    /**
     * An undelivered command expires at the deadline. A delivered one waits a little longer for its
     * acknowledgement, but is never offered again after the deadline.
     */
    private fun expire(link: Link, now: Long, force: Outgoing? = null) {
        link.outbox.removeAll { out ->
            val age = now - out.queuedAtMs
            val expired = out === force || if (out.delivered) age >= deadlineMs + ackGraceMs else age >= deadlineMs
            if (expired) out.result.complete(if (out.delivered) RemoteSendOutcome.UNCONFIRMED else RemoteSendOutcome.NOT_DELIVERED)
            expired
        }
    }

    private fun reply(link: Link, heldMs: Long): RemotePollReply {
        val now = clock()
        val commands = link.deliverable(now).map { out ->
            out.delivered = true
            RemoteEnvelope(out.seq, now - out.queuedAtMs, out.command)
        }
        link.lastReplyAtMs = now
        val status = hubStatus?.let { it.copy(positionAgeMs = it.positionAgeMs + (now - hubStatusAtMs).coerceAtLeast(0)) }
        return RemotePollReply(epoch, commands, status, hubVersion, hubName(), heldMs, hubHasPlayer)
    }

    private fun emptyReply(heldMs: Long) =
        RemotePollReply(epoch, emptyList(), null, hubVersion, hubName(), heldMs, hubHasPlayer, superseded = true)
}

const val REMOTE_CONNECTED_GRACE_MS = 3_000L

const val REMOTE_ACK_GRACE_MS = 1_000L

const val REMOTE_TRANSFER_WAIT_MS = 5_000L
