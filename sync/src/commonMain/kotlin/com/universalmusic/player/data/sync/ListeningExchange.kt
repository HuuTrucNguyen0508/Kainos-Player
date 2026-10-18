package com.universalmusic.player.data.sync

import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.ListeningPairingIdentity
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningEntry
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.PortableListeningTrack
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueHash
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.createPinnedHomeLanHttpClient
import com.universalmusic.player.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val HOME_LAN_SYNC_PATH_V3_LISTENING = "/kainos-sync/v3/listening"
const val HOME_LAN_SYNC_PATH_V3_QUEUE = "/kainos-sync/v3/queue"

@Serializable
data class ListeningReport(
    val deviceId: String,
    val revision: Long,
    val activeAgeMs: Long,
    val state: ListeningReportState? = null,
    /** A [ProjectionFailure] name; set exactly when [state] is null. */
    val unavailable: String? = null,
)

@Serializable
data class ListeningReportState(
    val queueHash: String,
    val current: PersistedQueueItem? = null,
    val positionMs: Long,
    val positionAgeMs: Long,
    val isPlaying: Boolean,
)

@Serializable
data class ListeningReply(val needQueue: Boolean, val hub: ListeningReport? = null)

fun LocalListeningExport.toReport(deviceId: String, nowMs: Long): ListeningReport = when (this) {
    is LocalListeningExport.Available -> {
        val state = publication.state
        ListeningReport(
            deviceId = deviceId,
            revision = publication.revision.value,
            activeAgeMs = ageMs(nowMs, publication.lastActiveAtMs),
            state = ListeningReportState(
                queueHash = state.queue.hash.hex,
                current = state.current?.let { PersistedQueueItem(it.itemId, it.track.track) },
                positionMs = state.positionMs,
                positionAgeMs = ageMs(nowMs, state.positionAtMs),
                isPlaying = state.isPlaying,
            ),
        )
    }
    is LocalListeningExport.Unavailable ->
        ListeningReport(deviceId, revision.value, ageMs(nowMs, lastActiveAtMs), unavailable = reason.name)
}

/** Files the report on the receiver's clock; null when the report is malformed. */
fun ListeningReport.toPeer(nowMs: Long): PeerListening? = runCatching {
    require(revision > 0 && activeAgeMs >= 0 && (state == null) != (unavailable == null))
    val peerState = state?.let { reported ->
        require(reported.positionMs >= 0 && reported.positionAgeMs >= 0)
        val current = reported.current?.let { item ->
            require(item.id.isNotBlank())
            PortableListeningEntry(item.id, requireNotNull(PortableListeningTrack.parse(item.track)))
        }
        PeerListeningState.Available(
            QueueHash.parse(reported.queueHash), current, reported.positionMs,
            nowMs - reported.positionAgeMs, reported.isPlaying,
        )
    } ?: PeerListeningState.Unavailable(ProjectionFailure.valueOf(unavailable!!))
    PeerListening(deviceId, PublicationRevision(revision), nowMs - activeAgeMs, peerState)
}.getOrNull()

private fun ageMs(nowMs: Long, atMs: Long): Long = (nowMs - atMs).coerceAtLeast(0)

data class HubAnswer(val status: Int, val body: String = "")

