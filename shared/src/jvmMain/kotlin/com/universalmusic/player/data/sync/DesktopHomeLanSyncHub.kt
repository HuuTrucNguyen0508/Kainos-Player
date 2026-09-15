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
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopHomeLanSyncHub(
    private val pairing: HomeLanSyncPairing,
    private val onHearts: suspend (HeartsSyncDocument) -> HeartsSyncDocument,
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
                    val size = store.localSize(rel) ?: 0L
                    val range = call.request.header(HttpHeaders.Range)
                    val (start, end) = parseRange(range, size)
                    val bytes = store.readRange(rel, start, end)
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$size")
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
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                    val total = call.request.queryParameters["total"]?.toLongOrNull() ?: -1L
                    if (rel.isBlank() || total < 0L) {
                        call.respondText("Missing path/total", status = HttpStatusCode.BadRequest)
                        return@put
                    }
                    val raw = call.receive<ByteArray>()
                    val chunk = decryptIfNeeded(raw)
                    vaultMutex.withLock {
                        store.writeRange(rel, offset, chunk, total)
                    }
                    call.respondText("ok")
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
        val header = call.request.header(HttpHeaders.Authorization).orEmpty()
        val expected = "Bearer ${pairing.sharedSecretHex}"
        if (header != expected) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return false
        }
        val requiredPin = pairing.pairingPin?.takeIf { it.isNotBlank() }
        if (requiredPin != null) {
            val pin = call.request.header(HOME_LAN_SYNC_HEADER_PIN).orEmpty()
            if (pin != requiredPin) {
                call.respondText("Invalid pairing PIN", status = HttpStatusCode.Forbidden)
                return false
            }
        }
        return true
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

    private fun parseRange(header: String?, size: Long): Pair<Long, Long> {
        if (size <= 0L) return 0L to -1L
        if (header.isNullOrBlank() || !header.startsWith("bytes=")) {
            return 0L to (size - 1)
        }
        val spec = header.removePrefix("bytes=")
        val parts = spec.split('-', limit = 2)
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.toLongOrNull() ?: (size - 1)
        return start.coerceAtLeast(0L) to end.coerceAtMost(size - 1)
    }
}
