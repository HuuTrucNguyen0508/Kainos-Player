package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.PlaylistsSyncDocument
import com.universalmusic.player.data.playlist.forV1Sync
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Syncs with [hub] at its last known address; [http] presents this device's certificate and pins the hub's. */
class HttpHomeLanSyncClient(
    private val hub: PairedPeer,
    private val library: LibraryRepository,
    private val playlists: KainosPlaylistRepository,
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    /** When true (default: the factory builds a fresh pinned client per session), [close] closes [http]. */
    private val ownsHttpClient: Boolean = true,
) : HomeLanSyncClient {
    override fun close() {
        if (ownsHttpClient) http.close()
    }

    private fun baseUrl(): String {
        val host = hub.lastKnownHost?.takeIf { it.isNotBlank() } ?: error("No address known for ${hub.displayName}")
        return "https://${HubEndpoint(host, hub.lastKnownPort).authority}"
    }

    override suspend fun probeHub(): Result<HomeLanHealthResponse> = runCatching {
        val text = http.get("${baseUrl()}$HOME_LAN_SYNC_PATH_HEALTH").requireSuccess("Hub health").bodyAsText()
        json.decodeFromString<HomeLanHealthResponse>(text)
    }

    override suspend fun syncHearts(): Result<HeartsSyncDocument> = runCatching {
        val local = library.exportHeartsSyncDocument()
        val text = postVersioned(HOME_LAN_SYNC_PATH_V2_HEARTS, HOME_LAN_SYNC_PATH_HEARTS) { v2 ->
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(if (v2) local else local.forV1Sync()))
        }.requireSuccess("Hearts sync").bodyAsText()
        json.decodeFromString<HeartsSyncDocument>(text)
    }

    override suspend fun syncPlaylists(): Result<PlaylistsSyncDocument> = runCatching {
        val local = playlists.exportSyncDocument()
        val text = postVersioned(HOME_LAN_SYNC_PATH_V2_PLAYLISTS, HOME_LAN_SYNC_PATH_PLAYLISTS) { v2 ->
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(if (v2) local else local.forV1Sync()))
        }.requireSuccess("Playlist sync").bodyAsText()
        json.decodeFromString<PlaylistsSyncDocument>(text)
    }

    override suspend fun fetchRemoteVaultIndex(local: VaultIndexDocument): Result<VaultIndexDocument> =
        runCatching {
            val text = postVersioned(HOME_LAN_SYNC_PATH_V2_VAULT_INDEX, HOME_LAN_SYNC_PATH_VAULT_INDEX) { v2 ->
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(if (v2) local else local.forV1Sync()))
            }.requireSuccess("Vault index").bodyAsText()
            json.decodeFromString<VaultIndexDocument>(text)
        }

    /** A missing v2 endpoint is the only reason to retry against the one-release v1 API. */
    private suspend fun postVersioned(
        v2Path: String,
        v1Path: String,
        block: HttpRequestBuilder.(Boolean) -> Unit,
    ): HttpResponse {
        val response = http.post("${baseUrl()}$v2Path") {
            expectSuccess = false
            block(true)
        }
        if (response.status != HttpStatusCode.NotFound) return response
        response.bodyAsBytes()
        return http.post("${baseUrl()}$v1Path") {
            expectSuccess = false
            block(false)
        }
    }

    override suspend fun downloadBlob(relPath: String, offset: Long, length: Int): Result<ByteArray> =
        runCatching {
            require(offset >= 0L && length in 1..VAULT_BLOB_CHUNK_BYTES) {
                "Invalid blob range offset=$offset length=$length"
            }
            val end = offset + length - 1
            val response = http.get("${baseUrl()}$HOME_LAN_SYNC_PATH_VAULT_BLOB") {
                parameter("path", relPath)
                header(HttpHeaders.Range, "bytes=$offset-$end")
            }
            // Only a 206 with a matching Content-Range is audio; anything else (error page,
            // full 200 body) must never reach the vault as file bytes.
            if (response.status != HttpStatusCode.PartialContent) {
                response.requireSuccess("Blob $relPath")
                error("Blob $relPath: expected 206 Partial Content, got ${response.status.value}")
            }
            val range = parseContentRange(response.headers[HttpHeaders.ContentRange])
                ?: error("Blob $relPath: missing or invalid Content-Range")
            require(range.first == offset && range.last in offset..end) {
                "Blob $relPath: Content-Range ${range.first}-${range.last} does not match $offset-$end"
            }
            val bytes = response.bodyAsBytes()
            val expected = range.last - range.first + 1
            require(bytes.size.toLong() == expected) {
                "Blob $relPath: body has ${bytes.size} bytes, Content-Range says $expected"
            }
            bytes
        }

    override suspend fun uploadBlob(
        relPath: String,
        offset: Long,
        totalSize: Long,
        chunk: ByteArray,
    ): Result<Unit> = runCatching {
        http.put("${baseUrl()}$HOME_LAN_SYNC_PATH_VAULT_BLOB") {
            parameter("path", relPath)
            parameter("offset", offset)
            parameter("total", totalSize)
            contentType(ContentType.Application.OctetStream)
            setBody(chunk)
        }.requireSuccess("Upload $relPath at $offset")
        Unit
    }
}

