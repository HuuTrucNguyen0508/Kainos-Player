package com.universalmusic.player.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keep short decoder underflows out of transport state, while reporting sustained stalls. */
internal class SpotifyHaltFilter(
    private val scope: CoroutineScope,
    private val emit: (SpotifyReceiverEvent) -> Unit,
) {
    private val lock = Any()
    private var pending: Job? = null
    private var generation = 0L
    private var last: Pair<String, SpotifyReceiverEvent.Kind>? = null

    fun reset() = synchronized(lock) {
        generation++
        pending?.cancel()
        pending = null
        last = null
    }

    fun accept(event: SpotifyReceiverEvent) = synchronized(lock) {
        if (event.kind == SpotifyReceiverEvent.Kind.BUFFERING && (event.positionMs ?: Long.MAX_VALUE) <= 1_000L) {
            if (pending != null || last == event.trackId to event.kind) return@synchronized
            val turn = generation
            pending = scope.launch {
                delay(250)
                synchronized(lock) {
                    if (turn == generation) {
                        pending = null
                        publish(event)
                    }
                }
            }
        } else {
            generation++
            pending?.cancel()
            pending = null
            publish(event)
        }
    }

    private fun publish(event: SpotifyReceiverEvent) {
        val identity = event.trackId to event.kind
        val status = event.kind == SpotifyReceiverEvent.Kind.PLAYING ||
            event.kind == SpotifyReceiverEvent.Kind.BUFFERING || event.kind == SpotifyReceiverEvent.Kind.PAUSED
        if (status && last == identity) return
        last = identity
        emit(event)
    }
}
