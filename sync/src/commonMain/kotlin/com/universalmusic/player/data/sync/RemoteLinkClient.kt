package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.createPinnedHomeLanHttpClient
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.readLocalArtworkBytes
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.TimeMark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout

/** One connection to the hub's remote routes; [poll] may take the whole hold. */
interface RemoteLinkTransport : AutoCloseable {
    suspend fun poll(request: RemotePollRequest): RemotePollReply

    /** Throws [RemoteHubHasNoPlayer] when the hub process has no player (`--hub-only`). */
    suspend fun command(command: RemoteCommand): RemoteCommandReply

    /** Called only after a new hub advertises a lease. Old/test transports keep legacy behavior. */
    suspend fun intent(intent: RemoteDirectIntent): RemoteCommandReply = command(intent.command)

    suspend fun postTransfer(epoch: String, seq: Long, reply: RemoteTransferReply)

    suspend fun putArtwork(key: String, bytes: ByteArray)

    /** Null when the hub has no artwork under [key]. */
    suspend fun fetchArtwork(key: String): ByteArray?
}

class RemoteHubHasNoPlayer : IllegalStateException("The PC is running Home sync without the player")

/** The hub as this device sees it. [hubStatusAtMs] is this device's clock when the status arrived. */
data class RemoteLinkState(
    /** True while the link runs: Home sync is on, this device is a paired client, and the link is wanted. */
    val active: Boolean = false,
    val connected: Boolean = false,
    /** The last poll or command failed; cleared by the next successful poll. */
    val unreachable: Boolean = false,
    val hubName: String? = null,
    val hubHasPlayer: Boolean = false,
    val hubStatus: RemoteStatus? = null,
    val hubStatusAtMs: Long = 0,
)

sealed interface RemoteClientSend {
    data class Applied(val reply: RemoteCommandReply) : RemoteClientSend
    data object NoPlayer : RemoteClientSend
    data object Unreachable : RemoteClientSend
}

/**
 * The polling half of the remote link, run by the device that is not the hub. Polling and command
 * execution are separate: one serialized executor applies each command under [NonCancellable] and
 * advances [lastExecuted] with it, so cancelling a poll to post a fresh status never runs a command
 * twice or loses one already applied.
 */
