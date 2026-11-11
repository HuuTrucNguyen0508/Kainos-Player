package com.universalmusic.player.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Skips for this process only, where no database is wired. */
class MemoryHomeSyncSkipStore : HomeSyncSkipStore {
    private val mutex = Mutex()
    private val rows = mutableMapOf<List<String>, SkippedCopy>()

    override suspend fun skipped(peerDeviceId: String): List<SkippedCopy> = mutex.withLock {
        rows.values.filter { it.peerDeviceId == peerDeviceId }.sortedByDescending { it.skippedAtMs }
    }

    override suspend fun skip(copies: List<SkippedCopy>) = mutex.withLock {
        copies.forEach { rows[it.rowKey()] = it }
    }

    override suspend fun unskip(peerDeviceId: String, kind: SkippedCopyKind, subject: String, direction: VaultCopyDirection) {
        mutex.withLock { rows.remove(listOf(peerDeviceId, kind.name, subject, direction.name)) }
    }

    private fun SkippedCopy.rowKey() = listOf(peerDeviceId, kind.name, subject, direction.name)
}

/**
 * The copies the user skipped, per peer, so Sync now does not offer them again until the file
 * changes (a new size, hash or cache token) or the user un-skips one. Skipped copies the last plan
 * found are held here, so Un-skip offers them again without another sync.
 */
internal class CopySkips(private val store: HomeSyncSkipStore, private val clock: () -> Long) {
    private var heldVault = emptyList<PendingVaultTransfer>()
    private var heldAudio = emptyList<PendingCachedAudioTransfer>()

    class Planned(
        val vault: List<PendingVaultTransfer>,
        val audio: List<PendingCachedAudioTransfer>,
        val skipped: List<SkippedCopy>,
    )

    /**
     * Drops skipped copies from a fresh plan. A null list was not planned this sync, so its skips
     * are kept as they are; otherwise skips that no planned copy matches any more are forgotten.
     */
    suspend fun apply(
        peer: String,
        vault: List<PendingVaultTransfer>?,
        audio: List<PendingCachedAudioTransfer>?,
    ): Planned {
        val stored = store.skipped(peer)
        val planned = vault.orEmpty().map { it.skipKey(peer) } + audio.orEmpty().map { it.skipKey(peer) }
        val obsolete = stored.filter { skip ->
            val kindPlanned = if (skip.kind == SkippedCopyKind.VAULT) vault != null else audio != null
            kindPlanned && planned.none { it == skip.key() }
        }
        obsolete.forEach { store.unskip(peer, it.kind, it.subject, it.direction) }
        val live = (stored - obsolete.toSet()).mapTo(HashSet()) { it.key() }
        heldVault = vault.orEmpty().filter { it.skipKey(peer) in live }
        heldAudio = audio.orEmpty().filter { it.skipKey(peer) in live }
        return Planned(
            vault = vault.orEmpty().filterNot { it.skipKey(peer) in live },
            audio = audio.orEmpty().filterNot { it.skipKey(peer) in live },
            skipped = stored - obsolete.toSet(),
        )
    }

    suspend fun skip(peer: String, vault: List<PendingVaultTransfer>, audio: List<PendingCachedAudioTransfer>): List<SkippedCopy> {
        val now = clock()
        store.skip(
            vault.map { SkippedCopy(peer, SkippedCopyKind.VAULT, it.relPath, it.direction, it.fileIdentity, it.label, it.sizeBytes, now) } +
                audio.map {
                    SkippedCopy(peer, SkippedCopyKind.CACHED_AUDIO, it.offer.ownerCanonicalId, it.direction, it.fileIdentity, it.label, it.offer.sizeBytes, now)
                },
        )
        heldVault = heldVault + vault
        heldAudio = heldAudio + audio
        return store.skipped(peer)
    }

    class Unskipped(
        val vault: PendingVaultTransfer?,
        val audio: PendingCachedAudioTransfer?,
        val skipped: List<SkippedCopy>,
    )

    /** Forgets one skip; the copy it held comes back when the last plan found it, else at the next sync. */
    suspend fun unskip(peer: String, kind: SkippedCopyKind, subject: String, direction: VaultCopyDirection): Unskipped {
        store.unskip(peer, kind, subject, direction)
        val vault = heldVault.firstOrNull {
            kind == SkippedCopyKind.VAULT && it.relPath == subject && it.direction == direction
        }
        val audio = heldAudio.firstOrNull {
            kind == SkippedCopyKind.CACHED_AUDIO && it.offer.ownerCanonicalId == subject && it.direction == direction
        }
        vault?.let { heldVault = heldVault - it }
        audio?.let { heldAudio = heldAudio - it }
        return Unskipped(vault, audio, store.skipped(peer))
    }

    suspend fun list(peer: String): List<SkippedCopy> = store.skipped(peer)

    private fun SkippedCopy.key() = SkipKey(peerDeviceId, kind, subject, direction, fileIdentity)

    private fun PendingVaultTransfer.skipKey(peer: String) =
        SkipKey(peer, SkippedCopyKind.VAULT, relPath, direction, fileIdentity)

    private fun PendingCachedAudioTransfer.skipKey(peer: String) =
        SkipKey(peer, SkippedCopyKind.CACHED_AUDIO, offer.ownerCanonicalId, direction, fileIdentity)

    private data class SkipKey(
        val peer: String,
        val kind: SkippedCopyKind,
        val subject: String,
        val direction: VaultCopyDirection,
        val fileIdentity: String,
    )
}
