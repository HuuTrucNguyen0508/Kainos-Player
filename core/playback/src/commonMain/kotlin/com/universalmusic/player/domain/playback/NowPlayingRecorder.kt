package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.session.HandoffClaim
import com.universalmusic.player.data.session.HandoffRequest
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.RemoteSession
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningRestore
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.ListeningStore
import com.universalmusic.player.data.session.LocalListeningUpdate
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.persistedItems
import com.universalmusic.player.data.session.toSessionSnapshot
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.secureRandomBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

sealed interface FlushResult {
    /** The private snapshot is durable. [exportUnavailable] is set when the newest local state could not be exported. */
    data class Committed(val exportUnavailable: ProjectionFailure? = null) : FlushResult
    data class Failed(val cause: Throwable) : FlushResult
}

internal class SessionCapture(
    val sequence: Long,
    val queue: PlaybackQueue,
    val positionMs: Long,
    val isPlaying: Boolean,
    val capturedAtMs: Long,
    val lastActiveAtMs: Long?,
    val origin: PublicationOrigin,
    val remoteSession: RemoteSession?,
)

private class PersistenceIntent(val private: SessionCapture, val local: SessionCapture?) {
    fun then(next: PersistenceIntent) = PersistenceIntent(next.private, next.local ?: local)
}

private data class RecorderStatus(
    val committedThrough: Long = 0,
    val failedThrough: Long = 0,
    val failure: Throwable? = null,
    val exportUnavailable: ProjectionFailure? = null,
)

/**
 * Persists one [PlayerSession]'s captures in order: at most one commit in flight and one merged
 * intent waiting. Projection and hashing run on [workerContext].
 */
class NowPlayingRecorder(
    private val store: ListeningStore,
    private val scope: CoroutineScope,
    private val workerContext: CoroutineContext = Dispatchers.Default,
    private val clock: () -> Long = ::currentTimeMillis,
    private val writerId: String = newWriterId(),
) {
    private var sequence = 0L
    private val writes = Mutex()
    private val pending = MutableStateFlow<PersistenceIntent?>(null)
    private val status = MutableStateFlow(RecorderStatus())
    private var worker: Job? = null
    private val persistedItems = IdentityCache<List<QueueItem>, List<PersistedQueueItem>>()
    private val portableQueues = IdentityCache<List<QueueItem>, QueueProjection>()

    internal suspend fun readRestore(): ListeningRestore? = try {
        store.readRestore()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        trace("restore read failed: ${error.message}")
        null
    }

    internal fun start() {
        if (worker == null) worker = scope.launch(workerContext) { run() }
    }

    internal fun offer(
        queue: PlaybackQueue,
        positionMs: Long,
        isPlaying: Boolean,
        capturedAtMs: Long,
        lastActiveAtMs: Long?,
        origin: PublicationOrigin,
        local: Boolean,
        remoteSession: RemoteSession?,
    ): Long {
        val capture = SessionCapture(++sequence, queue, positionMs, isPlaying, capturedAtMs, lastActiveAtMs, origin, remoteSession)
        val intent = PersistenceIntent(capture, capture.takeIf { local })
        pending.update { waiting -> waiting?.then(intent) ?: intent }
        return capture.sequence
    }

    internal suspend fun awaitCommitted(sequence: Long): FlushResult {
        val settled = status.first { it.committedThrough >= sequence || it.failedThrough >= sequence }
        return if (settled.committedThrough >= sequence) {
            FlushResult.Committed(settled.exportUnavailable)
        } else {
            FlushResult.Failed(checkNotNull(settled.failure))
        }
    }

    internal suspend fun finalizeHandoff(
        exchange: ListeningExchangeStore,
        request: HandoffRequest,
        accept: suspend () -> ListeningCommit?,
    ): HandoffClaim = writes.withLock {
        withContext(NonCancellable) {
            var accepted: ListeningCommit? = null
            val result = exchange.claimHandoff(request) { accept().also { accepted = it } }
            if (result == HandoffClaim.CLAIMED) {
                val committed = checkNotNull(accepted)
                status.update { it.copy(committedThrough = maxOf(it.committedThrough, committed.sequence)) }
            }
            result
        }
    }

    internal fun remoteCommit(snapshot: SessionSnapshot): ListeningCommit = ListeningCommit(
        writerId, ++sequence, snapshot, PublicationOrigin.REMOTE_SUPPRESSED, local = null, committedAtMs = clock(),
    )

    private suspend fun run() {
        var inFlight: PersistenceIntent? = null
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            if (inFlight == null) pending.first { it != null }
            val next = pending.getAndUpdate { null }
            val intent = when {
                inFlight == null -> checkNotNull(next)
                next == null -> inFlight
                else -> inFlight.then(next)
            }
            inFlight = intent
            val commit = try {
                prepare(intent).also { commit -> writes.withLock { store.commit(commit) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.update { it.copy(failedThrough = intent.private.sequence, failure = error) }
                trace("commit ${intent.private.sequence} failed: ${error.message}; retrying in ${backoffMs}ms")
                withTimeoutOrNull(backoffMs) { pending.first { it != null } }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            inFlight = null
            backoffMs = INITIAL_BACKOFF_MS
            status.update { previous ->
                RecorderStatus(
                    committedThrough = maxOf(previous.committedThrough, commit.sequence),
                    exportUnavailable = when (val local = commit.local) {
                        is LocalListeningUpdate.Unavailable -> local.reason
                        is LocalListeningUpdate.Publish -> null
                        null -> previous.exportUnavailable
                    },
                )
            }
        }
    }

    private fun prepare(intent: PersistenceIntent): ListeningCommit {
        val private = intent.private
        val local = intent.local?.let { capture ->
            val projection = portableQueues.getOrPut(capture.queue.items) {
                PortableListeningQueue.project(persisted(capture.queue))
            }
            when (projection) {
                is QueueProjection.Complete -> LocalListeningUpdate.Publish(
                    capture.sequence,
                    capture.lastActiveAtMs,
                    ListeningState.create(
                        projection.queue,
                        capture.queue.current?.id,
                        capture.positionMs.coerceAtLeast(0L),
                        capture.capturedAtMs,
                        capture.isPlaying,
                    ),
                )
                is QueueProjection.Unsupported -> {
                    trace("local export unavailable: ${projection.reason} at entry ${projection.itemId}")
                    LocalListeningUpdate.Unavailable(capture.sequence, capture.lastActiveAtMs, projection.reason)
                }
            }
        }
        return ListeningCommit(
            writerId = writerId,
            sequence = private.sequence,
            snapshot = private.queue.toSessionSnapshot(persisted(private.queue), private.positionMs).copy(remoteSession = private.remoteSession),
            origin = private.origin,
            local = local,
            committedAtMs = clock(),
        )
    }

    private fun persisted(queue: PlaybackQueue): List<PersistedQueueItem> =
        persistedItems.getOrPut(queue.items) { queue.persistedItems(clock()) }

    private fun trace(message: String) = PlaybackTrace.log("Recorder", message)

    private companion object {
        const val INITIAL_BACKOFF_MS = 250L
        const val MAX_BACKOFF_MS = 30_000L
    }
}

private class IdentityCache<K : Any, V>(private val capacity: Int = 2) {
    private val entries = ArrayDeque<Pair<K, V>>()

    fun getOrPut(key: K, compute: () -> V): V {
        entries.firstOrNull { it.first === key }?.let { return it.second }
        return compute().also { value ->
            entries.addFirst(key to value)
            if (entries.size > capacity) entries.removeLast()
        }
    }
}

private fun newWriterId(): String =
    secureRandomBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
