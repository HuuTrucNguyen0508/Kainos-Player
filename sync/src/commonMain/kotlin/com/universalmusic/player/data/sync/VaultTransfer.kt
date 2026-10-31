package com.universalmusic.player.data.sync

/** Stores that can validate a completed stage before replacing the destination. */
interface CheckedVaultStore : HomeLanVaultStore {
    suspend fun writeCheckedRange(
        relPath: String, offset: Long, data: ByteArray, totalSize: Long,
        expectedHash: String, beforePublish: suspend () -> Unit,
    ): Boolean
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
 *
 * Downloads are staged by [HomeLanVaultStore.writeRange] and only published once every byte
 * arrived, so a failure leaves any existing local copy untouched. Every chunk must be exactly the
 * requested size: an empty or short chunk (peer file changed, truncated read, bad response) fails
 * the transfer instead of silently reporting success.
 *
 * Downloads always restart because legacy peers lack immutable range tokens.
 * [replaceExisting] is retained for callers confirming a conflict replacement.
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
            // Old peers have no immutable range token. Never reuse bytes from an earlier attempt.
            store.discardStaged(transfer.relPath)
            var sourceIdentity: VaultFileEntry? = null
            suspend fun checkSource() {
                val entry = client.fetchRemoteVaultIndex(VaultIndexDocument("transfer-check"))
                    .getOrThrow().entries.firstOrNull { it.relPath == transfer.relPath }
                check(entry != null) { "Source ${transfer.relPath} is no longer advertised; sync again" }
                if (sourceIdentity == null) sourceIdentity = entry
                check(sourceIdentity == entry) { "Source ${transfer.relPath} changed; sync again" }
                if (entry != null) check(entry.sizeBytes == size &&
                    (transfer.mtimeMs == 0L || entry.mtimeMs == transfer.mtimeMs) &&
                    (transfer.contentHash.isBlank() || entry.contentHash.isBlank() || entry.contentHash == transfer.contentHash)
                ) { "Source ${transfer.relPath} changed; sync again" }
            }
            checkSource()
            suspend fun write(offset: Long, chunk: ByteArray): Boolean {
                val checked = store as? CheckedVaultStore
                if (checked != null) return checked.writeCheckedRange(
                    transfer.relPath, offset, chunk, size, transfer.contentHash, ::checkSource,
                )
                check(transfer.contentHash.isBlank()) { "Vault store cannot verify content hash" }
                checkSource()
                return store.writeRange(transfer.relPath, offset, chunk, size)
            }
            if (size == 0L) {
                check(write(0L, ByteArray(0)))
                onProgress("Downloading ${transfer.relPath}", total, total)
                return
            }
            var offset = 0L
            var published = false
            while (offset < size) {
                val chunkLen = minOf(VAULT_BLOB_CHUNK_BYTES.toLong(), size - offset).toInt()
                checkSource()
                val chunk = client.downloadBlob(transfer.relPath, offset, chunkLen).getOrThrow()
                if (chunk.isEmpty()) error("Empty chunk for ${transfer.relPath} at $offset")
                if (chunk.size != chunkLen) {
                    error("Short chunk for ${transfer.relPath} at $offset (${chunk.size} of $chunkLen bytes)")
                }
                checkSource()
                published = write(offset, chunk)
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
