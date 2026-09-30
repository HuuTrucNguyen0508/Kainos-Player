package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.PlaylistsSyncDocument
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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

class HttpHomeLanSyncClient(
    private val pairing: HomeLanSyncPairing,
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

    private val cryptoKey: ByteArray? =
        if (pairing.encryptPayloads) SyncPayloadCrypto.keyFromSharedSecret(pairing.sharedSecretHex) else null

    private fun baseUrl(): String {
        val host = pairing.hubHost?.takeIf { it.isNotBlank() }
            ?: error("Hub host not set; complete client pairing with the PC LAN address")
        require(!pairing.hubCertSha256Hex.isNullOrBlank()) {
            "Hub certificate pin required; re-pair from a kainos-homesync:2 URI"
        }
        return "https://$host:${pairing.hubPort}"
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        bearerAuth(pairing.sharedSecretHex)
        pairing.pairingPin?.takeIf { it.isNotBlank() }?.let {
            header(HOME_LAN_SYNC_HEADER_PIN, it)
        }
    }

    override suspend fun probeHub(): Result<HomeLanHealthResponse> = runCatching {
        val text = http.get("${baseUrl()}$HOME_LAN_SYNC_PATH_HEALTH") {
            authHeaders()
        }.requireSuccess("Hub health").bodyAsText()
        json.decodeFromString<HomeLanHealthResponse>(text)
    }

    override suspend fun syncHearts(): Result<HeartsSyncDocument> = runCatching {
        val local = library.exportHeartsSyncDocument()
        val text = http.post("${baseUrl()}$HOME_LAN_SYNC_PATH_HEARTS") {
            authHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(local))
        }.requireSuccess("Hearts sync").bodyAsText()
        json.decodeFromString<HeartsSyncDocument>(text)
    }

    override suspend fun syncPlaylists(): Result<PlaylistsSyncDocument> = runCatching {
        val local = playlists.exportSyncDocument()
        val text = http.post("${baseUrl()}$HOME_LAN_SYNC_PATH_PLAYLISTS") {
            authHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(local))
        }.requireSuccess("Playlist sync").bodyAsText()
        json.decodeFromString<PlaylistsSyncDocument>(text)
    }

    override suspend fun fetchRemoteVaultIndex(local: VaultIndexDocument): Result<VaultIndexDocument> =
        runCatching {
            val encoded = json.encodeToString(local)
            val text = http.post("${baseUrl()}$HOME_LAN_SYNC_PATH_VAULT_INDEX") {
                authHeaders()
                if (cryptoKey != null) {
                    header(HOME_LAN_SYNC_HEADER_ENC, HOME_LAN_SYNC_ENC_AES_GCM)
                    contentType(ContentType.Text.Plain)
                    setBody(SyncPayloadCrypto.encryptToBase64(encoded.encodeToByteArray(), cryptoKey))
                } else {
                    contentType(ContentType.Application.Json)
                    setBody(encoded)
                }
            }.requireSuccess("Vault index").bodyAsText()
            val plain = if (cryptoKey != null) {
                SyncPayloadCrypto.decryptFromBase64(text, cryptoKey).decodeToString()
            } else {
                text
            }
            json.decodeFromString<VaultIndexDocument>(plain)
        }

    override suspend fun downloadBlob(relPath: String, offset: Long, length: Int): Result<ByteArray> =
        runCatching {
            require(offset >= 0L && length in 1..VAULT_BLOB_CHUNK_BYTES) {
                "Invalid blob range offset=$offset length=$length"
            }
            val end = offset + length - 1
            val response = http.get("${baseUrl()}$HOME_LAN_SYNC_PATH_VAULT_BLOB") {
                authHeaders()
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
            val raw = response.bodyAsBytes()
            val bytes = if (cryptoKey != null) SyncPayloadCrypto.decrypt(raw, cryptoKey) else raw
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
        val payload = if (cryptoKey != null) SyncPayloadCrypto.encrypt(chunk, cryptoKey) else chunk
        http.put("${baseUrl()}$HOME_LAN_SYNC_PATH_VAULT_BLOB") {
            authHeaders()
            parameter("path", relPath)
            parameter("offset", offset)
            parameter("total", totalSize)
            contentType(ContentType.Application.OctetStream)
            setBody(payload)
        }.requireSuccess("Upload $relPath at $offset")
        Unit
    }
}

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
