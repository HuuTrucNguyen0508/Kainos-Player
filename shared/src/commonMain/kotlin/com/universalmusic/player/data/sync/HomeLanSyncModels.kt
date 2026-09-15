package com.universalmusic.player.data.sync

import kotlinx.serialization.Serializable

const val HOME_LAN_SYNC_DEFAULT_PORT = 43822
const val HOME_LAN_SYNC_PATH_HEALTH = "/kainos-sync/v1/health"
const val HOME_LAN_SYNC_PATH_HEARTS = "/kainos-sync/v1/hearts"
const val HOME_LAN_SYNC_PATH_VAULT_INDEX = "/kainos-sync/v1/vault/index"
const val HOME_LAN_SYNC_PATH_VAULT_BLOB = "/kainos-sync/v1/vault/blob"
const val HOME_LAN_SYNC_HEADER_ENC = "X-Kainos-Enc"
const val HOME_LAN_SYNC_HEADER_PIN = "X-Kainos-Pairing-Pin"
const val HOME_LAN_SYNC_ENC_AES_GCM = "aes-gcm-v1"
const val HOME_LAN_HUB_IDLE_TIMEOUT_MS = 30L * 60L * 1000L

/**
 * Pairing URI with mandatory hub certificate pin (TLS).
 * Older `kainos-homesync:1?` URIs without `cert=` are rejected for sync.
 */
const val HOME_LAN_PAIRING_URI_PREFIX = "kainos-homesync:2?"
const val HOME_LAN_PAIRING_URI_PREFIX_V1 = "kainos-homesync:1?"

@Serializable
data class HomeLanSyncPairing(
    val deviceId: String,
    val peerDeviceId: String? = null,
    /** Shared secret (hex). Sent as Bearer token over TLS only. */
    val sharedSecretHex: String,
    val hubHost: String? = null,
    val hubPort: Int = HOME_LAN_SYNC_DEFAULT_PORT,
    /** Short PIN shown once for pairing confirmation; verified on hub requests. */
    val pairingPin: String? = null,
    /**
     * SHA-256 of the hub leaf certificate DER (lowercase hex). Required for HTTPS pin.
     */
    val hubCertSha256Hex: String? = null,
    /** Extra AES-GCM on vault bodies (defense in depth under TLS). */
    val encryptPayloads: Boolean = true,
)

@Serializable
data class HomeLanSyncStatus(
    val enabled: Boolean = false,
    val role: HomeLanSyncRole = HomeLanSyncRole.AUTO,
    val lastSyncAtMs: Long? = null,
    val lastError: String? = null,
    val lastDetail: String? = null,
    val hubListening: Boolean = false,
    val vaultProgress: String? = null,
    val pendingConflicts: List<VaultConflict> = emptyList(),
    val bytesTransferred: Long = 0,
    val bytesTotal: Long = 0,
)

@Serializable
enum class HomeLanSyncRole {
    /** Desktop hubs; Android clients. */
    AUTO,
    HUB,
    CLIENT,
}

@Serializable
data class HomeLanHealthResponse(
    val ok: Boolean = true,
    val deviceId: String,
    val app: String = "kainos-player",
    val vaultConfigured: Boolean = false,
    val encryptPayloads: Boolean = true,
    val tls: Boolean = true,
)

interface HomeLanSyncHub {
    val isListening: Boolean
    suspend fun start()
    suspend fun stop()
    fun touchActivity()
    /** Epoch ms of last authorized request; null when unknown / no-op hub. */
    fun lastActivityAtMs(): Long?
}

interface HomeLanSyncClient {
    suspend fun syncHearts(): Result<HeartsSyncDocument>
    suspend fun probeHub(): Result<HomeLanHealthResponse>
    suspend fun fetchRemoteVaultIndex(local: VaultIndexDocument): Result<VaultIndexDocument>
    suspend fun downloadBlob(relPath: String, offset: Long, length: Int): Result<ByteArray>
    suspend fun uploadBlob(relPath: String, offset: Long, totalSize: Long, chunk: ByteArray): Result<Unit>
}