class RemoteLinkClient(
    private val scope: CoroutineScope,
    private val target: () -> DeliveryTarget?,
    private val connect: suspend (PairedPeer) -> RemoteLinkTransport,
    private val player: RemotePlayer,
    private val artwork: RemoteArtworkReader = RemoteArtworkReader(),
    private val onConnected: () -> Unit = {},
    private val clock: () -> Long = ::currentTimeMillis,
    private val backoffMs: List<Long> = REMOTE_BACKOFF_MS,
    private val repostGapMs: Long = REMOTE_REPOST_GAP_MS,
    private val linkId: String = Random.nextLong().toULong().toString(36),
    private val monotonicClock: TimeSource = TimeSource.Monotonic,
) {
    private val _state = MutableStateFlow(RemoteLinkState())
    val state: StateFlow<RemoteLinkState> = _state.asStateFlow()

    private val execution = Mutex()
    private var epoch: String? = null
    private var lastExecuted = 0L
    private var knownHubVersion = -1L
    private val dropped = ArrayList<Long>()

    private var generation = 0L
    private var failures = 0
    private val reconnect = Channel<Unit>(Channel.CONFLATED)
    private var latestIntent = 0L
    private var forcePollStatus = false
    private var directLease: String? = null
    private data class StatusIntent(val playing: Boolean, val started: TimeMark)
    private var statusIntent: StatusIntent? = null

    fun reconnectNow() { forcePollStatus = true; reconnect.trySend(Unit) }
    private suspend fun backoff(wait: Long) { withTimeoutOrNull(wait) { reconnect.receive() } }


    @kotlin.concurrent.Volatile
    private var transport: RemoteLinkTransport? = null
    private var uploadedArtworkKey: String? = null

    /** Runs the link while [wanted] is true and stops it, closing the poll, when it turns false. */
    fun run(wanted: Flow<Boolean>): Job = scope.launch {
        wanted.distinctUntilChanged().collectLatest { on ->
            if (on) {
                try {
                    _state.update { it.copy(active = true) }
                    link()
                } finally {
                    _state.value = RemoteLinkState()
                }
            }
        }
    }

    /** Only explicit Play/Pause may retry, under a receiver-issued ordering and expiry lease. */
    suspend fun send(command: RemoteCommand): RemoteClientSend {
        val explicit = command == RemoteCommand.Play || command == RemoteCommand.Pause
        val intent = if (explicit) ++latestIntent else 0L
        // Capture before the press is dispatched: a later-issued lease could extend its deadline.
        val lease = directLease
        val started = monotonicClock.markNow()
        if (explicit) statusIntent = StatusIntent(command == RemoteCommand.Play, started)
        return try {
            val reply = withTimeout(if (explicit) REMOTE_COMMAND_DEADLINE_MS else REMOTE_COMMAND_REQUEST_TIMEOUT_MS) {
                if (explicit && !_state.value.connected) {
                    if (!_state.value.active) return@withTimeout null
                    reconnectNow()
                    state.first { it.connected || !it.active }
                }
                if (explicit && intent != latestIntent) return@withTimeout null
                val current = transport?.takeIf { _state.value.connected } ?: return@withTimeout null
                if (!explicit || lease == null) current.command(command) else {
                    while (true) {
                        if (intent != latestIntent) return@withTimeout null
                        try {
                            return@withTimeout (transport ?: current).intent(RemoteDirectIntent(command, linkId, intent, started.elapsedNow().inWholeMilliseconds, lease))
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (noPlayer: RemoteHubHasNoPlayer) { throw noPlayer
                        } catch (err: Exception) {
                            // Retry only explicit intents and only with receiver-proven ordering/expiry.
                            delay(100)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
            }
            if (reply == null) return RemoteClientSend.Unreachable
            if (!explicit || intent == latestIntent) reply.status?.let { status -> _state.update { it.copy(hubStatus = status, hubStatusAtMs = clock()) } }
            RemoteClientSend.Applied(reply)
        } catch (cancelled: CancellationException) {
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) unreachable("command timed out") else throw cancelled
        } catch (_: RemoteHubHasNoPlayer) {
            _state.update { it.copy(hubHasPlayer = false, hubStatus = null) }
            RemoteClientSend.NoPlayer
        } catch (err: Exception) {
            unreachable(err.message ?: err.toString())
        }
    }

    suspend fun fetchHubArtwork(key: String): ByteArray? {
        val current = transport ?: return null
        return try {
            withTimeout(REMOTE_ARTWORK_REQUEST_TIMEOUT_MS) { current.fetchArtwork(key) }
        } catch (cancelled: CancellationException) {
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) null else throw cancelled
        } catch (err: Exception) {
            trace("hub artwork fetch failed: ${err.message ?: err}")
            null
        }
    }

    private fun unreachable(reason: String): RemoteClientSend {
        trace("command failed: $reason")
        _state.update { it.copy(connected = false, unreachable = true) }
        return RemoteClientSend.Unreachable
    }

    private suspend fun link() {
        while (true) {
            val current = target()
            if (current == null) {
                _state.update { it.copy(connected = false) }
                backoff(backoffMs.last())
                continue
            }
            try {
                connect(current.hub).use { opened ->
                    transport = opened
                    try {
                        polls(opened, current)
                    } finally {
                        transport = null
                    }
                }
            } catch (err: Exception) {
                currentCoroutineContext().ensureActive()
                if (err is CancellationException && err !is TimeoutCancellationException) throw err
                val wait = backoffMs[failures.coerceAtMost(backoffMs.lastIndex)]
                failures++
                trace("link to ${current.hub.displayName} failed (${err.message ?: err}); retry in $wait ms")
                _state.update { it.copy(connected = false, unreachable = true) }
                backoff(wait)
            }
        }
    }

    private suspend fun polls(opened: RemoteLinkTransport, current: DeliveryTarget): Unit = coroutineScope {
        // Cover uploads must not leave the phone without a poll or hold up an acknowledgement.
        // One worker bounds uploads; newer covers replace pending ones. It closes with this link.
        val covers = Channel<Pair<String, RemotePlayerState?>>(Channel.CONFLATED)
        launch {
            var artworkEpoch: String? = null
            for ((nextEpoch, state) in covers) {
                if (nextEpoch != artworkEpoch) {
                    uploadedArtworkKey = null
                    artworkEpoch = nextEpoch
                }
                uploadArtwork(opened, state)
            }
        }
        while (true) {
            val sent = player.state.value
            val observingIntent = latestIntent
            val request = execution.withLock {
                RemotePollRequest(
                    deviceId = current.deviceId,
                    linkId = linkId,
                    generation = ++generation,
                    epoch = epoch,
                    ackSeq = lastExecuted,
                    knownHubVersion = if (forcePollStatus) -1 else knownHubVersion,
                    status = artwork.wireStatus(sent, clock()),
                    droppedSeqs = dropped.toList(),
                )
            }
            val sentAt = monotonicClock.markNow()
            val reply = pollUntilLocalChange(opened, request, sent)
            if (reply == null) {
                val wait = repostGapMs - sentAt.elapsedNow().inWholeMilliseconds
                if (wait > 0) delay(wait)
                continue
            }
            if (request.knownHubVersion == -1L && observingIntent == latestIntent) forcePollStatus = false
            directLease = reply.directIntentLease
            val wasConnected = _state.value.connected
            failures = 0
            if (!reply.superseded) {
                val transit = (sentAt.elapsedNow().inWholeMilliseconds - reply.heldMs).coerceAtLeast(0)
                val transfers = execute(reply, transit, request)
                val pendingStatus = statusIntent
                val freshStatus = observingIntent == latestIntent && (pendingStatus == null ||
                    pendingStatus.started.elapsedNow().inWholeMilliseconds >= REMOTE_COMMAND_DEADLINE_MS ||
                    reply.hubStatus?.let { (it.isPlaying || it.buffering) == pendingStatus.playing } == true)
                if (freshStatus) statusIntent = null
                _state.update {
                    it.copy(
                        connected = true,
                        unreachable = false,
                        hubName = reply.hubName,
                        hubHasPlayer = reply.hubHasPlayer,
                        hubStatus = if (freshStatus) reply.hubStatus else it.hubStatus,
                        hubStatusAtMs = if (freshStatus) clock() else it.hubStatusAtMs,
                    )
                }
                for ((seq, transfer) in transfers) postTransfer(opened, reply.epoch, seq, transfer)
            } else {
                _state.update { it.copy(connected = true, unreachable = false) }
            }
            if (!wasConnected) {
                trace("link to ${current.hub.displayName} connected")
                onConnected()
            }
            covers.trySend(reply.epoch to player.state.value)
        }
    }

    /** The hub's reply, or null when this device's status changed first and the poll was abandoned. */
    private suspend fun pollUntilLocalChange(
        opened: RemoteLinkTransport,
        request: RemotePollRequest,
        sent: RemotePlayerState?,
    ): RemotePollReply? = coroutineScope {
        val call = async { withTimeout(REMOTE_POLL_REQUEST_TIMEOUT_MS) { opened.poll(request) } }
        val change = async {
            player.state.first { next -> sent?.status.isMaterialChange(next?.status, elapsedSince(sent, next)) }
        }
        // Refresh a receiver lease before it expires, even when neither player changes.
        val refresh = async { if (directLease != null) delay(2_000) else awaitCancellation() }
        val wake = async { reconnect.receive() }
        select<RemotePollReply?> {
            call.onAwait { reply -> change.cancel(); refresh.cancel(); wake.cancel(); reply }
            change.onAwait { call.cancel(); refresh.cancel(); wake.cancel(); null }
            refresh.onAwait { call.cancel(); change.cancel(); wake.cancel(); null }
            wake.onAwait { call.cancel(); change.cancel(); refresh.cancel(); null }
        }
    }

    private fun elapsedSince(sent: RemotePlayerState?, next: RemotePlayerState?): Long =
        if (sent == null || next == null) 0 else next.capturedAtMs - sent.capturedAtMs

    /**
     * Runs [reply]'s commands in sequence order, each at most once. A command older than the
     * deadline after transit and session-thread waiting is skipped and reported as dropped.
     */
    private suspend fun execute(
        reply: RemotePollReply,
        transitMs: Long,
        request: RemotePollRequest,
    ): List<Pair<Long, RemoteTransferReply>> = withContext(NonCancellable) {
        val receivedAt = monotonicClock.markNow()
        execution.withLock {
            // The hub processed this request, so it has the drops it carried.
            dropped.removeAll(request.droppedSeqs.toSet())
            if (reply.epoch != epoch) {
                if (epoch != null) trace("hub epoch changed; command history reset")
                epoch = reply.epoch
                lastExecuted = 0
                dropped.clear()
            }
            knownHubVersion = reply.hubVersion
            val transfers = ArrayList<Pair<Long, RemoteTransferReply>>()
            for (envelope in reply.commands.sortedBy { it.seq }) {
                if (envelope.seq <= lastExecuted) continue
                val deadline = receivedAt + (REMOTE_COMMAND_DEADLINE_MS - envelope.ageMs - transitMs).milliseconds
                val applied = try {
                    if (deadline.hasPassedNow()) throw RemoteCommandExpired()
                    player.apply(envelope.command, deadline)
                } catch (_: RemoteCommandExpired) {
                    trace("dropped late ${envelope.command::class.simpleName} seq=${envelope.seq}")
                    dropped += envelope.seq
                    continue
                } catch (err: Exception) {
                    trace("command ${envelope.command::class.simpleName} failed: ${err.message ?: err}")
                    null
                } finally {
                    lastExecuted = envelope.seq
                }
                if (envelope.command == RemoteCommand.PauseForTransfer) {
                    transfers += envelope.seq to (applied?.transfer ?: RemoteTransferReply(failure = RemoteTransferFailure.EMPTY))
                }
            }
            transfers
        }
    }

    private suspend fun postTransfer(opened: RemoteLinkTransport, epoch: String, seq: Long, reply: RemoteTransferReply) {
        try {
            withTimeout(REMOTE_COMMAND_REQUEST_TIMEOUT_MS) { opened.postTransfer(epoch, seq, reply) }
        } catch (cancelled: CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
            trace("transfer post timed out")
        } catch (err: Exception) {
            trace("transfer post failed: ${err.message ?: err}")
        }
    }

    /** Uploads this device's local cover once per key; the hub keeps only the latest. */
    private suspend fun uploadArtwork(opened: RemoteLinkTransport, state: RemotePlayerState?) {
        val key = state?.status?.artworkKey ?: return
        if (key == uploadedArtworkKey) return
        val bytes = artwork.bytesFor(state.localArtwork) ?: return
        try {
            withTimeout(REMOTE_ARTWORK_REQUEST_TIMEOUT_MS) { opened.putArtwork(key, bytes) }
            uploadedArtworkKey = key
        } catch (cancelled: CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
        } catch (err: Exception) {
            trace("artwork upload failed: ${err.message ?: err}")
        }
    }

    private fun trace(message: String) = PlaybackTrace.log(REMOTE_TRACE_TAG, message)
}

/**
 * Reads local cover bytes for the artwork route, remembering the latest location. A cover larger
 * than [REMOTE_ARTWORK_MAX_BYTES] or unreadable sends no key, so the other side shows its placeholder.
 */
class RemoteArtworkReader(
    private val read: suspend (location: String) -> ByteArray? = { readLocalArtworkBytes(it, REMOTE_ARTWORK_MAX_BYTES) },
) {
    private val lock = Mutex()
    private var cached: Pair<String, ByteArray?>? = null

    suspend fun bytesFor(location: String?): ByteArray? {
        location ?: return null
        return lock.withLock {
            cached?.takeIf { it.first == location }?.let { return@withLock it.second }
            val bytes = try {
                read(location)?.takeIf { it.size <= REMOTE_ARTWORK_MAX_BYTES }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            cached = location to bytes
            bytes
        }
    }

    /** The status to send at [nowMs], without its artwork key when the bytes cannot be read. */
    suspend fun wireStatus(state: RemotePlayerState?, nowMs: Long): RemoteStatus? {
        state ?: return null
        val wire = state.toWire(nowMs)
        if (wire.artworkKey == null || bytesFor(state.localArtwork) != null) return wire
        return wire.copy(artworkKey = null)
    }
}

/** Opens a pinned connection to [hub] with the stored address, then mDNS. */
suspend fun openPairedRemoteLink(hub: PairedPeer): RemoteLinkTransport {
    val http = createPinnedHomeLanHttpClient(hub.fingerprint)
    val endpoint = try {
        resolvePairedHub(hub, http, platformHubBrowser())
    } catch (err: Throwable) {
        http.close()
        throw err
    }
    return HttpRemoteLinkTransport(endpoint, http)
}

/** Longer than the hub's hold, so a held poll returns before the client gives up on it. */
const val REMOTE_POLL_REQUEST_TIMEOUT_MS = 25_000L

const val REMOTE_COMMAND_REQUEST_TIMEOUT_MS = 5_000L

const val REMOTE_ARTWORK_REQUEST_TIMEOUT_MS = 10_000L

const val REMOTE_REPOST_GAP_MS = 250L

// A single fast reconnect recovers a brief LAN failure inside the command deadline.
// Sustained failures still back off to 30 seconds. Poll seq/epoch dedupe is unchanged.
val REMOTE_BACKOFF_MS = listOf(200L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L)

internal const val REMOTE_TRACE_TAG = "Remote"
