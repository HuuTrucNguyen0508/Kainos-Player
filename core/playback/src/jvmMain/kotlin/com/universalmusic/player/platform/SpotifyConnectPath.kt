package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.SpotifyObservationGate
import com.universalmusic.player.domain.playback.SpotifyObservedPlayback
import com.universalmusic.player.domain.playback.reconcileSpotifyObservation
import com.universalmusic.player.domain.playback.spotifyObservationStillValid
import com.universalmusic.player.domain.playback.spotifyPauseGuardOpen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Plays through Spotify Connect: one ordered command queue, the pause acknowledgement guard, the
 * local receiver's events and the Web API reconcile loop. Mutable state is guarded by the
 * transport lock.
 */
internal class SpotifyConnectPath(
    private val controller: SpotifyPlaybackController,
    private val transport: DesktopTransport,
    private val ticker: PositionTicker,
    private val scope: CoroutineScope,
    private val observeIntervalMs: Long,
) {
    private val commands = Channel<SpotifyCommand>(Channel.UNLIMITED)
    private var volumeJob: Job? = null
    private var pauseAcknowledged = false
    private var lastPauseCommandId = 0L
    private var pauseCommandCompleted = false
    private var observationsSincePause = 0
    private var transportGeneration = 0L
    private var reconcileJob: Job? = null
    private var receiverJob: Job? = null
    private var receiverEndTimeout: Job? = null
    private var receiverConfirmed = false
    private var receiverEndPending = false
    private var receiverStopped = false
    private var misses = 0

    init {
        scope.launch {
            for (command in commands) {
                val result = runCatching { command.action() }
                command.pauseCommandId?.let { pauseCommandId ->
                    synchronized(transport.lock) {
                        if (transport.userPaused && lastPauseCommandId == pauseCommandId) {
                            pauseCommandCompleted = true
                        }
                    }
                }
                command.completion?.complete(result)
                if (command.completion == null) {
                    result.onFailure { failure -> markCommandFailed(failure, command.name) }
                }
            }
        }
    }

    /** Subscribes to the receiver, sends play, then starts the clock if the receiver has not. */
    suspend fun start(handle: PlaybackHandle.ProviderPlayback, token: Long) {
        synchronized(transport.lock) {
            if (token != transport.requestToken) return
            startReceiverLocked(token)
        }
        runCommand("Spotify Connect playback") { controller.play(handle.trackId) }
        synchronized(transport.lock) {
            if (token != transport.requestToken) return
            if (transport.current.status == EngineStatus.BUFFERING) {
                ticker.markRunning()
                transport.publish(EngineState(
                    status = EngineStatus.PLAYING,
                    positionMs = 0,
                    durationMs = handle.durationMs,
                ))
                ticker.startLocked()
            }
            startReconcileLocked(token)
        }
    }

    fun pause() {
        val pauseCommandId = synchronized(transport.lock) {
            if (transport.current.status != EngineStatus.PLAYING &&
                transport.current.status != EngineStatus.BUFFERING
            ) return
            ticker.hold(transport.current.positionMs)
            ticker.cancelLocked()
            transport.userPaused = true
            pauseAcknowledged = false
            clearPauseGuardLocked()
            noteLocalTransportLocked()
            transport.publish(transport.current.copy(status = EngineStatus.PAUSED, error = null))
            if (receiverEndPending) return
            ++lastPauseCommandId
        }
        enqueueCommand("Spotify Connect pause", controller.pause, pauseCommandId)
    }

    fun resume() {
        synchronized(transport.lock) {
            if (transport.current.status != EngineStatus.PAUSED) return
            transport.userPaused = false
            pauseAcknowledged = false
            clearPauseGuardLocked()
            noteLocalTransportLocked()
            if (receiverEndPending) {
                if (receiverStopped) finishReceiverTrackLocked()
                else transport.publish(transport.current.copy(status = EngineStatus.PLAYING))
                return
            }
            ticker.markRunning()
            transport.publish(transport.current.copy(status = EngineStatus.PLAYING, error = null))
            ticker.startLocked()
        }
        enqueueCommand("Spotify Connect resume", controller.resume)
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        synchronized(transport.lock) {
            ticker.anchor(target)
            noteLocalTransportLocked()
            transport.publish(transport.current.copy(positionMs = target, error = null))
        }
        enqueueCommand("Spotify Connect seek", action = { controller.seekTo(target) })
    }

    fun setVolume(volume: Float) {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(250)
            if (transport.spotifyIsActive()) {
                runCatching { controller.setVolume(volume.coerceIn(0f, 1f)) }
                    .logged("Engine", "Spotify volume update failed")
            }
        }
    }

    /** A new track or a stop: nothing about the previous pause carries over. */
    fun resetPauseStateLocked() {
        pauseAcknowledged = false
        clearPauseGuardLocked()
        noteLocalTransportLocked()
    }

    /** The receiver already stopped after its end-of-queue reset. */
    fun receiverEndedTrackLocked(): Boolean =
        receiverConfirmed && transport.current.status == EngineStatus.ENDED

    fun pauseAfterStop() = enqueueCommand("Spotify Connect stop", controller.pause)

    fun cancelReconcileLocked() {
        reconcileJob?.cancel()
        reconcileJob = null
        misses = 0
    }

    fun receiverOwnsPlaybackLocked(): Boolean =
        receiverConfirmed && controller.receiverEvents?.isAvailable == true

    fun cancelReceiverLocked() {
        receiverJob?.cancel()
        receiverJob = null
        receiverEndTimeout?.cancel()
        receiverEndTimeout = null
        receiverConfirmed = false
        receiverEndPending = false
        receiverStopped = false
    }

    private fun startReceiverLocked(token: Long) {
        val receiver = controller.receiverEvents ?: return
        // Subscribe before sending play: loading and playing can arrive before the HTTP reply.
        receiverJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            receiver.events.collect { event ->
                synchronized(transport.lock) {
                    if (token != transport.requestToken || !transport.spotifyIsActive() || !receiver.isAvailable) return@synchronized
                    val handle = transport.handle as PlaybackHandle.ProviderPlayback
                    if (event.trackId != handle.trackId.removePrefix("spotify:track:")) {
                        // Connect gives the receiver a context of its own, so it can roll past
                        // EndOfTrack into a track that was never in the Kainos queue.
                        if (receiverEndPending && event.kind == SpotifyReceiverEvent.Kind.PLAYING) {
                            PlaybackTrace.log("Librespot", "receiver rolled over to ${event.trackId}")
                            silenceOverrunReceiverLocked()
                        }
                        return@synchronized
                    }
                    val current = transport.current
                    if (current.status == EngineStatus.ENDED || current.status == EngineStatus.FAILED) return@synchronized
                    PlaybackTrace.log("Librespot", "receiver ${event.kind} track=${event.trackId} pos=${event.positionMs}")
                    if (event.kind == SpotifyReceiverEvent.Kind.PLAYING) receiverConfirmed = true
                    if (!receiverConfirmed && event.kind != SpotifyReceiverEvent.Kind.UNAVAILABLE) return@synchronized
                    // A Web API request already in flight must not overwrite this receiver event.
                    noteLocalTransportLocked()
                    when (event.kind) {
                        SpotifyReceiverEvent.Kind.END_OF_TRACK -> {
                            if (receiverEndPending) return@synchronized
                            receiverEndPending = true
                            receiverStopped = false
                            ticker.cancelLocked()
                            // SPIRC handles EndOfTrack independently of --onevent. Its following
                            // Stopped event confirms handle_stop's queue/position reset has run.
                            // Bound the wait in case the event helper or receiver dies in between.
                            receiverEndTimeout = scope.launch {
                                delay(2_000)
                                synchronized(transport.lock) {
                                    if (token == transport.requestToken && receiverEndPending && !receiverStopped) {
                                        silenceOverrunReceiverLocked("Receiver stop acknowledgement timed out")
                                    }
                                }
                            }
                        }
                        SpotifyReceiverEvent.Kind.STOPPED -> {
                            receiverStopped = true
                            if (receiverEndPending) {
                                if (!transport.userPaused) finishReceiverTrackLocked()
                            } else if (!transport.userPaused) {
                                ticker.cancelLocked()
                                transport.publish(current.copy(status = EngineStatus.PAUSED))
                            }
                        }
                        SpotifyReceiverEvent.Kind.UNAVAILABLE -> {
                            ticker.cancelLocked()
                            transport.publish(current.copy(status = EngineStatus.FAILED, error = "Spotify track is unavailable"))
                        }
                        else -> {
                            // A rewind/pause after EndOfTrack belongs to the receiver's reset.
                            if (receiverEndPending) return@synchronized
                            val playing = when (event.kind) {
                                SpotifyReceiverEvent.Kind.PLAYING -> true
                                SpotifyReceiverEvent.Kind.PAUSED -> false
                                else -> current.status == EngineStatus.PLAYING
                            }
                            val decision = reconcileSpotifyObservation(
                                status = current.status,
                                positionMs = current.positionMs,
                                // Only EndOfTrack ends a local track; a user pause near its end is a pause.
                                durationMs = null,
                                expectedTrackId = handle.trackId,
                                userPaused = transport.userPaused,
                                misses = 0,
                                observed = SpotifyObservedPlayback(
                                    playing, event.positionMs, handle.trackId,
                                ),
                                pauseAcknowledged = pauseAcknowledged,
                                pauseGuardOpen = spotifyPauseGuardOpen(
                                    pauseCommandCompleted, observationsSincePause,
                                ),
                            )
                            pauseAcknowledged = decision.pauseAcknowledged
                            if (decision.clearUserPause) {
                                transport.userPaused = false
                                clearPauseGuardLocked()
                            } else if (transport.userPaused) observationsSincePause++
                            val eventPosition = event.positionMs
                            if (decision.snapClock || eventPosition != null && !transport.userPaused) {
                                ticker.anchor(if (decision.snapClock) decision.positionMs else eventPosition ?: ticker.anchorMs)
                            }
                            transport.publish(current.copy(
                                status = decision.status,
                                positionMs = ticker.anchorMs,
                                durationMs = handle.durationMs,
                                syncWarning = null,
                            ))
                            if (decision.status == EngineStatus.PLAYING) ticker.startLocked() else ticker.cancelLocked()
                        }
                    }
                }
            }
        }
    }

    /**
     * The receiver did not stop at the end of its track, and the transition skips its pause once
     * this track is ENDED, so anything played next would otherwise sound on top of it.
     */
    private fun silenceOverrunReceiverLocked(warning: String? = null) {
        enqueueCommand("Spotify Connect pause", action = {
            runCatching { controller.pause() }.onFailure { failure ->
                PlaybackTrace.log("Librespot", "pause after end of track failed: ${failure.message}")
            }
        })
        receiverStopped = true
        if (!transport.userPaused) finishReceiverTrackLocked(warning)
    }

    private fun finishReceiverTrackLocked(warning: String? = null) {
        receiverEndPending = false
        receiverEndTimeout?.cancel()
        receiverEndTimeout = null
        ticker.cancelLocked()
        val current = transport.current
        transport.publish(current.copy(
            status = EngineStatus.ENDED,
            positionMs = current.durationMs ?: current.positionMs,
            syncWarning = warning,
        ))
    }

    private fun startReconcileLocked(token: Long) {
        cancelReconcileLocked()
        val observe = controller.observe ?: return
        reconcileJob = scope.launch {
            while (true) {
                delay(observeIntervalMs)
                val snapshot = synchronized(transport.lock) {
                    if (token != transport.requestToken || !transport.spotifyIsActive()) return@launch
                    if (transport.current.status == EngineStatus.ENDED || transport.current.status == EngineStatus.FAILED) return@launch
                    if (receiverOwnsPlaybackLocked()) return@synchronized null
                    SpotifyReconcileSnapshot(
                        status = transport.current.status,
                        positionMs = transport.current.positionMs,
                        durationMs = transport.current.durationMs,
                        expectedTrackId = (transport.handle as? PlaybackHandle.ProviderPlayback)?.trackId,
                        userPaused = transport.userPaused,
                        pauseAcknowledged = pauseAcknowledged,
                        pauseGuardOpen = spotifyPauseGuardOpen(
                            pauseCommandCompleted = pauseCommandCompleted,
                            observationsSincePause = observationsSincePause,
                        ),
                        transportGeneration = transportGeneration,
                        misses = misses,
                    )
                } ?: continue
                val observed = runCatching { observe() }.getOrNull()
                val decision = reconcileSpotifyObservation(
                    status = snapshot.status,
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    expectedTrackId = snapshot.expectedTrackId,
                    userPaused = snapshot.userPaused,
                    misses = snapshot.misses,
                    observed = observed,
                    pauseAcknowledged = snapshot.pauseAcknowledged,
                    pauseGuardOpen = snapshot.pauseGuardOpen,
                )
                synchronized(transport.lock) {
                    val currentGate = SpotifyObservationGate(
                        requestToken = transport.requestToken,
                        transportGeneration = transportGeneration,
                        userPaused = transport.userPaused,
                    )
                    val capturedGate = SpotifyObservationGate(
                        requestToken = token,
                        transportGeneration = snapshot.transportGeneration,
                        userPaused = snapshot.userPaused,
                    )
                    if (receiverOwnsPlaybackLocked() ||
                        transport.current.status == EngineStatus.ENDED ||
                        !spotifyObservationStillValid(capturedGate, currentGate, transport.spotifyIsActive())) {
                        return@synchronized
                    }
                    pauseAcknowledged = decision.pauseAcknowledged
                    if (decision.clearUserPause) {
                        transport.userPaused = false
                        clearPauseGuardLocked()
                    } else if (snapshot.userPaused && observed != null) {
                        observationsSincePause++
                    }
                    misses = decision.misses
                    if (decision.snapClock) {
                        ticker.anchor(decision.positionMs)
                    }
                    val previous = transport.current.status
                    transport.publish(transport.current.copy(
                        status = decision.status,
                        positionMs = decision.positionMs,
                        syncWarning = decision.syncWarning,
                    ))
                    when (decision.status) {
                        EngineStatus.PLAYING -> if (previous != EngineStatus.PLAYING) ticker.startLocked()
                        EngineStatus.PAUSED, EngineStatus.ENDED, EngineStatus.FAILED -> ticker.cancelLocked()
                        else -> Unit
                    }
                }
            }
        }
    }

    private data class SpotifyReconcileSnapshot(
        val status: EngineStatus,
        val positionMs: Long,
        val durationMs: Long?,
        val expectedTrackId: String?,
        val userPaused: Boolean,
        val pauseAcknowledged: Boolean,
        val pauseGuardOpen: Boolean,
        val transportGeneration: Long,
        val misses: Int,
    )

    /** A newer local decision exists, so an observation already in flight must not apply. */
    private fun noteLocalTransportLocked() {
        transportGeneration++
    }

    private fun clearPauseGuardLocked() {
        pauseCommandCompleted = false
        observationsSincePause = 0
    }

    private suspend fun runCommand(name: String, action: suspend () -> Unit) {
        val completion = CompletableDeferred<Result<Unit>>()
        commands.send(SpotifyCommand(name, action, completion))
        completion.await().getOrThrow()
    }

    private fun enqueueCommand(
        name: String,
        action: suspend () -> Unit,
        pauseCommandId: Long? = null,
    ) {
        commands.trySend(SpotifyCommand(name, action, pauseCommandId = pauseCommandId))
    }

    private fun markCommandFailed(failure: Throwable, name: String) {
        PlaybackTrace.log("Engine", "$name warning: ${failure.message}")
        if (name.endsWith("stop")) return
        synchronized(transport.lock) {
            if (transport.spotifyIsActive()) transport.publish(transport.current.copy(syncWarning = "$name failed; waiting for receiver state"))
        }
    }

    private data class SpotifyCommand(
        val name: String,
        val action: suspend () -> Unit,
        val completion: CompletableDeferred<Result<Unit>>? = null,
        val pauseCommandId: Long? = null,
    )
}
