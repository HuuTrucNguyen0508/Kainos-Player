package com.universalmusic.player.data.sync

import kotlinx.serialization.Serializable

@Serializable
enum class SkippedCopyKind { VAULT, CACHED_AUDIO }

/**
 * A copy the user skipped. It stays skipped for this [peerDeviceId], [subject] (vault path or the
 * hearted owner's canonical id) and [direction] while the file keeps the same [fileIdentity].
 */
@Serializable
data class SkippedCopy(
    val peerDeviceId: String,
    val kind: SkippedCopyKind,
    val subject: String,
    val direction: VaultCopyDirection,
    val fileIdentity: String,
    val label: String,
    val sizeBytes: Long,
    val skippedAtMs: Long,
)

interface HomeSyncSkipStore {
    suspend fun skipped(peerDeviceId: String): List<SkippedCopy>
    suspend fun skip(copies: List<SkippedCopy>)
    suspend fun unskip(peerDeviceId: String, kind: SkippedCopyKind, subject: String, direction: VaultCopyDirection)
}

/**
 * What a skip of [this] remembers about the file: size and content hash. Android sends no hash, so
 * its files are told apart by size and modification time instead.
 */
val PendingVaultTransfer.fileIdentity: String
    get() = if (contentHash.isNotBlank()) "$sizeBytes:$contentHash" else "$sizeBytes:mtime=$mtimeMs"

/** A new cache file always gets a new token, so the token with the size names one file. */
val PendingCachedAudioTransfer.fileIdentity: String get() = "${offer.sizeBytes}:${offer.fileToken}"
