package com.universalmusic.player.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keep short decoder underflows out of transport state, while reporting sustained stalls. */
internal class SpotifyHaltFilter(
    private val scope: CoroutineScope,
    private val trace: (String) -> Unit = { PlaybackTrace.log("Librespot", it) },
    private val emit: (SpotifyReceiverEvent) -> Unit,
) {
    private val lock = Any()
    private var pending: Job? = null
    private var generation = 0L
    private var absorbedUnderflows = 0
    private var last: Pair<String, SpotifyReceiverEvent.Kind>? = null

    fun reset() = synchronized(lock) {
        if (absorbedUnderflows > 0) trace("start underflows absorbed=$absorbedUnderflows")
        absorbedUnderflows = 0
        generation++
        pending?.cancel()
        pending = null
        last = null
    }

    fun accept(event: SpotifyReceiverEvent) = synchronized(lock) {
        if (event.kind == SpotifyReceiverEvent.Kind.BUFFERING && (event.positionMs ?: Long.MAX_VALUE) <= 1_000L) {
            absorbedUnderflows++
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
        if (event.kind == SpotifyReceiverEvent.Kind.BUFFERING) {
            if ((event.positionMs ?: Long.MAX_VALUE) <= 1_000L) absorbedUnderflows--
            trace("stall at ${event.positionMs}")
        } else if (event.kind == SpotifyReceiverEvent.Kind.PLAYING && last?.second == SpotifyReceiverEvent.Kind.BUFFERING) {
            trace("stall recovered at ${event.positionMs}")
        }
        last = identity
        emit(event)
    }
}
