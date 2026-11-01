package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The published position: a clock anchored at the last known point, corrected from mpv when a
 * process is present and extrapolated when it is not. Guarded by the transport lock.
 */
internal class PositionTicker(
    private val transport: DesktopTransport,
    private val slot: MpvSlot,
    private val runtime: DesktopPlaybackRuntime,
    private val scope: CoroutineScope,
    /** Called under the lock. True when a Spotify receiver, not the clock, decides the track's end. */
    private val receiverOwnsPlayback: () -> Boolean,
) {
    private var job: Job? = null
    private var tickerToken = 0L
    private var startedAt = 0L

    /** Position at the moment the clock last started running. */
    var anchorMs = 0L
        private set

    /** Leaves the clock where it is measured from without marking it running. */
    fun hold(positionMs: Long) {
        anchorMs = positionMs
    }

    fun markRunning() {
        startedAt = System.currentTimeMillis()
    }

    fun anchor(positionMs: Long) {
        hold(positionMs)
        markRunning()
    }

    fun cancelLocked() {
        tickerToken++
        job?.cancel()
        job = null
    }

    fun startLocked() {
        cancelLocked()
        val token = ++tickerToken
        job = scope.launch {
            while (true) {
                val snapshot = synchronized(transport.lock) {
                    if (token != tickerToken || transport.current.status != EngineStatus.PLAYING) {
                        return@launch
                    }
                    TickerSnapshot(slot.active, slot.epoch)
                }
                // Socket reads happen on the IPC worker, outside the lock.
                val sample = snapshot.active?.let { active -> slot.awaitIpc { readTickerSample(active) } }
                val ended = synchronized(transport.lock) {
                    if (token != tickerToken || transport.current.status != EngineStatus.PLAYING) {
                        return@launch
                    }
                    val active = snapshot.active
                    if (active != null && (slot.active !== active || slot.epoch != snapshot.epoch)) {
                        // Stale sample: the process was replaced or a seek/pause/resume happened
                        // while the read was in flight. Do not publish it.
                        return@synchronized false
                    }
                    if (sample != null && active?.playlist?.crossing == null) {
                        if (sample.eof && !active.gapless) {
                            cancelLocked()
                            val current = transport.current
                            val truncated = mpvEndedEarly(current.positionMs, current.durationMs)
                            transport.publish(current.copy(
                                status = if (truncated) EngineStatus.FAILED else EngineStatus.ENDED,
                                positionMs = if (truncated) current.positionMs else current.durationMs ?: current.positionMs,
                                retryAtPositionMs = current.positionMs.takeIf { truncated },
                                error = if (truncated) "Stream ended before the track finished" else null,
                            ))
                            return@synchronized true
                        }
                        val timePos = sample.timePosSeconds
                        if (timePos != null) {
                            val position = (timePos * 1000.0).toLong().coerceAtLeast(0)
                            anchor(position)
                            transport.publish(transport.current.copy(positionMs = position))
                            return@synchronized false
                        }
                    }
                    val position = anchorMs + (System.currentTimeMillis() - startedAt)
                    val duration = transport.current.durationMs
                        ?: (transport.handle as? PlaybackHandle.ProviderPlayback)?.durationMs
                    val capped = if (duration != null) position.coerceAtMost(duration) else position
                    transport.publish(transport.current.copy(
                        positionMs = capped,
                        durationMs = duration ?: transport.current.durationMs,
                    ))
                    if (duration != null && position >= duration && active?.gapless != true && !receiverOwnsPlayback()) {
                        cancelLocked()
                        transport.publish(transport.current.copy(
                            status = EngineStatus.ENDED,
                            positionMs = duration,
                        ))
                        true
                    } else {
                        false
                    }
                }
                if (ended) return@launch
                delay(400)
            }
        }
    }

    private class TickerSnapshot(val active: ActiveProcess?, val epoch: Long)

    private class TickerSample(val eof: Boolean, val timePosSeconds: Double?)

    /** Runs on the IPC worker. Returns null when [active] is no longer the current process. */
    private fun readTickerSample(active: ActiveProcess): TickerSample? {
        if (!slot.isActive(active)) return null
        val eof = runtime.readProperty("eof-reached", active.socket)
        if (eof == "yes" || eof == "true") return TickerSample(eof = true, timePosSeconds = null)
        val timePos = runtime.readProperty("time-pos", active.socket)?.toDoubleOrNull()
        return TickerSample(eof = false, timePosSeconds = timePos)
    }
}
