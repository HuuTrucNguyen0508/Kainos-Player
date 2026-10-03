package com.universalmusic.player.data.sync

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
 *
 * Downloads are staged by [HomeLanVaultStore.writeRange] and only published once every byte
 * arrived, so a failure leaves any existing local copy untouched. Every chunk must be exactly the
 * requested size: an empty or short chunk (peer file changed, truncated read, bad response) fails
 * the transfer instead of silently reporting success.
 *
 * [replaceExisting] is for conflict resolution: it skips the "same size already present" shortcut
 * and restarts staging from zero instead of resuming a partial of possibly different content.
 */
suspend fun executeVaultTransfer(
    store: HomeLanVaultStore,
    client: HomeLanSyncClient,
    transfer: PendingVaultTransfer,
    replaceExisting: Boolean = false,
    onProgress: suspend (detail: String, transferred: Long, total: Long) -> Unit,
) {
    val size = transfer.sizeBytes
    require(size in 0L..VAULT_MAX_FILE_BYTES) { "Invalid size $size for ${transfer.relPath}" }
    val total = size.coerceAtLeast(1L)
    when (transfer.direction) {
        VaultCopyDirection.TO_LOCAL -> {
            if (!replaceExisting && size > 0L && store.localSize(transfer.relPath) == size) {
                onProgress("Skip ${transfer.relPath}", total, total)
                return
            }
            if (size == 0L) {
                store.writeRange(transfer.relPath, 0L, ByteArray(0), 0L)
                onProgress("Downloading ${transfer.relPath}", total, total)
                return
            }
            val staged = if (replaceExisting) 0L else store.stagedSize(transfer.relPath) ?: 0L
            var offset = if (staged in 1 until size) staged else 0L
            var published = false
            while (offset < size) {
                val chunkLen = minOf(VAULT_BLOB_CHUNK_BYTES.toLong(), size - offset).toInt()
                val chunk = client.downloadBlob(transfer.relPath, offset, chunkLen).getOrThrow()
                if (chunk.isEmpty()) error("Empty chunk for ${transfer.relPath} at $offset")
                if (chunk.size != chunkLen) {
                    error("Short chunk for ${transfer.relPath} at $offset (${chunk.size} of $chunkLen bytes)")
                }
                published = store.writeRange(transfer.relPath, offset, chunk, size)
                offset += chunk.size
                onProgress("Downloading ${transfer.relPath}", offset, total)
            }
            check(published && store.localSize(transfer.relPath) == size) {
                "Download of ${transfer.relPath} did not publish a complete file"
            }
        }
        VaultCopyDirection.TO_REMOTE -> {
            if (size == 0L) {
                client.uploadBlob(transfer.relPath, 0L, 0L, ByteArray(0)).getOrThrow()
                return
            }
            val localSize = store.localSize(transfer.relPath)
                ?: error("Local file missing for ${transfer.relPath}")
            check(localSize == size) {
                "Local ${transfer.relPath} changed size ($localSize, expected $size); sync again"
            }
            var offset = 0L
            while (offset < size) {
                val end = minOf(offset + VAULT_BLOB_CHUNK_BYTES - 1, size - 1)
                val expected = (end - offset + 1).toInt()
                val chunk = store.readRange(transfer.relPath, offset, end)
                if (chunk.isEmpty()) error("Cannot read ${transfer.relPath} at $offset")
                if (chunk.size != expected) {
                    error("Short read for ${transfer.relPath} at $offset (${chunk.size} of $expected bytes)")
                }
                client.uploadBlob(transfer.relPath, offset, size, chunk).getOrThrow()
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
