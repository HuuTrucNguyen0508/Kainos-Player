package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HttpHomeLanSyncClient(
    private val pairing: HomeLanSyncPairing,
    private val library: LibraryRepository,
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : HomeLanSyncClient {
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
        }.bodyAsText()
        json.decodeFromString<HomeLanHealthResponse>(text)
    }

    override suspend fun syncHearts(): Result<HeartsSyncDocument> = runCatching {
        val local = library.exportHeartsSyncDocument()
        val text = http.post("${baseUrl()}$HOME_LAN_SYNC_PATH_HEARTS") {
            authHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(local))
        }.bodyAsText()
        json.decodeFromString<HeartsSyncDocument>(text)
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
            }.bodyAsText()
            val plain = if (cryptoKey != null) {
                SyncPayloadCrypto.decryptFromBase64(text, cryptoKey).decodeToString()
            } else {
                text
            }
            json.decodeFromString<VaultIndexDocument>(plain)
        }

    override suspend fun downloadBlob(relPath: String, offset: Long, length: Int): Result<ByteArray> =
        runCatching {
            val end = offset + length - 1
            val bytes = http.get("${baseUrl()}$HOME_LAN_SYNC_PATH_VAULT_BLOB") {
                authHeaders()
                parameter("path", relPath)
                header(HttpHeaders.Range, "bytes=$offset-$end")
            }.bodyAsBytes()
            if (cryptoKey != null) SyncPayloadCrypto.decrypt(bytes, cryptoKey) else bytes
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
        }.bodyAsText()
    }
}

/** No-op hub for Android / tests. */
class NoOpHomeLanSyncHub : HomeLanSyncHub {
    override val isListening: Boolean = false
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun touchActivity() = Unit
    override fun lastActivityAtMs(): Long? = null
}