/** The phone's side of the pairing routes; a refusal throws with the hub's reason as its message. */
interface HomeLanPairingClient : AutoCloseable {
    override fun close() = Unit
    suspend fun start(request: PairStartRequest): PairStartResponse
    suspend fun reveal(request: PairRevealRequest)
    suspend fun confirm(attemptId: String)
    suspend fun cancel(attemptId: String)
    suspend fun status(attemptId: String): PairStatusResponse
}

class HttpHomeLanPairingClient(
    private val endpoint: HubEndpoint,
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : HomeLanPairingClient {
    override fun close() = http.close()

    private val base get() = "https://${endpoint.authority}"

    override suspend fun start(request: PairStartRequest): PairStartResponse =
        json.decodeFromString(post(HOME_LAN_SYNC_PATH_PAIR_START, json.encodeToString(request)))

    override suspend fun reveal(request: PairRevealRequest) {
        post(HOME_LAN_SYNC_PATH_PAIR_REVEAL, json.encodeToString(request))
    }

    override suspend fun confirm(attemptId: String) {
        post(HOME_LAN_SYNC_PATH_PAIR_CONFIRM, json.encodeToString(PairAttemptRequest(attemptId)))
    }

    override suspend fun cancel(attemptId: String) {
        post(HOME_LAN_SYNC_PATH_PAIR_CANCEL, json.encodeToString(PairAttemptRequest(attemptId)))
    }

    override suspend fun status(attemptId: String): PairStatusResponse =
        json.decodeFromString(
            http.get("$base$HOME_LAN_SYNC_PATH_PAIR_STATUS") {
                expectSuccess = false
                parameter("attemptId", attemptId)
            }.pairingBody(),
        )

    private suspend fun post(path: String, body: String): String =
        http.post("$base$path") {
            expectSuccess = false
            contentType(ContentType.Application.Json)
            setBody(body)
        }.pairingBody()

    private suspend fun HttpResponse.pairingBody(): String {
        val text = bodyAsText()
        if (!status.isSuccess()) throw PairingRefusedException(status.value, text.take(200).ifBlank { "HTTP ${status.value}" })
        return text
    }
}

class PairingRefusedException(val httpStatus: Int, reason: String) : IllegalStateException(reason)

/** Throws with status + a short body excerpt unless the hub answered 2xx. */
private suspend fun HttpResponse.requireSuccess(what: String): HttpResponse {
    if (status.isSuccess()) return this
    val detail = runCatching { bodyAsText() }.getOrDefault("").take(200).trim()
    throw IllegalStateException(
        "$what failed: HTTP ${status.value}" + if (detail.isNotEmpty()) " ($detail)" else "",
    )
}

/** Parses `bytes start-end/total` into the inclusive byte range; null when malformed. */
internal fun parseContentRange(header: String?): LongRange? {
    val spec = header?.trim()?.removePrefix("bytes ")?.substringBefore('/') ?: return null
    val start = spec.substringBefore('-', "").trim().toLongOrNull() ?: return null
    val end = spec.substringAfter('-', "").trim().toLongOrNull() ?: return null
    if (start < 0L || end < start) return null
    return start..end
}

/** No-op hub for Android / tests. */
class NoOpHomeLanSyncHub : HomeLanSyncHub {
    override val isListening: Boolean = false
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun touchActivity() = Unit
    override fun lastActivityAtMs(): Long? = null
}
