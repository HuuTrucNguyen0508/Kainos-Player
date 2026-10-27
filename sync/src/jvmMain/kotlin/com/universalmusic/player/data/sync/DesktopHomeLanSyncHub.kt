package com.universalmusic.player.data.sync

import com.universalmusic.player.data.playlist.PlaylistsSyncDocument
import com.universalmusic.player.data.playlist.forV1Sync
import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.PlaybackTrace
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.netty.handler.ssl.SslHandler
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopHomeLanSyncHub(
    private val config: HomeLanHubConfig,
    private val identity: JvmDeviceIdentity.Material,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val bindHost: String = "0.0.0.0",
    clock: () -> Long = System::currentTimeMillis,
) : HomeLanSyncHub {
    private var server: EmbeddedServer<*, *>? = null
    @Volatile
    private var listening = false
    @Volatile
    private var lastActivityMs: Long = System.currentTimeMillis()
    private val vaultMutex = Mutex()
    private val self = PairedPeer(config.deviceId, "This PC", identity.fingerprint, pairedAtMs = 0L)
    private val vault get() = config.vault
    private val listeningExchange = config.listening?.let { ListeningExchangeHost(it, config.deviceId, clock) }

    override val isListening: Boolean get() = listening

    override fun touchActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    override fun lastActivityAtMs(): Long = lastActivityMs

    override suspend fun start() {
        if (listening) return
        val serverSsl = hubServerSslContext(identity)
        // Netty (not CIO): Ktor CIO server engine does not support HTTPS/sslConnector.
        val engine = embeddedServer(
            Netty,
            environment = applicationEnvironment {},
            configure = {
                // With HTTP/2, ALPN builds the pipeline after the handshake, so the swap below would
                // run too late and every client would arrive without a certificate.
                enableHttp2 = false
                sslConnector(
                    keyStore = identity.keyStore,
                    keyAlias = JvmDeviceIdentity.KEY_ALIAS,
                    keyStorePassword = { identity.password() },
                    privateKeyPassword = { identity.password() },
                ) {
                    port = config.port
                    host = bindHost
                }
                // Ktor's own handler only offers PKIX client auth, which rejects self-signed devices.
                channelPipelineConfig = {
                    val ktorSsl = get("ssl") as? SslHandler
                    if (ktorSsl != null && !ktorSsl.handshakeFuture().isDone) {
                        val started = System.nanoTime()
                        val ssl = serverSsl.newHandler(channel().alloc())
                        ssl.handshakeFuture().addListener { result ->
                            val elapsed = (System.nanoTime() - started) / 1_000_000
                            val outcome = if (result.isSuccess) "complete protocol=${ssl.engine().session.protocol}"
                                else "failed ${result.cause()?.javaClass?.name}: ${result.cause()?.message}"
                            PlaybackTrace.log("HomeSync", "hub TLS $outcome in $elapsed ms remote=${channel().remoteAddress()}")
                        }
                        replace("ssl", "ssl", ssl)
                    }
                }
            },
        ) {
            routing {
                get(HOME_LAN_SYNC_PATH_HEALTH) {
                    call.pairedPeer() ?: return@get
                    touchActivity()
                    call.respondText(
                        json.encodeToString(
                            HomeLanHealthResponse(
                                deviceId = config.deviceId,
                                vaultConfigured = vault?.isConfigured() == true,
                            ),
                        ),
                        ContentType.Application.Json,
                    )
                }
                post(HOME_LAN_SYNC_PATH_PAIR_START) {
                    PlaybackTrace.log("HomeSync", "hub pair/start received")
                    val client = call.pairingClient() ?: return@post
                    val request = call.receivePairing<PairStartRequest>() ?: return@post
                    call.respondPairing(config.pairing.start(client, request))
                }
                post(HOME_LAN_SYNC_PATH_PAIR_REVEAL) {
                    val client = call.pairingClient() ?: return@post
                    val request = call.receivePairing<PairRevealRequest>() ?: return@post
                    call.respondPairing(config.pairing.reveal(client, request))
                }
                post(HOME_LAN_SYNC_PATH_PAIR_CONFIRM) {
                    val client = call.pairingClient() ?: return@post
                    val request = call.receivePairing<PairAttemptRequest>() ?: return@post
                    call.respondPairing(config.pairing.confirmFromPhone(client, request.attemptId))
                }
                post(HOME_LAN_SYNC_PATH_PAIR_CANCEL) {
                    val client = call.pairingClient() ?: return@post
                    val request = call.receivePairing<PairAttemptRequest>() ?: return@post
                    call.respondPairing(config.pairing.cancel(client, request.attemptId))
                }
                get(HOME_LAN_SYNC_PATH_PAIR_STATUS) {
                    val client = call.pairingClient() ?: return@get
                    val attemptId = call.request.queryParameters["attemptId"].orEmpty()
                    call.respondPairing(config.pairing.status(client, attemptId))
                }
                listeningExchange?.let { host ->
                    get(HOME_LAN_SYNC_PATH_V3_LISTENING) {
                        call.pairedPeer() ?: return@get
                        touchActivity()
                        call.respondAnswer(host.fetchListening(), ContentType.Application.Json)
                    }
                    post(HOME_LAN_SYNC_PATH_V3_LISTENING) {
                        val peer = call.pairedPeer() ?: return@post
                        touchActivity()
                        val body = call.receiveOrRespondTooLarge(MAX_LISTENING_BODY_BYTES) ?: return@post
                        call.respondAnswer(host.report(peer, body), ContentType.Application.Json)
                    }
                    put("$HOME_LAN_SYNC_PATH_V3_QUEUE/{hash}") {
                        val peer = call.pairedPeer() ?: return@put
                        touchActivity()
                        val body = call.receiveOrRespondTooLarge(MAX_QUEUE_BODY_BYTES) ?: return@put
                        call.respondAnswer(host.uploadQueue(peer, call.parameters["hash"].orEmpty(), body), ContentType.Text.Plain)
                    }
                    get("$HOME_LAN_SYNC_PATH_V3_QUEUE/{hash}") {
                        call.pairedPeer() ?: return@get
                        touchActivity()
                        call.respondAnswer(host.fetchQueue(call.parameters["hash"].orEmpty()), ContentType.Application.Json)
                    }
                }
                config.remote?.let { host ->
                    post(HOME_LAN_SYNC_PATH_V3_REMOTE_POLL) {
                        val peer = call.pairedPeer() ?: return@post
                        val body = call.receiveOrRespondTooLarge(MAX_REMOTE_BODY_BYTES) ?: return@post
                        val request = runCatching { json.decodeFromString<RemotePollRequest>(body) }.getOrNull()
                            ?: return@post call.respondText("Malformed poll", status = HttpStatusCode.BadRequest)
                        call.respondRemote(host.poll(peer, request)) { json.encodeToString(it) }
                    }
                    post(HOME_LAN_SYNC_PATH_V3_REMOTE_COMMAND) {
                        val deadline = TimeSource.Monotonic.markNow() + REMOTE_COMMAND_REQUEST_TIMEOUT_MS.milliseconds
                        call.pairedPeer() ?: return@post
                        touchActivity()
                        val player = config.guiPlayer()
                            ?: return@post call.respondText("This hub has no player", status = HttpStatusCode.Conflict)
                        val body = call.receiveOrRespondTooLarge(MAX_REMOTE_BODY_BYTES) ?: return@post
                        val command = runCatching { json.decodeFromString<RemoteCommand>(body) }.getOrNull()
                            ?: return@post call.respondText("Malformed command", status = HttpStatusCode.BadRequest)
                        val reply = try {
                            player.apply(command, deadline)
                        } catch (_: RemoteCommandExpired) {
                            return@post call.respondText("Command expired", status = HttpStatusCode.RequestTimeout)
                        }
                        call.respondText(json.encodeToString(reply), ContentType.Application.Json)
                    }
                    post("$HOME_LAN_SYNC_PATH_V3_REMOTE_TRANSFER/{epoch}/{seq}") {
                        val peer = call.pairedPeer() ?: return@post
                        touchActivity()
                        val seq = call.parameters["seq"]?.toLongOrNull()
                            ?: return@post call.respondText("Malformed sequence", status = HttpStatusCode.BadRequest)
                        val body = call.receiveOrRespondTooLarge(MAX_QUEUE_BODY_BYTES) ?: return@post
                        val reply = runCatching { json.decodeFromString<RemoteTransferReply>(body) }.getOrNull()
                            ?: return@post call.respondText("Malformed transfer", status = HttpStatusCode.BadRequest)
                        call.respondRemote(host.receiveTransfer(peer, call.parameters["epoch"].orEmpty(), seq, reply)) { "" }
                    }
                    put("$HOME_LAN_SYNC_PATH_V3_REMOTE_ARTWORK/{key}") {
                        val peer = call.pairedPeer() ?: return@put
                        val bytes = call.receiveBytesOrRespondTooLarge(REMOTE_ARTWORK_MAX_BYTES.toLong()) ?: return@put
                        call.respondRemote(host.receiveArtwork(peer, call.parameters["key"].orEmpty(), bytes)) { "" }
                    }
                    get("$HOME_LAN_SYNC_PATH_V3_REMOTE_ARTWORK/{key}") {
                        call.pairedPeer() ?: return@get
                        val bytes = host.hubArtwork(call.parameters["key"].orEmpty())
                            ?: return@get call.respondText("No artwork", status = HttpStatusCode.NotFound)
                        call.respondBytes(bytes, ContentType.Application.OctetStream)
                    }
                }
                for (path in listOf(HOME_LAN_SYNC_PATH_HEARTS, HOME_LAN_SYNC_PATH_V2_HEARTS)) {
                    post(path) {
                        call.pairedPeer() ?: return@post
                        touchActivity()
                        val body = call.receiveText()
                        val remote = json.decodeFromString<HeartsSyncDocument>(body)
                        val localAfter = config.onHearts(remote)
                        call.respondText(
                            json.encodeToString(if (path == HOME_LAN_SYNC_PATH_HEARTS) localAfter.forV1Sync() else localAfter),
                            ContentType.Application.Json,
                        )
                    }
                }
                for (path in listOf(HOME_LAN_SYNC_PATH_PLAYLISTS, HOME_LAN_SYNC_PATH_V2_PLAYLISTS)) {
                    post(path) {
                        call.pairedPeer() ?: return@post
                        touchActivity()
                        val body = call.receiveText()
                        val remote = json.decodeFromString<PlaylistsSyncDocument>(body)
                        val localAfter = config.onPlaylists(remote)
                        call.respondText(
                            json.encodeToString(if (path == HOME_LAN_SYNC_PATH_PLAYLISTS) localAfter.forV1Sync() else localAfter),
                            ContentType.Application.Json,
                        )
                    }
                }
                for (path in listOf(HOME_LAN_SYNC_PATH_VAULT_INDEX, HOME_LAN_SYNC_PATH_V2_VAULT_INDEX)) {
                    post(path) {
                        call.pairedPeer() ?: return@post
                        touchActivity()
                        val store = vault
                        if (store == null || !store.isConfigured()) {
                            call.respondText("Vault not configured", status = HttpStatusCode.BadRequest)
                            return@post
                        }
                        val remote = json.decodeFromString<VaultIndexDocument>(call.receiveText())
                        vaultMutex.withLock {
                            val mergedTombs = mergeTombstones(store.loadTombstones(), remote.tombstones)
                            store.saveTombstones(mergedTombs)
                            val local = store.buildIndex(config.deviceId).let { index ->
                                val allow = config.heartedVaultFileNames()
                                if (allow == null) index
                                else index.filterEntriesByBasenames(allow)
                            }
                            call.respondText(
                                json.encodeToString(if (path == HOME_LAN_SYNC_PATH_VAULT_INDEX) local.forV1Sync() else local),
                                ContentType.Application.Json,
                            )
                        }
                    }
                }
                get(HOME_LAN_SYNC_PATH_VAULT_BLOB) {
                    call.pairedPeer() ?: return@get
                    touchActivity()
                    val store = vault
                    if (store == null || !store.isConfigured()) {
                        call.respondText("Vault not configured", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                    val rel = call.request.queryParameters["path"]?.normalizeVaultRelPath().orEmpty()
                    if (rel.isBlank()) {
                        call.respondText("Missing path", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                    val size = store.localSize(rel)
                    if (size == null) {
                        call.respondText("Not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    val range = parseHubBlobRange(call.request.header(HttpHeaders.Range), size)
                    if (range == null) {
                        call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                        call.respondText("Range not satisfiable", status = HttpStatusCode.RequestedRangeNotSatisfiable)
                        return@get
                    }
                    val bytes = store.readRange(rel, range.first, range.last)
                    if (bytes.size.toLong() != range.last - range.first + 1) {
                        call.respondText("Short read", status = HttpStatusCode.InternalServerError)
                        return@get
                    }
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$size")
                    call.respondBytes(bytes, ContentType.Application.OctetStream, HttpStatusCode.PartialContent)
                }
                put(HOME_LAN_SYNC_PATH_VAULT_BLOB) {
                    call.pairedPeer() ?: return@put
                    touchActivity()
                    val store = vault
                    if (store == null || !store.isConfigured()) {
                        call.respondText("Vault not configured", status = HttpStatusCode.BadRequest)
                        return@put
                    }
                    val rel = call.request.queryParameters["path"]?.normalizeVaultRelPath().orEmpty()
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull()
                    val total = call.request.queryParameters["total"]?.toLongOrNull()
                    if (rel.isBlank() || offset == null || total == null ||
                        total !in 0L..VAULT_MAX_FILE_BYTES || offset !in 0L..total
                    ) {
                        call.respondText("Missing or invalid path/offset/total", status = HttpStatusCode.BadRequest)
                        return@put
                    }
                    val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                    if (declared != null && declared > MAX_BLOB_BODY_BYTES) {
                        call.respondText("Chunk too large", status = HttpStatusCode.PayloadTooLarge)
                        return@put
                    }
                    // Read at most one byte past the cap so an undeclared oversized body is rejected
                    // without buffering it all.
                    val chunk = call.receiveChannel().readRemaining(MAX_BLOB_BODY_BYTES + 1).readByteArray()
                    if (chunk.size > MAX_BLOB_BODY_BYTES) {
                        call.respondText("Chunk too large", status = HttpStatusCode.PayloadTooLarge)
                        return@put
                    }
                    // Same staged path as client downloads: bytes only replace the file on the last chunk.
                    val written = vaultMutex.withLock {
                        runCatching { store.writeRange(rel, offset, chunk, total) }
                    }
                    written.onFailure { err ->
                        val status = if (err is IllegalArgumentException) HttpStatusCode.Conflict else HttpStatusCode.InternalServerError
                        call.respondText(err.message ?: "Write failed", status = status)
                        return@put
                    }
                    call.respondText(if (written.getOrDefault(false)) "published" else "ok")
                }
            }
        }
        config.remote?.start()
        engine.start(wait = false)
        server = engine
        listening = true
        touchActivity()
    }

    override suspend fun stop() {
        listening = false
        // Held polls would otherwise keep Netty's grace period busy for their whole hold.
        config.remote?.stop()
        server?.stop(1_000, 2_000)
        server = null
    }

    private fun caller(call: ApplicationCall): HubCaller =
        identifyHubCaller(call.clientFingerprint(), self, config.peers())

    private suspend fun ApplicationCall.pairedPeer(): PairedPeer? =
        (caller(this) as? HubCaller.Paired)?.peer
            ?: null.also { respondText("Unauthorized", status = HttpStatusCode.Unauthorized) }

    private suspend fun ApplicationCall.pairingClient(): CertFingerprint? =
        when (val caller = caller(this)) {
            HubCaller.Anonymous -> null.also { respondText("Unauthorized", status = HttpStatusCode.Unauthorized) }
            is HubCaller.Unpaired -> caller.fingerprint
            is HubCaller.Paired -> caller.peer.fingerprint
        }

    private suspend fun ApplicationCall.receiveOrRespondTooLarge(limit: Long): String? {
        val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        val body = if (declared != null && declared > limit) null
            else receiveChannel().readRemaining(limit + 1).readByteArray().takeIf { it.size <= limit }
        if (body == null) respondText("Request too large", status = HttpStatusCode.PayloadTooLarge)
        return body?.decodeToString()
    }

    private suspend fun ApplicationCall.receiveBytesOrRespondTooLarge(limit: Long): ByteArray? {
        val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        val body = if (declared != null && declared > limit) null
            else receiveChannel().readRemaining(limit + 1).readByteArray().takeIf { it.size <= limit }
        if (body == null) respondText("Request too large", status = HttpStatusCode.PayloadTooLarge)
        return body
    }

    private suspend fun <T> ApplicationCall.respondRemote(reply: RemoteHostReply<T>, encode: (T) -> String) = when (reply) {
        is RemoteHostReply.Ok -> {
            val body = encode(reply.value)
            if (body.isEmpty()) respond(HttpStatusCode.NoContent) else respondText(body, ContentType.Application.Json)
        }
        is RemoteHostReply.Refused -> respondText(reply.reason, status = HttpStatusCode.fromValue(reply.status))
    }

    private suspend fun ApplicationCall.respondAnswer(answer: HubAnswer, type: ContentType) =
        if (answer.status == HttpStatusCode.NoContent.value) respond(HttpStatusCode.NoContent)
        else respondText(answer.body, type, HttpStatusCode.fromValue(answer.status))

    private suspend inline fun <reified T> ApplicationCall.receivePairing(): T? {
        val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declared != null && declared > MAX_PAIRING_BODY_BYTES) {
            respondText("Pairing request too large", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        val body = receiveChannel().readRemaining(MAX_PAIRING_BODY_BYTES + 1).readByteArray()
        if (body.size > MAX_PAIRING_BODY_BYTES) {
            respondText("Pairing request too large", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        return runCatching { json.decodeFromString<T>(body.decodeToString()) }.getOrNull()
            ?: null.also { respondText("Malformed pairing request", status = HttpStatusCode.BadRequest) }
    }

    private suspend inline fun <reified T> ApplicationCall.respondPairing(reply: PairingReply<T>) = when (reply) {
        is PairingReply.Ok -> respondText(json.encodeToString(reply.value), ContentType.Application.Json)
        is PairingReply.Refused ->
            respondText(reply.reason, status = HttpStatusCode.fromValue(reply.refusal.httpStatus))
    }
}

private const val MAX_PAIRING_BODY_BYTES = 4_096L

private const val MAX_LISTENING_BODY_BYTES = 64L * 1024

private const val MAX_REMOTE_BODY_BYTES = 256L * 1024

private const val MAX_QUEUE_BODY_BYTES = 16L * 1024 * 1024

private const val MAX_BLOB_BODY_BYTES: Long = VAULT_BLOB_CHUNK_BYTES.toLong()

/**
 * Parses a single `bytes=start-end` (or open-ended `bytes=start-`) request range and bounds it
 * to one [VAULT_BLOB_CHUNK_BYTES] chunk. No header means the first chunk. Returns null when the
 * range is malformed or unsatisfiable (caller answers 416).
 */
internal fun parseHubBlobRange(header: String?, size: Long): LongRange? {
    if (size <= 0L) return null
    val maxEnd = { start: Long -> minOf(size - 1, start + VAULT_BLOB_CHUNK_BYTES - 1) }
    if (header.isNullOrBlank()) return 0L..maxEnd(0L)
    val spec = header.trim()
    if (!spec.startsWith("bytes=")) return null
    val parts = spec.removePrefix("bytes=").split('-')
    if (parts.size != 2) return null
    val start = parts[0].trim().toLongOrNull() ?: return null
    val endRaw = parts[1].trim()
    val end = if (endRaw.isEmpty()) size - 1 else endRaw.toLongOrNull() ?: return null
    if (start < 0L || start >= size || end < start) return null
    return start..minOf(end, maxEnd(start))
}
