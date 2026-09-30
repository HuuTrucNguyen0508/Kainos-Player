package com.universalmusic.player.data.sync

import kotlinx.serialization.Serializable

/** Persisted vault tombstones (and future local vault metadata). */
@Serializable
data class VaultSyncStateDocument(
    val tombstones: List<VaultTombstone> = emptyList(),
)

/**
 * Local vault root I/O for home-LAN file sync.
 * [vaultRoot] is a filesystem path (desktop) or SAF tree URI (Android).
 */
interface HomeLanVaultStore {
    fun isConfigured(): Boolean

    suspend fun buildIndex(deviceId: String): VaultIndexDocument

    /**
     * Inclusive end; empty when file missing or the range is invalid. Returns at most
     * [VAULT_BLOB_CHUNK_BYTES] bytes per call (callers loop on the returned size).
     */
    suspend fun readRange(relPath: String, start: Long, endInclusive: Long): ByteArray

    /**
     * Staged write: [data] lands in a partial file next to [relPath] whose name is outside
     * [VAULT_AUDIO_EXTENSIONS] (see [vaultStagingName]), never in the final file. Chunks must be
     * contiguous: [offset] 0 restarts staging, otherwise [offset] must not exceed the staged size.
     * When the staged size reaches [totalSize] it is verified and published over [relPath]
     * (atomically where the platform allows). An interrupted transfer therefore never touches an
     * existing good copy. Returns true when this call published the file.
     */
    suspend fun writeRange(relPath: String, offset: Long, data: ByteArray, totalSize: Long): Boolean

    /** Bytes already staged for [relPath] (resume point), or null when nothing is staged. */
    suspend fun stagedSize(relPath: String): Long? = null

    /** Drops any staged partial for [relPath]. */
    suspend fun discardStaged(relPath: String) = Unit

    suspend fun delete(relPath: String): Boolean

    suspend fun localSize(relPath: String): Long?

    suspend fun loadTombstones(): List<VaultTombstone>

    suspend fun saveTombstones(tombstones: List<VaultTombstone>)
}

val VAULT_AUDIO_EXTENSIONS: Set<String> = setOf(
    "aif", "aiff", "alac", "flac", "wav", "wave",
    "aac", "m4a", "mp3", "oga", "ogg", "opus", "wma",
)

const val VAULT_BLOB_CHUNK_BYTES: Int = 512 * 1024

/** Upper bound for a single vault file (hi-res WAV/AIFF stays well under this). */
const val VAULT_MAX_FILE_BYTES: Long = 8L * 1024L * 1024L * 1024L

/** Suffix for in-flight vault writes; not an audio extension so indexes skip it. */
const val VAULT_STAGING_SUFFIX: String = ".kainos-part"

/**
 * Staging file name for [fileName] in the same directory (same filesystem for rename).
 * No leading dot: some SAF providers hide or refuse dotfiles.
 */
fun vaultStagingName(fileName: String): String = "$fileName$VAULT_STAGING_SUFFIX"

/**
 * Validates one staged chunk against the bytes already staged. Throws on a gap, overflow,
 * or out-of-bounds sizes so a bad peer can never produce a padded or oversized file.
 */
fun checkVaultChunk(stagedBytes: Long, offset: Long, chunkSize: Int, totalSize: Long) {
    require(totalSize in 0L..VAULT_MAX_FILE_BYTES) { "Invalid vault total size $totalSize" }
    require(offset >= 0L && offset <= totalSize) { "Invalid vault offset $offset" }
    require(chunkSize <= VAULT_BLOB_CHUNK_BYTES) { "Vault chunk too large ($chunkSize bytes)" }
    require(offset + chunkSize <= totalSize) { "Vault chunk overruns total $totalSize" }
    require(chunkSize > 0 || totalSize == 0L) { "Empty vault chunk at $offset" }
    require(offset == 0L || offset <= stagedBytes) {
        "Non-contiguous vault chunk at $offset (staged $stagedBytes)"
    }
}
