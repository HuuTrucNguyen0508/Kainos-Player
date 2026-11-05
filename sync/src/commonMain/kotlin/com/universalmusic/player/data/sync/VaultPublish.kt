package com.universalmusic.player.data.sync

/** A document as its folder reports it. Some providers report no size. */
data class VaultDocument(val sizeBytes: Long?)

/** The sibling documents of one vault folder, addressed by display name. */
interface VaultFolder {
    /** Null when [name] is absent. Throws when the folder cannot be read. */
    fun find(name: String): VaultDocument?

    /** May refuse, or land on another name. Callers read the outcome back through [find]. */
    fun rename(from: String, to: String)

    fun create(name: String)

    /** Replaces the content of [to], creating it when absent. */
    fun copy(from: String, to: String)

    fun delete(name: String)

    fun sha256(name: String): String
}

private const val VAULT_BACKUP_SUFFIX = ".kainos-old"
private const val VAULT_UNVERIFIED_SUFFIX = ".kainos-unverified"

/** The vault file whose publish left the document [name] behind, or null for any other document. */
fun interruptedVaultFile(name: String): String? =
    listOf(VAULT_BACKUP_SUFFIX, VAULT_UNVERIFIED_SUFFIX).firstOrNull(name::endsWith)?.let(name::removeSuffix)

/**
 * Replaces [fileName] with its complete staged sibling, for folders with no atomic replace.
 *
 * Two siblings journal the publish. The backup holds the old file. The unverified marker exists
 * only while [fileName] may be incomplete, is created only after the backup is complete, and its
 * deletion is the commit point. A missing backup under the marker means there was no old file.
 *
 * On failure [fileName] is restored and throws. After process death [recoverVaultFile] restores it.
 */
fun publishVaultFile(folder: VaultFolder, fileName: String, totalSize: Long) {
    val staged = vaultStagingName(fileName)
    val backup = fileName + VAULT_BACKUP_SUFFIX
    val unverified = fileName + VAULT_UNVERIFIED_SUFFIX
    recoverVaultFile(folder, fileName)
    checkNotNull(folder.find(staged)) { "Staged vault file for $fileName vanished during publish" }
    try {
        if (folder.find(fileName) != null) {
            runCatching { folder.rename(fileName, backup) }
            if (folder.find(fileName) != null) folder.copyVerified(fileName, backup)
            checkNotNull(folder.find(backup)) { "Cannot back up $fileName" }
        }
        folder.create(unverified)
        if (folder.find(fileName) == null) runCatching { folder.rename(staged, fileName) }
        if (folder.find(staged) != null) folder.copyVerified(staged, fileName)
        val published = checkNotNull(folder.find(fileName)) { "Publish of $fileName left no file" }
        check(published.sizeBytes == null || published.sizeBytes == totalSize) {
            "Publish of $fileName holds ${published.sizeBytes} of $totalSize bytes"
        }
        folder.delete(unverified)
    } catch (failure: Throwable) {
        runCatching { recoverVaultFile(folder, fileName) }
        throw failure
    }
    runCatching { if (folder.find(staged) != null) folder.delete(staged) }
    runCatching { if (folder.find(backup) != null) folder.delete(backup) }
}

/**
 * Settles whatever an interrupted publish of [fileName] left, keeping the old file unless the
 * publish committed. Staged bytes stay: the next transfer restarts over them.
 */
fun recoverVaultFile(folder: VaultFolder, fileName: String) {
    val backup = fileName + VAULT_BACKUP_SUFFIX
    val unverified = fileName + VAULT_UNVERIFIED_SUFFIX
    val hasBackup = folder.find(backup) != null
    if (hasBackup && folder.find(fileName) == null && folder.find(unverified) == null) {
        folder.create(unverified)
    }
    if (folder.find(unverified) != null) {
        when {
            hasBackup -> folder.copyVerified(backup, fileName)
            folder.find(fileName) != null -> folder.delete(fileName)
        }
        folder.delete(unverified)
    }
    if (hasBackup) folder.delete(backup)
}

private fun VaultFolder.copyVerified(from: String, to: String) {
    copy(from, to)
    check(sha256(to) == sha256(from)) { "Copy of $from to $to does not match" }
}
