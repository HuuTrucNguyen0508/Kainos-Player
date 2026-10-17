package com.universalmusic.player.data.sync

import kotlinx.serialization.Serializable

const val HOME_LAN_SYNC_DEFAULT_PORT = 43822
const val HOME_LAN_SYNC_PATH_HEALTH = "/kainos-sync/v1/health"
const val HOME_LAN_SYNC_PATH_HEARTS = "/kainos-sync/v1/hearts"
const val HOME_LAN_SYNC_PATH_PLAYLISTS = "/kainos-sync/v1/playlists"
const val HOME_LAN_SYNC_PATH_VAULT_INDEX = "/kainos-sync/v1/vault/index"
const val HOME_LAN_SYNC_PATH_V2_HEARTS = "/kainos-sync/v2/hearts"
const val HOME_LAN_SYNC_PATH_V2_PLAYLISTS = "/kainos-sync/v2/playlists"
const val HOME_LAN_SYNC_PATH_V2_VAULT_INDEX = "/kainos-sync/v2/vault/index"

const val HOME_LAN_SYNC_PATH_VAULT_BLOB = "/kainos-sync/v1/vault/blob"

const val HOME_LAN_MDNS_SERVICE_TYPE = "_kainos-sync._tcp"
const val HOME_LAN_MDNS_TXT_FP = "fp"
const val HOME_LAN_MDNS_TXT_ID = "id"
const val HOME_LAN_MDNS_TXT_VERSION = "v"
const val HOME_LAN_MDNS_VERSION = "3"

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
    /** Missing hearted (or vault) files waiting for user confirm before blob copy. */
    val pendingTransfers: List<PendingVaultTransfer> = emptyList(),
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
)

interface HomeLanSyncHub {
    val isListening: Boolean
    suspend fun start()
    suspend fun stop()
    fun touchActivity()
    /** Epoch ms of last authorized request; null when unknown / no-op hub. */
    fun lastActivityAtMs(): Long?
}

/**
 * One hub session. Callers close it after each operation ([use]) so the pinned HttpClient
 * behind it does not leak; the default close is a no-op for fakes.
 */
interface HomeLanSyncClient : AutoCloseable {
    override fun close() = Unit
    suspend fun syncHearts(): Result<HeartsSyncDocument>
    suspend fun syncPlaylists(): Result<com.universalmusic.player.data.playlist.PlaylistsSyncDocument>
    suspend fun probeHub(): Result<HomeLanHealthResponse>
    suspend fun fetchRemoteVaultIndex(local: VaultIndexDocument): Result<VaultIndexDocument>
    suspend fun downloadBlob(relPath: String, offset: Long, length: Int): Result<ByteArray>
    suspend fun uploadBlob(relPath: String, offset: Long, totalSize: Long, chunk: ByteArray): Result<Unit>
}
