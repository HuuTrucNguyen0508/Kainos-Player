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
/** Portable Home pins; hubs from before pin sync answer 404 and the client skips pins. */
const val HOME_LAN_SYNC_PATH_V3_PINS = "/kainos-sync/v3/pins"

const val HOME_LAN_SYNC_PATH_VAULT_BLOB = "/kainos-sync/v1/vault/blob"
const val HOME_LAN_SYNC_PATH_V2_VAULT_BLOB_HASH = "/kainos-sync/v2/vault/blob-hash"

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
    val hubAutostartError: String? = null,
    val lastDetail: String? = null,
    val hubListening: Boolean = false,
    val vaultProgress: String? = null,
    val pendingConflicts: List<VaultConflict> = emptyList(),
    /** Missing hearted (or vault) files waiting for user confirm before blob copy. */
    val pendingTransfers: List<PendingVaultTransfer> = emptyList(),
    /** Hearted YouTube audio cached on one side and missing on the other, waiting for Transfer or Skip. */
    val pendingCachedAudio: List<PendingCachedAudioTransfer> = emptyList(),
    /** Copies the user skipped for the current peer; they are not offered again until the file changes. */
    val skippedCopies: List<SkippedCopy> = emptyList(),
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

    /** The hub's pins after it merged ours; null when the hub predates pin sync. */
    suspend fun syncHomePins(): Result<com.universalmusic.player.data.library.HomePinsSyncDocument?> = Result.success(null)
    suspend fun probeHub(): Result<HomeLanHealthResponse>
    suspend fun fetchRemoteVaultIndex(local: VaultIndexDocument): Result<VaultIndexDocument>
    suspend fun downloadBlob(relPath: String, offset: Long, length: Int): Result<ByteArray>

    /** SHA-256 of the whole file as the hub reads it now; null when the hub predates this route. */
    suspend fun fetchBlobHash(relPath: String): Result<String?> = Result.success(null)

    suspend fun uploadBlob(relPath: String, offset: Long, totalSize: Long, chunk: ByteArray): Result<Unit>

    /** The hub's own loudness measurements after it took ours; null when the hub predates loudness sync. */
    suspend fun syncLoudness(local: LoudnessSyncDocument): Result<LoudnessSyncDocument?> = Result.success(null)

    /** The hub's cached-audio offers after it saw ours; null when the hub predates cached-audio sync. */
    suspend fun syncCachedAudio(local: CachedAudioSyncDocument): Result<CachedAudioSyncDocument?> = Result.success(null)

    suspend fun downloadCachedAudio(owner: String, token: String, offset: Long, length: Int): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("Cached audio sync unsupported"))

    /** SHA-256 of the hub's cached file [token] for [owner]. */
    suspend fun fetchCachedAudioHash(owner: String, token: String): Result<String> =
        Result.failure(UnsupportedOperationException("Cached audio sync unsupported"))

    /**
     * Sends one chunk; the hub verifies [sha256] and publishes once the chunk ending at [totalSize] arrives.
     * Returns the hub's word: `ok`, `published`, or `already cached` (stop sending).
     */
    suspend fun uploadCachedAudio(
        owner: String, token: String, offset: Long, totalSize: Long, sha256: String, chunk: ByteArray,
    ): Result<String> = Result.failure(UnsupportedOperationException("Cached audio sync unsupported"))
}