class ListeningExchangeHost(
    private val store: ListeningExchangeStore,
    private val hubDeviceId: String,
    private val clock: () -> Long = ::currentTimeMillis,
    private val json: Json = exchangeJson,
) {
    suspend fun report(caller: PairedPeer, body: String): HubAnswer {
        val report = runCatching { json.decodeFromString<ListeningReport>(body) }.getOrNull()
            ?: return HubAnswer(400, "Malformed listening report")
        if (report.deviceId != caller.deviceId) return HubAnswer(403, "The report names another device")
        val now = clock()
        val needQueue = if (caller.deviceId == hubDeviceId) false else {
            val peer = report.toPeer(now) ?: return HubAnswer(400, "Malformed listening report")
            val receipt = store.receivePeer(peer)
            if (receipt.stored) PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "listening from ${caller.displayName} ${peer.describe()}")
            receipt.needsQueue
        }
        val own = store.readLocalExport()?.toReport(hubDeviceId, now)
        return HubAnswer(200, json.encodeToString(ListeningReply(needQueue, own)))
    }

    suspend fun fetchListening(): HubAnswer =
        HubAnswer(200, json.encodeToString(ListeningReply(false, store.readLocalExport()?.toReport(hubDeviceId, clock()))))

    suspend fun uploadQueue(caller: PairedPeer, hashHex: String, body: String): HubAnswer {
        val queue = runCatching { PortableListeningQueue.decode(body, QueueHash.parse(hashHex)) }.getOrNull()
            ?: return HubAnswer(400, "Queue does not match its hash")
        if (caller.deviceId == hubDeviceId) return HubAnswer(204)
        if (!store.attachPeerQueue(caller.deviceId, queue)) return HubAnswer(409, "No listening state has this queue")
        PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "listening queue from ${caller.displayName} (${queue.entries.size} entries)")
        return HubAnswer(204)
    }

    suspend fun fetchQueue(hashHex: String): HubAnswer {
        val hash = runCatching { QueueHash.parse(hashHex) }.getOrNull() ?: return HubAnswer(400, "Malformed queue hash")
        val queue = store.readLocalQueue(hash) ?: return HubAnswer(404, "No queue with this hash")
        return HubAnswer(200, queue.encodeJson())
    }
}

private fun PeerListening.describe(): String = when (val s = state) {
    is PeerListeningState.Available ->
        "rev=${revision.value} track=${s.current?.track?.track?.title ?: "none"} playing=${s.isPlaying} position=${s.positionMs}"
    is PeerListeningState.Unavailable -> "rev=${revision.value} unavailable=${s.reason}"
}

/** The hub refused this device (401/403) or presented another certificate; retrying cannot help. */
class ListeningHubRefused(message: String) : IllegalStateException(message)

interface ListeningHubClient : AutoCloseable {
    suspend fun report(report: ListeningReport): ListeningReply

    suspend fun fetchListening(): ListeningReply

    /** False when the hub holds no state with this queue's hash any more. */
    suspend fun uploadQueue(queue: PortableListeningQueue): Boolean

    suspend fun fetchQueue(hash: QueueHash): PortableListeningQueue?
}

class HttpListeningHubClient(
    private val endpoint: HubEndpoint,
    private val http: HttpClient,
    private val json: Json = exchangeJson,
) : ListeningHubClient {
    private val base get() = "https://${endpoint.authority}"

    override fun close() = http.close()

    override suspend fun report(report: ListeningReport): ListeningReply {
        val response = request {
            http.post("$base$HOME_LAN_SYNC_PATH_V3_LISTENING") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(report))
            }
        }
        return json.decodeFromString(response.checked("Listening report").bodyAsText())
    }

    override suspend fun fetchListening(): ListeningReply {
        val response = request { http.get("$base$HOME_LAN_SYNC_PATH_V3_LISTENING") { expectSuccess = false } }
        return json.decodeFromString(response.checked("Listening fetch").bodyAsText())
    }

    override suspend fun uploadQueue(queue: PortableListeningQueue): Boolean {
        val response = request {
            http.put("$base$HOME_LAN_SYNC_PATH_V3_QUEUE/${queue.hash.hex}") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody(queue.encodeJson())
            }
        }
        if (response.status == HttpStatusCode.Conflict) return false
        response.checked("Queue upload")
        return true
    }

    override suspend fun fetchQueue(hash: QueueHash): PortableListeningQueue? {
        val response = request { http.get("$base$HOME_LAN_SYNC_PATH_V3_QUEUE/${hash.hex}") { expectSuccess = false } }
        if (response.status == HttpStatusCode.NotFound) return null
        return PortableListeningQueue.decode(response.checked("Queue fetch").bodyAsText(), hash)
    }

    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (err: Exception) {
        throw trustFailureOf(err)?.let(::ListeningHubRefused) ?: err
    }

    private suspend fun HttpResponse.checked(what: String): HttpResponse {
        if (status.isSuccess()) return this
        val detail = runCatching { bodyAsText() }.getOrDefault("").take(200)
        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
            throw ListeningHubRefused("$what refused: HTTP ${status.value} $detail")
        }
        error("$what failed: HTTP ${status.value} $detail")
    }
}

