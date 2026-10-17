package com.universalmusic.player.data.db

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningRestore
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.ListeningStore
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.LocalListeningPublication
import com.universalmusic.player.data.session.LocalListeningUpdate
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/** Receipts outlive their writer only long enough to cover any retry; older ones are dropped. */
private const val RECEIPT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

/**
 * The only runtime writer of the private restore snapshot and this device's listening publication.
 * Each commit is one transaction, so a reader never pairs a row with another commit's queue.
 */
class DbListeningStore(
    private val storage: KainosStorage,
) : ListeningStore {
    private val writeMutex = Mutex()
    private var decodedQueue: StoredQueue? = null
    private val prunedFor = mutableSetOf<String>()

    override suspend fun readRestore(): ListeningRestore =
        storage.read { db -> ListeningRestore(db.readSessionSnapshot(), db.readSessionOrigin()) }

    override suspend fun commit(commit: ListeningCommit) = writeMutex.withLock {
        val prune = commit.writerId !in prunedFor
        decodedQueue = storage.write { db -> db.commitListening(commit, decodedQueue, prune) }
        prunedFor += commit.writerId
    }

    override suspend fun readLocalExport(): LocalListeningExport? = storage.read { it.readLocalExport() }
}

internal fun KainosDatabase.commitListening(
    commit: ListeningCommit,
    cached: StoredQueue?,
    pruneReceipts: Boolean,
): StoredQueue? {
    val queries = nowPlayingQueries
    if (pruneReceipts) queries.deleteReceiptsBefore(commit.committedAtMs - RECEIPT_RETENTION_MS)
    val receipt = queries.selectReceipt(commit.writerId).executeAsOneOrNull()
    val committedPrivate = receipt?.private_sequence ?: 0L
    val committedLocal = receipt?.local_sequence ?: 0L
    val stored = if (commit.sequence > committedPrivate) {
        writeSessionSnapshot(commit.snapshot, cached, commit.origin)
    } else {
        cached
    }
    val local = commit.local?.takeIf { it.sequence > committedLocal }
    if (local != null) publishLocal(local, commit.committedAtMs)
    queries.upsertReceipt(
        commit.writerId,
        maxOf(commit.sequence, committedPrivate),
        maxOf(local?.sequence ?: 0L, committedLocal),
        commit.committedAtMs,
    )
    return stored
}

private fun KainosDatabase.publishLocal(update: LocalListeningUpdate, committedAtMs: Long) {
    val queries = nowPlayingQueries
    val export = queries.selectExport().executeAsOneOrNull()
    val previous = export?.revision ?: 0L
    check(previous < Long.MAX_VALUE) { "Listening publication revisions exhausted" }
    val revision = previous + 1
    val lastActiveAtMs = update.lastActiveAtMs ?: export?.last_active_at_ms ?: committedAtMs
    when (update) {
        is LocalListeningUpdate.Publish -> {
            val state = update.state
            val hash = state.queue.hash.hex
            if (queries.selectLocalQueueHash().executeAsOneOrNull() != hash) {
                queries.upsertLocalQueue(hash, state.queue.encodeJson())
            }
            queries.upsertLocal(
                revision,
                lastActiveAtMs,
                state.current?.itemId,
                state.current?.let { storageJson.encodeToString(it.track.track) },
                state.positionMs,
                state.positionAtMs,
                state.isPlaying,
                hash,
            )
            queries.upsertExport(revision, lastActiveAtMs, null)
        }
        is LocalListeningUpdate.Unavailable -> queries.upsertExport(revision, lastActiveAtMs, update.reason.name)
    }
}

internal fun KainosDatabase.readLocalExport(): LocalListeningExport? {
    val queries = nowPlayingQueries
    val export = queries.selectExport().executeAsOneOrNull() ?: return null
    val revision = PublicationRevision(export.revision)
    export.unavailable_reason?.let { stored ->
        val reason = ProjectionFailure.entries.firstOrNull { it.name == stored } ?: ProjectionFailure.UNSUPPORTED_TRACK
        return LocalListeningExport.Unavailable(revision, export.last_active_at_ms, reason)
    }
    val row = queries.selectLocal().executeAsOne()
    check(row.revision == export.revision) { "Local listening row ${row.revision} is not export ${export.revision}" }
    val stored = queries.selectLocalQueue().executeAsOne()
    check(stored.queue_hash == row.queue_hash) { "Local listening queue does not match its row" }
    val queue = PortableListeningQueue.decode(stored.queue_json, QueueHash.parse(row.queue_hash))
    val state = ListeningState.create(queue, row.queue_item_id, row.position_ms, row.position_at_ms, row.is_playing)
    row.track_json?.let { json ->
        check(storageJson.decodeFromString<PersistedTrack>(json).canonicalId == state.current?.track?.identity) {
            "Local listening track does not match its queue entry"
        }
    }
    return LocalListeningExport.Available(LocalListeningPublication(revision, row.last_active_at_ms, state))
}