interface HomeLanSyncController {
    val status: kotlinx.coroutines.flow.StateFlow<HomeLanSyncStatus>
    val pairing: kotlinx.coroutines.flow.StateFlow<HomeLanSyncPairing?>
    suspend fun enable(enabled: Boolean)
    suspend fun beginHubPairing(): HomeLanSyncPairing
    suspend fun completeClientPairing(
        hubHost: String,
        hubPort: Int,
        sharedSecretHex: String,
        peerDeviceId: String,
        pairingPin: String = "",
        hubCertSha256Hex: String = "",
    )
    suspend fun completeClientPairingFromUri(uri: String)
    suspend fun unpair()
    suspend fun syncNow(): Result<String>
    suspend fun startHubIfNeeded()
    suspend fun stopHub()
    suspend fun tombstoneVaultPath(relPath: String): Result<String>
    suspend fun resolveConflictKeepLocal(relPath: String): Result<String>
    suspend fun resolveConflictKeepRemote(relPath: String): Result<String>
    fun pairingUri(): String?
}

fun HomeLanSyncPairing.toPairingUri(hubHostOverride: String? = null): String {
    val host = hubHostOverride ?: hubHost ?: "YOUR_PC_LAN_IP"
    val cert = hubCertSha256Hex?.takeIf { it.isNotBlank() }
        ?: error("Hub certificate pin required before sharing pairing URI")
    val params = buildList {
        add("host=${encodePairingComponent(host)}")
        add("port=$hubPort")
        add("secret=${encodePairingComponent(sharedSecretHex)}")
        add("deviceId=${encodePairingComponent(deviceId)}")
        add("cert=${encodePairingComponent(cert.lowercase())}")
        pairingPin?.takeIf { it.isNotBlank() }?.let {
            add("pin=${encodePairingComponent(it)}")
        }
        if (encryptPayloads) add("enc=1")
    }
    return HOME_LAN_PAIRING_URI_PREFIX + params.joinToString("&")
}

fun parseHomeLanPairingUri(raw: String): HomeLanSyncPairing? {
    val trimmed = raw.trim()
    val query = when {
        trimmed.startsWith(HOME_LAN_PAIRING_URI_PREFIX) ->
            trimmed.removePrefix(HOME_LAN_PAIRING_URI_PREFIX)
        trimmed.startsWith(HOME_LAN_PAIRING_URI_PREFIX_V1) ->
            trimmed.removePrefix(HOME_LAN_PAIRING_URI_PREFIX_V1)
        else -> return null
    }
    val map = query.split('&')
        .mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else part.substring(0, idx) to decodePairingComponent(part.substring(idx + 1))
        }
        .toMap()
    val host = map["host"]?.takeIf { it.isNotBlank() } ?: return null
    val secret = map["secret"]?.takeIf { it.isNotBlank() } ?: return null
    val cert = map["cert"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) } ?: return null
    val deviceId = map["deviceId"]?.takeIf { it.isNotBlank() } ?: "peer"
    val port = map["port"]?.toIntOrNull() ?: HOME_LAN_SYNC_DEFAULT_PORT
    return HomeLanSyncPairing(
        deviceId = "pending-local",
        peerDeviceId = deviceId,
        sharedSecretHex = secret,
        hubHost = host,
        hubPort = port,
        pairingPin = map["pin"],
        hubCertSha256Hex = cert.lowercase(),
        encryptPayloads = map["enc"] != "0",
    )
}

private fun encodePairingComponent(value: String): String =
    buildString(value.length) {
        for (ch in value) {
            when (ch) {
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '.', '_', '~' -> append(ch)
                else -> {
                    val bytes = ch.toString().encodeToByteArray()
                    for (b in bytes) {
                        append('%')
                        append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1).uppercase())
                    }
                }
            }
        }
    }

private fun decodePairingComponent(value: String): String {
    val bytes = ArrayList<Byte>()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hex = value.substring(i + 1, i + 3)
            bytes += hex.toInt(16).toByte()
            i += 3
        } else {
            bytes += c.code.toByte()
            i += 1
        }
    }
    return bytes.toByteArray().decodeToString()
}
