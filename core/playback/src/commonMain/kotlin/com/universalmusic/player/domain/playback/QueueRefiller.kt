package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.continuation.QueueContinuation
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.queue.QueueController
import com.universalmusic.player.platform.PlaybackTrace
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Refills the tail of the queue a continuation was started with. Session thread only. */
internal class QueueRefiller(
    private val queue: QueueController,
    private val scope: CoroutineScope,
    private val sessionContext: CoroutineContext,
    private val workContext: CoroutineContext,
    private val onAppended: () -> Unit,
) {
    /** Dropped when other playback replaces the queue it was started with. */
    private var continuation: QueueContinuation? = null
    private var refill: Job? = null

    fun attach(continuation: QueueContinuation?) {
        this.continuation = continuation
    }

    fun drop() {
        continuation = null
        refill?.cancel()
        refill = null
    }

    /** Start a refill once the queue is down to the continuation's low-water mark. */
    fun refillIfLow() {
        val source = continuation ?: return
        if (refill?.isActive == true) return
        val snapshot = queue.queue.value
        if (snapshot.repeat == RepeatMode.ALL || snapshot.upcoming.size > source.lowWater) return
        refillTail()
    }

    /** The refill in flight, or a new one. Null when there is no continuation. */
    fun pendingRefill(): Job? = refill?.takeIf { it.isActive } ?: refillTail()

    /** Ask the continuation for a batch and append it at the tail. Null when there is no continuation. */
    private fun refillTail(): Job? {
        val source = continuation ?: return null
        val snapshot = queue.queue.value
        return scope.launch(sessionContext) {
            val batch = try {
                withContext(workContext) { source.nextBatch(snapshot) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                trace("continuation failed: ${error.message}")
                return@launch
            }
            if (continuation !== source) return@launch
            if (batch.isEmpty()) {
                trace("continuation finished")
                continuation = null
                return@launch
            }
            queue.appendTracks(batch)
            trace("continuation appended ${batch.size}; queue size=${queue.queue.value.items.size}")
            onAppended()
        }.also { refill = it }
    }

    private fun trace(message: String) = PlaybackTrace.log("Session", message)
}
