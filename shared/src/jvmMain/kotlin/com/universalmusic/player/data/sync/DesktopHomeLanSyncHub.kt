package com.universalmusic.player.data.sync

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
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopHomeLanSyncHub(
    private val pairing: HomeLanSyncPairing,
    private val onHearts: suspend (HeartsSyncDocument) -> HeartsSyncDocument,
    private val onPlaylists: suspend (com.universalmusic.player.data.playlist.PlaylistsSyncDocument) -> com.universalmusic.player.data.playlist.PlaylistsSyncDocument,
    private val vault: HomeLanVaultStore?,
    private val tls: HomeLanHubTlsMaterial,
    /** Null = full vault index; non-null = hearts-only basename allowlist. */
    private val heartedVaultFileNames: () -> Set<String>? = { null },
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val bindHost: String = "0.0.0.0",
) : HomeLanSyncHub {
    private var server: EmbeddedServer<*, *>? = null
    @Volatile
    private var listening = false
    @Volatile
    private var lastActivityMs: Long = System.currentTimeMillis()
    private val vaultMutex = Mutex()
    private val cryptoKey: ByteArray? =
        if (pairing.encryptPayloads) SyncPayloadCrypto.keyFromSharedSecret(pairing.sharedSecretHex) else null

    override val isListening: Boolean get() = listening

    override fun touchActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    override fun lastActivityAtMs(): Long = lastActivityMs

    override suspend fun start() {
        if (listening) return
        // Netty (not CIO): Ktor CIO server engine does not support HTTPS/sslConnector.
        val engine = embeddedServer(
            Netty,
            environment = applicationEnvironment {},
            configure = {
                sslConnector(
                    keyStore = tls.keyStore,
                    keyAlias = tls.keyAlias,
                    keyStorePassword = { tls.keyStorePassword },
                    privateKeyPassword = { tls.privateKeyPassword },
                ) {
                    port = pairing.hubPort
                    host = bindHost
                    keyStorePath = tls.keyStoreFile
                }
            },
        ) {
            routing {
                get(HOME_LAN_SYNC_PATH_HEALTH) {
                    if (!authorized(call)) return@get
                    touchActivity()
                    call.respondText(
                        json.encodeToString(
                            HomeLanHealthResponse(
                                deviceId = pairing.deviceId,
                                vaultConfigured = vault?.isConfigured() == true,
                                encryptPayloads = pairing.encryptPayloads,
                                tls = true,
                            ),
                        ),
                        ContentType.Application.Json,
                    )
                }
                post(HOME_LAN_SYNC_PATH_HEARTS) {
                    if (!authorized(call)) return@post
                    touchActivity()
                    val body = call.receiveText()
                    val remote = json.decodeFromString<HeartsSyncDocument>(body)
                    val localAfter = onHearts(remote)
                    call.respondText(
                        json.encodeToString(localAfter),
                        ContentType.Application.Json,
                    )
                }
                post(HOME_LAN_SYNC_PATH_PLAYLISTS) {
                    if (!authorized(call)) return@post
                    touchActivity()
                    val body = call.receiveText()
                    val remote = json.decodeFromString<com.universalmusic.player.data.playlist.PlaylistsSyncDocument>(body)
                    val localAfter = onPlaylists(remote)
                    call.respondText(
                        json.encodeToString(localAfter),
                        ContentType.Application.Json,
                    )
                }
                post(HOME_LAN_SYNC_PATH_VAULT_INDEX) {
                    if (!authorized(call)) return@post
                    touchActivity()
                    val store = vault
                    if (store == null || !store.isConfigured()) {
                        call.respondText("Vault not configured", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    val remote = decodeVaultIndex(call)
                    vaultMutex.withLock {
                        val mergedTombs = mergeTombstones(store.loadTombstones(), remote.tombstones)
                        store.saveTombstones(mergedTombs)
                        val local = store.buildIndex(pairing.deviceId).let { index ->
                            val allow = heartedVaultFileNames()
                            if (allow == null) index
                            else index.filterEntriesByBasenames(allow)
                        }
                        respondVaultIndex(call, local)
                    }
                }
                get(HOME_LAN_SYNC_PATH_VAULT_BLOB) {
                    if (!authorized(call)) return@get
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
                    val payload = encryptIfNeeded(bytes)
                    call.respondBytes(payload, ContentType.Application.OctetStream, HttpStatusCode.PartialContent)
                }
                put(HOME_LAN_SYNC_PATH_VAULT_BLOB) {
                    if (!authorized(call)) return@put
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
                    val raw = call.receiveChannel().readRemaining(MAX_BLOB_BODY_BYTES + 1).readByteArray()
                    if (raw.size > MAX_BLOB_BODY_BYTES) {
                        call.respondText("Chunk too large", status = HttpStatusCode.PayloadTooLarge)
                        return@put
                    }
                    val chunk = runCatching { decryptIfNeeded(raw) }.getOrElse {
                        call.respondText("Cannot decrypt chunk", status = HttpStatusCode.BadRequest)
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
        engine.start(wait = false)
        server = engine
        listening = true
        touchActivity()
    }

    override suspend fun stop() {
        listening = false
        server?.stop(1_000, 2_000)
        server = null
    }

    private suspend fun authorized(call: ApplicationCall): Boolean {
        when (
            homeLanHubAuthorize(
                pairing,
                authorizationHeader = call.request.header(HttpHeaders.Authorization),
                pinHeader = call.request.header(HOME_LAN_SYNC_HEADER_PIN),
            )
        ) {
            HubAuthResult.OK -> return true
            HubAuthResult.BAD_TOKEN ->
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            HubAuthResult.BAD_PIN ->
                call.respondText("Invalid pairing PIN", status = HttpStatusCode.Forbidden)
        }
        return false
    }

    private suspend fun decodeVaultIndex(call: ApplicationCall): VaultIndexDocument {
        val enc = call.request.header(HOME_LAN_SYNC_HEADER_ENC)
        val body = call.receiveText()
        val plain = if (enc == HOME_LAN_SYNC_ENC_AES_GCM && cryptoKey != null) {
            SyncPayloadCrypto.decryptFromBase64(body, cryptoKey).decodeToString()
        } else {
            body
        }
        return json.decodeFromString(plain)
    }

    private suspend fun respondVaultIndex(call: ApplicationCall, doc: VaultIndexDocument) {
        val encoded = json.encodeToString(doc)
        if (cryptoKey != null) {
            call.response.header(HOME_LAN_SYNC_HEADER_ENC, HOME_LAN_SYNC_ENC_AES_GCM)
            call.respondText(
                SyncPayloadCrypto.encryptToBase64(encoded.encodeToByteArray(), cryptoKey),
                ContentType.Text.Plain,
            )
        } else {
            call.respondText(encoded, ContentType.Application.Json)
        }
    }

    private fun encryptIfNeeded(bytes: ByteArray): ByteArray {
        val key = cryptoKey ?: return bytes
        return SyncPayloadCrypto.encrypt(bytes, key)
    }

    private fun decryptIfNeeded(bytes: ByteArray): ByteArray {
        val key = cryptoKey ?: return bytes
        return SyncPayloadCrypto.decrypt(bytes, key)
    }
}

/** Max PUT body: one plaintext chunk plus AES-GCM nonce/tag and slack. */
private const val MAX_BLOB_BODY_BYTES: Long = VAULT_BLOB_CHUNK_BYTES.toLong() + 1024L

internal enum class HubAuthResult { OK, BAD_TOKEN, BAD_PIN }

/** Bearer token + optional pairing PIN check using constant-time comparisons. */
internal fun homeLanHubAuthorize(
    pairing: HomeLanSyncPairing,
    authorizationHeader: String?,
    pinHeader: String?,
): HubAuthResult {
    val expected = "Bearer ${pairing.sharedSecretHex}"
    if (!constantTimeEquals(authorizationHeader.orEmpty(), expected)) return HubAuthResult.BAD_TOKEN
    val requiredPin = pairing.pairingPin?.takeIf { it.isNotBlank() } ?: return HubAuthResult.OK
    if (!constantTimeEquals(pinHeader.orEmpty(), requiredPin)) return HubAuthResult.BAD_PIN
    return HubAuthResult.OK
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())

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
