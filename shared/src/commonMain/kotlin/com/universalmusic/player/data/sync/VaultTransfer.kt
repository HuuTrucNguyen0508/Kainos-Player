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

/**
 * Exchange vault indexes with the hub, apply tombstones, and return planned blob copies
 * as [PendingVaultTransfer] for manual confirm (hearts / missing-file transfers).
 */
suspend fun planVaultTransfers(
    deviceId: String,
    store: HomeLanVaultStore,
    client: HomeLanSyncClient,
    heartsOnly: Boolean = false,
    heartedBasenamesLower: Set<String> = emptySet(),
): VaultExecuteResult {
    val local = store.buildIndex(deviceId)
        .applyHeartsOnlyVaultFilter(heartsOnly, heartedBasenamesLower)
    val remote = client.fetchRemoteVaultIndex(local).getOrThrow()
    val plan = planVaultUnion(local, remote)

    val tombs = mergeTombstones(local.tombstones, remote.tombstones)
    for (tomb in plan.applyTombstones) {
        store.delete(tomb.relPath)
    }
    store.saveTombstones(tombs)

    val pending = buildList {
        for (entry in plan.copyToLocal) {
            add(
                PendingVaultTransfer(
                    relPath = entry.relPath,
                    direction = VaultCopyDirection.TO_LOCAL,
                    sizeBytes = entry.sizeBytes,
                    mtimeMs = entry.mtimeMs,
                    contentHash = entry.contentHash,
                ),
            )
        }
        for (entry in plan.copyToRemote) {
            add(
                PendingVaultTransfer(
                    relPath = entry.relPath,
                    direction = VaultCopyDirection.TO_REMOTE,
                    sizeBytes = entry.sizeBytes,
                    mtimeMs = entry.mtimeMs,
                    contentHash = entry.contentHash,
                ),
            )
        }
    }

    val after = store.buildIndex(deviceId)
        .applyHeartsOnlyVaultFilter(heartsOnly, heartedBasenamesLower)
    return VaultExecuteResult(
        downloaded = 0,
        uploaded = 0,
        tombstonesApplied = plan.applyTombstones.size,
        conflicts = plan.conflicts,
        localIndexAfter = after,
        pendingTransfers = pending,
    )
}

/**
 * Copy one pending vault blob after the user confirms.
 */
suspend fun executeVaultTransfer(
    store: HomeLanVaultStore,
    client: HomeLanSyncClient,
    transfer: PendingVaultTransfer,
    onProgress: suspend (detail: String, transferred: Long, total: Long) -> Unit,
) {
    val total = transfer.sizeBytes.coerceAtLeast(1L)
    when (transfer.direction) {
        VaultCopyDirection.TO_LOCAL -> {
            val existing = store.localSize(transfer.relPath) ?: 0L
            var offset = when {
                existing in 1 until transfer.sizeBytes -> existing
                existing == transfer.sizeBytes && transfer.sizeBytes > 0L -> {
                    onProgress("Skip ${transfer.relPath}", total, total)
                    return
                }
                else -> 0L
            }
            while (offset < transfer.sizeBytes) {
                val chunkLen = minOf(VAULT_BLOB_CHUNK_BYTES.toLong(), transfer.sizeBytes - offset).toInt()
                val chunk = client.downloadBlob(transfer.relPath, offset, chunkLen).getOrThrow()
                if (chunk.isEmpty()) error("Empty chunk for ${transfer.relPath} at $offset")
                store.writeRange(transfer.relPath, offset, chunk, transfer.sizeBytes)
                offset += chunk.size
                onProgress("Downloading ${transfer.relPath}", offset, total)
            }
        }
        VaultCopyDirection.TO_REMOTE -> {
            if (transfer.sizeBytes <= 0L) {
                client.uploadBlob(transfer.relPath, 0L, 0L, ByteArray(0)).getOrThrow()
                return
            }
            var offset = 0L
            while (offset < transfer.sizeBytes) {
                val end = minOf(offset + VAULT_BLOB_CHUNK_BYTES - 1, transfer.sizeBytes - 1)
                val chunk = store.readRange(transfer.relPath, offset, end)
                if (chunk.isEmpty()) error("Cannot read ${transfer.relPath} at $offset")
                client.uploadBlob(transfer.relPath, offset, transfer.sizeBytes, chunk).getOrThrow()
                offset += chunk.size
                onProgress("Uploading ${transfer.relPath}", offset, total)
            }
        }
    }
}

/**
 * Legacy auto-copy path used by tests; production sync uses [planVaultTransfers] + confirm.
 */
suspend fun transferVaultFiles(
    deviceId: String,
    store: HomeLanVaultStore,
    client: HomeLanSyncClient,
    heartsOnly: Boolean = false,
    heartedBasenamesLower: Set<String> = emptySet(),
    onProgress: suspend (detail: String, transferred: Long, total: Long) -> Unit,
): VaultExecuteResult {
    val planned = planVaultTransfers(deviceId, store, client, heartsOnly, heartedBasenamesLower)
    var downloaded = 0
    var uploaded = 0
    val downloadBytes = planned.pendingTransfers
        .filter { it.direction == VaultCopyDirection.TO_LOCAL }
        .sumOf { it.sizeBytes.coerceAtLeast(0L) }
    val uploadBytes = planned.pendingTransfers
        .filter { it.direction == VaultCopyDirection.TO_REMOTE }
        .sumOf { it.sizeBytes.coerceAtLeast(0L) }
    val total = (downloadBytes + uploadBytes).coerceAtLeast(1L)
    var transferred = 0L
    for (pending in planned.pendingTransfers) {
        executeVaultTransfer(store, client, pending) { detail, done, _ ->
            onProgress(detail, transferred + done, total)
        }
        transferred += pending.sizeBytes.coerceAtLeast(0L)
        when (pending.direction) {
            VaultCopyDirection.TO_LOCAL -> downloaded += 1
            VaultCopyDirection.TO_REMOTE -> uploaded += 1
        }
    }
    val after = store.buildIndex(deviceId)
        .applyHeartsOnlyVaultFilter(heartsOnly, heartedBasenamesLower)
    client.fetchRemoteVaultIndex(after).getOrThrow()
    return VaultExecuteResult(
        downloaded = downloaded,
        uploaded = uploaded,
        tombstonesApplied = planned.tombstonesApplied,
        conflicts = planned.conflicts,
        localIndexAfter = after,
        pendingTransfers = emptyList(),
    )
}
