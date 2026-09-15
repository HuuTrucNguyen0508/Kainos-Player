package com.universalmusic.player.data.sync

data class VaultExecuteResult(
    val downloaded: Int,
    val uploaded: Int,
    val tombstonesApplied: Int,
    val conflicts: List<VaultConflict>,
    val localIndexAfter: VaultIndexDocument,
)

/**
 * Exchange vault indexes with the hub, apply tombstones, then copy missing blobs both ways
 * with byte-range resume.
 */
suspend fun transferVaultFiles(
    deviceId: String,
    store: HomeLanVaultStore,
    client: HomeLanSyncClient,
    heartsOnly: Boolean = false,
    heartedBasenamesLower: Set<String> = emptySet(),
    onProgress: suspend (detail: String, transferred: Long, total: Long) -> Unit,
): VaultExecuteResult {
    val local = store.buildIndex(deviceId)
        .applyHeartsOnlyVaultFilter(heartsOnly, heartedBasenamesLower)
    // Hub filters its own index by its hearts; do not re-filter by this device's names.
    val remote = client.fetchRemoteVaultIndex(local).getOrThrow()
    val plan = planVaultUnion(local, remote)

    val downloadBytes = plan.copyToLocal.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    val uploadBytes = plan.copyToRemote.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    val total = (downloadBytes + uploadBytes).coerceAtLeast(1L)
    var transferred = 0L

    val tombs = mergeTombstones(local.tombstones, remote.tombstones)
    for (tomb in plan.applyTombstones) {
        store.delete(tomb.relPath)
    }
    store.saveTombstones(tombs)

    for (entry in plan.copyToLocal) {
        val existing = store.localSize(entry.relPath) ?: 0L
        var offset = when {
            existing in 1 until entry.sizeBytes -> existing
            existing == entry.sizeBytes && entry.sizeBytes > 0L -> {
                transferred += entry.sizeBytes
                onProgress("Skip ${entry.relPath}", transferred, total)
                continue
            }
            else -> 0L
        }
        while (offset < entry.sizeBytes) {
            val chunkLen = minOf(VAULT_BLOB_CHUNK_BYTES.toLong(), entry.sizeBytes - offset).toInt()
            val chunk = client.downloadBlob(entry.relPath, offset, chunkLen).getOrThrow()
            if (chunk.isEmpty()) error("Empty chunk for ${entry.relPath} at $offset")
            store.writeRange(entry.relPath, offset, chunk, entry.sizeBytes)
            offset += chunk.size
            transferred += chunk.size
            onProgress("Downloading ${entry.relPath}", transferred, total)
        }
    }

    for (entry in plan.copyToRemote) {
        if (entry.sizeBytes <= 0L) {
            client.uploadBlob(entry.relPath, 0L, 0L, ByteArray(0)).getOrThrow()
            continue
        }
        var offset = 0L
        while (offset < entry.sizeBytes) {
            val end = minOf(offset + VAULT_BLOB_CHUNK_BYTES - 1, entry.sizeBytes - 1)
            val chunk = store.readRange(entry.relPath, offset, end)
            if (chunk.isEmpty()) error("Cannot read ${entry.relPath} at $offset")
            client.uploadBlob(entry.relPath, offset, entry.sizeBytes, chunk).getOrThrow()
            offset += chunk.size
            transferred += chunk.size
            onProgress("Uploading ${entry.relPath}", transferred, total)
        }
    }

    val after = store.buildIndex(deviceId)
        .applyHeartsOnlyVaultFilter(heartsOnly, heartedBasenamesLower)
    client.fetchRemoteVaultIndex(after).getOrThrow()
    return VaultExecuteResult(
        downloaded = plan.copyToLocal.size,
        uploaded = plan.copyToRemote.size,
        tombstonesApplied = plan.applyTombstones.size,
        conflicts = plan.conflicts,
        localIndexAfter = after,
    )
}
