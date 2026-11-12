package com.universalmusic.player.data.sync

data class VaultExecuteResult(
    val downloaded: Int,
    val uploaded: Int,
    val tombstonesApplied: Int,
    val conflicts: List<VaultConflict>,
    val localIndexAfter: VaultIndexDocument,
    val pendingTransfers: List<PendingVaultTransfer> = emptyList(),
)

@kotlinx.serialization.Serializable
data class PendingVaultTransfer(
    val relPath: String,
    val direction: VaultCopyDirection,
    val sizeBytes: Long,
    val mtimeMs: Long = 0L,
    val contentHash: String = "",
) {
    val label: String
        get() = when (direction) {
            VaultCopyDirection.TO_LOCAL -> "Download $relPath"
            VaultCopyDirection.TO_REMOTE -> "Upload $relPath"
        }
}

/** Names one pending copy for Transfer or Skip: a vault file or a hearted track's offline copy. */
sealed interface PendingCopyKey {
    data class Vault(val relPath: String, val direction: VaultCopyDirection) : PendingCopyKey
    data class CachedAudio(val ownerCanonicalId: String, val direction: VaultCopyDirection) : PendingCopyKey
}

val PendingVaultTransfer.key: PendingCopyKey get() = PendingCopyKey.Vault(relPath, direction)
val PendingCachedAudioTransfer.key: PendingCopyKey get() = PendingCopyKey.CachedAudio(offer.ownerCanonicalId, direction)