suspend fun openListeningHub(hub: PairedPeer, http: HttpClient, browser: HubBrowser): ListeningHubClient {
    val resolver = HubEndpointResolver(
        browser = browser,
        probe = { endpoint ->
            runCatching { http.get("https://${endpoint.authority}$HOME_LAN_SYNC_PATH_HEALTH") { expectSuccess = false }; Unit }
                .onFailure { err ->
                    if (err is CancellationException) throw err
                }
        },
        log = { PlaybackTrace.log(HOME_SYNC_TRACE_TAG, it) },
    )
    val stored = hub.lastKnownHost?.takeIf { it.isNotBlank() }?.let { HubEndpoint(it, hub.lastKnownPort) }
    val result = try {
        resolver.resolve(stored, hub.fingerprint.hex)
    } catch (err: Throwable) {
        http.close()
        throw err
    }
    return when (result) {
        is ResolveResult.Resolved -> HttpListeningHubClient(result.endpoint, http)
        is ResolveResult.HubNotFound -> {
            http.close()
            throw IllegalStateException(result.reason)
        }
    }
}

suspend fun openPairedListeningHub(hub: PairedPeer): ListeningHubClient =
    openListeningHub(hub, createPinnedHomeLanHttpClient(hub.fingerprint), platformHubBrowser())

data class DeliveryTarget(val hub: PairedPeer, val deviceId: String, val ownFingerprint: CertFingerprint) {
    val pairingIdentity get() = ListeningPairingIdentity(hub.deviceId, hub.fingerprint.hex, deviceId, ownFingerprint.hex, hub.pairedAtMs)
}

fun AppSettings.listeningDeliveryTarget(isDesktop: Boolean, ownFingerprint: CertFingerprint): DeliveryTarget? {
    if (!homeLanSyncEnabled || homeLanSyncIdentityResetPending) return null
    val client = when (homeLanSyncRole) {
        HomeLanSyncRole.CLIENT -> true
        HomeLanSyncRole.HUB -> false
        HomeLanSyncRole.AUTO -> !isDesktop
    }
    if (!client) return null
    return DeliveryTarget(homeLanSyncHub ?: return null, homeLanSyncDeviceId ?: return null, ownFingerprint)
}

enum class DeliveryOutcome {
    /** Nothing pending, or this device has no hub to deliver to. */
    IDLE,
    DELIVERED,
    /** The hub was unreachable or failed; the export stays pending. */
    RETRY,
    /** The hub no longer trusts this device; only pairing again helps. */
    REFUSED,
}

private val processDeliveryLock = Mutex()

