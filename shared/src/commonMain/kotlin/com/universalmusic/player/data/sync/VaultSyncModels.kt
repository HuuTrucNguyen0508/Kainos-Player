package com.universalmusic.player.data.sync

import kotlinx.serialization.Serializable

@Serializable
data class VaultFileEntry(
    /** Path relative to the vault root, using `/` separators, no leading slash. */
    val relPath: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
    /** Hex-encoded content hash when known; empty if not yet computed. */
    val contentHash: String = "",
)

@Serializable
data class VaultTombstone(
    val relPath: String,
    val deletedAtMs: Long,
    val deviceId: String,
    val revision: Long,
)

@Serializable
data class VaultIndexDocument(
    val deviceId: String,
    val entries: List<VaultFileEntry> = emptyList(),
    val tombstones: List<VaultTombstone> = emptyList(),
)

@Serializable
enum class VaultCopyDirection {
    TO_LOCAL,
    TO_REMOTE,
}

@Serializable
data class VaultSyncPlan(
    val copyToLocal: List<VaultFileEntry> = emptyList(),
    val copyToRemote: List<VaultFileEntry> = emptyList(),
    /** Tombstones that both sides should honor (delete local file if present). */
    val applyTombstones: List<VaultTombstone> = emptyList(),
    val conflicts: List<VaultConflict> = emptyList(),
)

@Serializable
data class VaultConflict(
    val relPath: String,
    val local: VaultFileEntry,
    val remote: VaultFileEntry,
)

/**
 * Union sync without inferring deletes from absence.
 * Tombstones win over live files when tombstone revision is newer than both file mtimes
 * (or when no hash match is required — v1 uses path + tombstone revision).
 */
fun planVaultUnion(
    local: VaultIndexDocument,
    remote: VaultIndexDocument,
): VaultSyncPlan {
    val localFiles = local.entries.associateBy { it.relPath.normalizeVaultRelPath() }
    val remoteFiles = remote.entries.associateBy { it.relPath.normalizeVaultRelPath() }
    val tombstones = mergeTombstones(local.tombstones, remote.tombstones)
        .associateBy { it.relPath.normalizeVaultRelPath() }

    val copyToLocal = mutableListOf<VaultFileEntry>()
    val copyToRemote = mutableListOf<VaultFileEntry>()
    val conflicts = mutableListOf<VaultConflict>()
    val apply = mutableListOf<VaultTombstone>()

    val allPaths = (localFiles.keys + remoteFiles.keys + tombstones.keys).toSortedSet()
    for (path in allPaths) {
        val tomb = tombstones[path]
        val l = localFiles[path]
        val r = remoteFiles[path]
        if (tomb != null) {
            val fileNewerThanTomb = listOfNotNull(l, r).any { it.mtimeMs > tomb.revision }
            if (!fileNewerThanTomb) {
                apply += tomb
                continue
            }
            // A live file newer than tombstone supersedes the tombstone (re-added track).
        }
        when {
            l == null && r != null -> copyToLocal += r
            r == null && l != null -> copyToRemote += l
            l != null && r != null -> {
                if (sameContent(l, r)) {
                    // nothing
                } else {
                    conflicts += VaultConflict(path, l, r)
                }
            }
        }
    }
    return VaultSyncPlan(
        copyToLocal = copyToLocal,
        copyToRemote = copyToRemote,
        applyTombstones = apply.sortedBy { it.relPath },
        conflicts = conflicts.sortedBy { it.relPath },
    )
}

fun mergeTombstones(local: List<VaultTombstone>, remote: List<VaultTombstone>): List<VaultTombstone> {
    val byPath = LinkedHashMap<String, VaultTombstone>()
    for (t in local + remote) {
        val path = t.relPath.normalizeVaultRelPath()
        val normalized = t.copy(relPath = path)
        val existing = byPath[path]
        if (existing == null ||
            normalized.revision > existing.revision ||
            (normalized.revision == existing.revision && normalized.deviceId > existing.deviceId)
        ) {
            byPath[path] = normalized
        }
    }
    return byPath.values.sortedBy { it.relPath }
}

fun sameContent(a: VaultFileEntry, b: VaultFileEntry): Boolean {
    if (a.contentHash.isNotBlank() && b.contentHash.isNotBlank()) {
        return a.contentHash == b.contentHash
    }
    return a.sizeBytes == b.sizeBytes && a.mtimeMs == b.mtimeMs
}

fun String.normalizeVaultRelPath(): String =
    trim()
        .removePrefix("/")
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")
