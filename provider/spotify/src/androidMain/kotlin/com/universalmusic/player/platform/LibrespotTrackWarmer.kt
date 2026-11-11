package com.universalmusic.player.platform

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.gianlu.librespot.audio.AbsChunkedInputStream
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.audio.decoders.VorbisOnlyAudioQuality
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.TrackId

/**
 * Reads the opening chunks of an upcoming track through librespot's disk cache, so its load reads
 * them from disk instead of waiting on the CDN. librespot keeps one cache handle per audio file and
 * closing any stream on it closes the handle, so a warm must never overlap a load of the same
 * track: [beforeLoad] stops it and waits until its stream is closed.
 */
internal class LibrespotTrackWarmer(private val scope: CoroutineScope) {
    /** Includes cancelled warms until their stream is closed. */
    private val warms = mutableMapOf<String, Warm>()
    private var loadedTrackId: String? = null

    /** Warms [trackId] on a running [session] and cancels every other warm; null only cancels. */
    fun warm(trackId: String?, session: Session?) = synchronized(warms) {
        warms.values.filter { it.trackId != trackId }.forEach(Warm::cancel)
        if (trackId == null || trackId == loadedTrackId || trackId in warms) return@synchronized
        if (session == null) {
            trace("warm $trackId skipped: no receiver running")
            return@synchronized
        }
        val warm = Warm(trackId)
        warms[trackId] = warm
        scope.launch {
            try {
                warm.run(session)
            } finally {
                synchronized(warms) { warms.remove(trackId) }
                warm.finished.complete(Unit)
            }
        }
    }

    suspend fun beforeLoad(trackId: String) {
        val warm = synchronized(warms) {
            loadedTrackId = trackId
            warms[trackId]
        } ?: return
        warm.cancel()
        if (withTimeoutOrNull(JOIN_TIMEOUT_MS) { warm.finished.await() } == null) {
            trace("warm $trackId still open after ${JOIN_TIMEOUT_MS} ms; loading anyway")
        }
    }

    fun cancelAll() = synchronized(warms) { warms.values.forEach(Warm::cancel) }

    private class Warm(val trackId: String) {
        val finished = CompletableDeferred<Unit>()
        private val cancelled = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        @Volatile private var stream: AbsChunkedInputStream? = null

        fun cancel() {
            cancelled.set(true)
            close()
        }

        private fun close() {
            val open = stream ?: return
            if (closed.compareAndSet(false, true)) open.close()
        }

        fun run(session: Session) {
            val started = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
            var bytes = 0
            try {
                // The same file the player picks (PlayerQueueEntry), so the load finds these chunks.
                val loaded = session.contentFeeder().load(
                    TrackId.fromBase62(trackId), VorbisOnlyAudioQuality(AudioQuality.VERY_HIGH), true, null,
                )
                val input = loaded.`in`.stream()
                stream = input
                try {
                    val wanted = minOf(input.available(), WARM_BYTES)
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (!cancelled.get() && bytes < wanted) {
                        val read = input.read(buffer, 0, minOf(buffer.size, wanted - bytes))
                        if (read <= 0) break
                        bytes += read
                    }
                } finally {
                    close()
                }
                trace("warmed $trackId: $bytes bytes in ${elapsedMs()} ms${if (cancelled.get()) " (stopped early)" else ""}")
            } catch (failure: Exception) {
                val outcome = if (cancelled.get()) "stopped" else "failed"
                trace("warm $trackId $outcome after $bytes bytes in ${elapsedMs()} ms: $failure")
            }
        }
    }

    private companion object {
        /** Four of librespot's 128 KiB chunks: several seconds of audio at the highest quality. */
        const val WARM_BYTES = 4 * 128 * 1024
        const val READ_BUFFER_BYTES = 16 * 1024
        const val JOIN_TIMEOUT_MS = 10_000L

        fun trace(message: String) = PlaybackTrace.log("Librespot", message)
    }
}
