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

    /** Inclusive end; empty when file missing. */
    suspend fun readRange(relPath: String, start: Long, endInclusive: Long): ByteArray

    /**
     * Writes [data] at [offset]. When [offset] is 0 and [totalSize] is set, truncates/creates
     * to that size when the platform supports it. Resume uses offset > 0.
     */
    suspend fun writeRange(relPath: String, offset: Long, data: ByteArray, totalSize: Long)

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