class ListeningDelivery(
    private val store: ListeningExchangeStore,
    private val target: suspend () -> DeliveryTarget?,
    private val connect: suspend (PairedPeer) -> ListeningHubClient,
    private val clock: () -> Long = ::currentTimeMillis,
    private val mutex: Mutex = processDeliveryLock,
    private val onRefused: () -> Unit = {},
) {
    suspend fun canDeliverPending(): Boolean {
        val current = target() ?: return false
        return store.refusedPairing() != current.pairingIdentity && pendingRevision(current.hub) != null
    }

    suspend fun deliverPending(exchangeOnForeground: Boolean = false): DeliveryOutcome = mutex.withLock {
        var outcome = DeliveryOutcome.IDLE
        repeat(MAX_PASSES) { passIndex ->
            when (val next = pass(exchangeOnForeground && passIndex == 0)) {
                DeliveryOutcome.IDLE -> return@withLock outcome
                DeliveryOutcome.DELIVERED -> outcome = next
                else -> return@withLock next
            }
        }
        DeliveryOutcome.RETRY
    }

    private suspend fun pendingRevision(hub: PairedPeer): LocalListeningExport? {
        val export = store.readLocalExport() ?: return null
        val delivered = store.deliveredRevision(hub.deviceId)?.value ?: 0L
        return export.takeIf { it.revision().value > delivered }
    }

    private suspend fun pass(exchangeOnForeground: Boolean): DeliveryOutcome {
        return try {
            val current = target() ?: return DeliveryOutcome.IDLE
            val refused = store.refusedPairing()
            if (refused == current.pairingIdentity) {
                onRefused()
                return DeliveryOutcome.REFUSED
            }
            if (refused != null) store.setRefusedPairing(null)
            val export = if (exchangeOnForeground) store.readLocalExport() else pendingRevision(current.hub)
            try {
                if (export == null && !exchangeOnForeground) DeliveryOutcome.IDLE
                else connect(current.hub).use { client ->
                    if (export == null) {
                        receiveReply(client, client.fetchListening(), current)
                        DeliveryOutcome.IDLE
                    } else exchange(client, current, export)
                }
            } catch (refused: ListeningHubRefused) {
                store.setRefusedPairing(current.pairingIdentity)
                onRefused()
                PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "listening delivery refused: ${refused.message}")
                DeliveryOutcome.REFUSED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "listening delivery failed: ${err.message ?: err}")
            DeliveryOutcome.RETRY
        }
    }

    private suspend fun receiveReply(client: ListeningHubClient, reply: ListeningReply, current: DeliveryTarget) {
        reply.hub?.takeIf { it.deviceId == current.hub.deviceId }?.toPeer(clock())?.let { receiveHub(client, it) }
    }

    private suspend fun exchange(client: ListeningHubClient, current: DeliveryTarget, export: LocalListeningExport): DeliveryOutcome {
        val revision = export.revision()
        repeat(MAX_ROUNDS) {
            val reply = client.report(export.toReport(current.deviceId, clock()))
            receiveReply(client, reply, current)
            if (!reply.needQueue) {
                store.markDelivered(current.hub.deviceId, revision)
                PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "listening rev=${revision.value} delivered to ${current.hub.displayName}")
                return DeliveryOutcome.DELIVERED
            }
            val available = export as? LocalListeningExport.Available ?: error("The hub asked for a queue of an unavailable state")
            client.uploadQueue(available.publication.state.queue)
        }
        error("The hub still lacks the queue after $MAX_ROUNDS reports")
    }

    private suspend fun receiveHub(client: ListeningHubClient, peer: PeerListening) {
        if (!store.receivePeer(peer).needsQueue) return
        val hash = (peer.state as? PeerListeningState.Available)?.queueHash ?: return
        try {
            client.fetchQueue(hash)?.let { store.attachPeerQueue(peer.deviceId, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "hub listening queue fetch failed: ${err.message ?: err}")
        }
    }

    private companion object {
        const val MAX_PASSES = 4
        const val MAX_ROUNDS = 3
    }
}

private fun LocalListeningExport.revision(): PublicationRevision = when (this) {
    is LocalListeningExport.Available -> publication.revision
    is LocalListeningExport.Unavailable -> revision
}

class ListeningDeliveryRunner(
    scope: CoroutineScope,
    private val delivery: ListeningDelivery,
    private val retryInProcess: () -> Boolean,
    private val scheduleBackground: suspend () -> Unit,
    private val retryMs: Long = LISTENING_RETRY_MS,
    private val isAppForeground: () -> Boolean,
    private val onForegroundExchangeCompleted: () -> Unit = {},
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val foregroundRequested = MutableStateFlow(false)
    private val job = scope.launch(start = CoroutineStart.LAZY) { run() }

    fun request(exchangeOnForeground: Boolean = false) {
        if (exchangeOnForeground) foregroundRequested.value = true
        requests.trySend(Unit)
        job.start()
    }

    private suspend fun run() {
        var retrying = false
        while (true) {
            if (retrying && retryInProcess()) withTimeoutOrNull(retryMs) { requests.receive() } else requests.receive()
            val exchangeOnForeground = foregroundRequested.getAndUpdate { false }
            retrying = try {
                if (!isAppForeground() && delivery.canDeliverPending()) scheduleBackground()
                delivery.deliverPending(exchangeOnForeground) == DeliveryOutcome.RETRY && delivery.canDeliverPending()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "listening runner failed: ${err.message ?: err}")
                true
            }
            if (exchangeOnForeground && !foregroundRequested.value) onForegroundExchangeCompleted()
        }
    }
}

const val LISTENING_RETRY_MS = 30_000L

private val exchangeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
