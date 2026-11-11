package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.continuation.QueueContinuation
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.queue.QueueController
import com.universalmusic.player.platform.PlaybackTrace
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Refills the tail of the queue a continuation was started with. Session thread only. */
internal class QueueRefiller(
    private val queue: QueueController,
    private val scope: CoroutineScope,
    private val sessionContext: CoroutineContext,
    private val workContext: CoroutineContext,
    /** Records the queue or continuation change; the saved session must not keep a finished radio. */
    private val onChanged: () -> Unit,
) {
    private val _radio = MutableStateFlow<ProviderId?>(null)

    /** The radio continuing the queue, for the Queue screen; null when nothing continues it. */
    val radio: StateFlow<ProviderId?> = _radio.asStateFlow()

    /** Dropped when other playback replaces the queue it was started with. */
    var continuation: QueueContinuation? = null
        private set(value) {
            field = value
            _radio.value = value?.radio
        }
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
                onChanged()
                return@launch
            }
            queue.appendTracks(batch)
            trace("continuation appended ${batch.size}; queue size=${queue.queue.value.items.size}")
            onChanged()
        }.also { refill = it }
    }

    private fun trace(message: String) = PlaybackTrace.log("Session", message)
}
