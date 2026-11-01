package com.universalmusic.player.domain.playback


import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.SessionRevision
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.toPlaybackQueue
import com.universalmusic.player.domain.continuation.QueueContinuation
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.ResolvedPlayback
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.queue.QueueController
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns queue mutations and playback transitions. Engines report status; this session decides
 * when to advance, stop, or ignore stale completion from a superseded play request.
 */
class PlayerSession(
    private val engine: PlaybackEngine,
    private val resolver: SourceResolver,
    private val scope: CoroutineScope,
    initialPreferences: PlaybackPreferences = PlaybackPreferences.Default,
    private val enrichSource: suspend (Track, PlaybackSource) -> PlaybackSource = { _, source -> source },
    private val isFavorite: (canonicalId: String) -> Boolean = { false },
    /** Merge hearted audio cache / other offline sources before resolution. */
    private val prepareTrack: (Track) -> Track = { it },
    /**
     * Single thread that owns every session mutation (UI, MPRIS/D-Bus, Media3 callbacks and
     * engine events all funnel through it). Null keeps the caller's thread, which tests rely on.
     */
    private val confineTo: CoroutineDispatcher? = null,
    /** Where resolve / enrich / engine start run so network and process work stay off [confineTo]. */
    private val workContext: CoroutineContext = EmptyCoroutineContext,
    /** Receives every recorded change and checkpoint. Null keeps the session in memory only. */
    private val persistence: NowPlayingRecorder? = null,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val sessionContext: CoroutineContext = confineTo ?: EmptyCoroutineContext
    private val queue = QueueController()
    val queueState: StateFlow<PlaybackQueue> = queue.queue

    /** Run [block] on the session thread: inline when already there, otherwise posted in order. */
    private inline fun onSession(crossinline block: () -> Unit) {
        val dispatcher = confineTo
        if (dispatcher == null || !dispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
            block()
        } else {
            scope.launch(dispatcher) { block() }
        }
    }

    private val _nowPlaying = MutableStateFlow(NowPlayingState())
    val nowPlaying: StateFlow<NowPlayingState> = _nowPlaying.asStateFlow()

    private val _preferences = MutableStateFlow(initialPreferences)
    val preferences: StateFlow<PlaybackPreferences> = _preferences.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val ledger = ListeningLedger(persistence, queueState, nowPlaying, scope, sessionContext, clock)
    private val refiller = QueueRefiller(queue, scope, sessionContext, workContext) {
        ledger.record(ListeningChange.AUTOMATIC)
    }
    private val turn = PlaybackTurn()
    private val sources = TrackSources(resolver, enrichSource, prepareTrack, workContext)
    private val prefetcher = SuccessorPrefetcher(
        engine, queue, sources, preferences, nowPlaying, turn,
        heldAtTrackEnd = { naturalCompletionGate != null },
        scope, sessionContext, clock,
    )
    private val starter = TrackStarter(
        engine, sources, preferences, volume, _nowPlaying, turn, prefetcher, isFavorite,
        onEngineStarted = { target -> if (startupTarget === target) startupTarget = null },
        onSpeculativeUrlRejected = { resumeAt ->
            pendingResumePositionMs = resumeAt
            startCurrent(ListeningChange.AUTOMATIC, applyResumePosition = true)
        },
        onTerminalFailure = { generation -> advanceAfterTerminalFailure(generation) },
    )
    private val remoteLoader = RemoteSessionLoader(
        engine, persistence, ledger, nowPlaying, confineTo, clock,
        captureLocal = { captureLocalListening() },
        installPaused = { installPaused(it) },
    )

    private var playJob: Job? = null
    private var truncatedRetryItemId: String? = null
    /** Last engine status seen by the collector; trace only on change, never per position tick. */
    private var lastTracedStatus: EngineStatus? = null
    /**
     * After cold-start restore, Play re-resolves then seeks here once.
     * Cleared on a normal track start that is not a resume-from-restore.
     */
    private var pendingResumePositionMs: Long? = null
    private var startupTarget: StartupTarget? = null

    /**
     * Queue entries that failed terminally since the last successful start or user play request.
     * Bounds failure-skipping so an all-dead queue under Repeat All stops instead of cycling forever.
     */
    private val failedItemIds = mutableSetOf<String>()

    /** Session thread only. Advances before any recorded change returns, even while its write is pending. */
    val localRevision: SessionRevision get() = SessionRevision(ledger.revision)

    private var pausedSeekTargetMs: Long? = null
    private var pausePending = false

    /**
     * Optional gate for sleep-timer end-of-track mode. Invoked on natural completion
     * before advance; return true to freeze playback without starting the next item.
     */
    @Volatile
    private var naturalCompletionGate: (() -> Boolean)? = null

    private fun trace(message: String) = PlaybackTrace.log("Session", message)

    init {
        scope.launch(sessionContext) {
            queueState.collect {
                prefetcher.onQueueChanged()
            }
        }
        scope.launch(sessionContext) {
            engine.state.collectLatest { onEngineState(it) }
        }
    }

    private fun onEngineState(engineState: EngineState) {
        val eventGeneration = engineState.playGeneration
        if (engineState.previousGeneration == turn.generation && eventGeneration == turn.generation + 1) {
            if (!crossSeamlessBoundary(engineState)) return
        }
        if (engineState.status != lastTracedStatus) {
            lastTracedStatus = engineState.status
            trace(
                "engine -> ${engineState.status} gen=$eventGeneration (session gen=${turn.generation} " +
                    "armed=${turn.completionArmed}) pos=${engineState.positionMs} dur=${engineState.durationMs}" +
                    (engineState.error?.let { " error=$it" } ?: ""),
            )
        }
        // Reject stale engine events before touching nowPlaying.
        if (eventGeneration != turn.generation) {
            if (engineState.status == EngineStatus.ENDED || engineState.status == EngineStatus.FAILED) {
                trace("dropped stale ${engineState.status} gen=$eventGeneration current=${turn.generation}")
            }
            return
        }

        val learnedDuration = engineState.durationMs?.takeIf { it > 0 }
        val item = queue.queue.value.current
        if (learnedDuration != null && item != null && item.track.durationMs == null &&
            _nowPlaying.value.queueItemId == item.id
        ) {
            val updated = item.track.copy(durationMs = learnedDuration)
            queue.replaceCurrentTrack(updated)
            _nowPlaying.update { it.copy(track = it.track?.copy(durationMs = learnedDuration)) }
        }

        if (engineState.status != EngineStatus.PLAYING && engineState.status != EngineStatus.BUFFERING) {
            pausePending = false
        }
        val playing = engineState.status == EngineStatus.PLAYING && !pausePending
        _nowPlaying.update { current ->
            current.copy(
                isPlaying = playing,
                positionMs = startupTarget?.positionMs ?: pausedSeekTargetMs.takeUnless { playing }
                    ?: engineState.positionMs,
                durationMs = engineState.durationMs ?: current.track?.durationMs ?: current.durationMs,
                buffering = !pausePending && (engineState.status == EngineStatus.BUFFERING ||
                    (current.buffering && current.resolved == null &&
                        engineState.status == EngineStatus.IDLE)),
                error = if (engineState.status == EngineStatus.FAILED) {
                    engineState.error
                } else {
                    current.error
                },
                syncWarning = engineState.syncWarning,
            )
        }
        if (playing) pausedSeekTargetMs = null
        if (_nowPlaying.value.isPlaying != ledger.lastRecordedPlaying && queue.queue.value.items.isNotEmpty()) {
            ledger.record(ListeningChange.AUTOMATIC)
        }

        if (eventGeneration != turn.generation) return
        when (engineState.status) {
            EngineStatus.PLAYING -> if (turn.completionArmed) failedItemIds.clear()
            EngineStatus.ENDED -> {
                if (!turn.completionArmed) {
                    trace("ENDED ignored: completion not armed gen=$eventGeneration")
                    return
                }
                if (eventGeneration != turn.generation) return
                turn.disarm()
                trace("natural completion of ${_nowPlaying.value.track.traceLabel()}")
                handleNaturalCompletion(eventGeneration)
            }
            EngineStatus.FAILED -> {
                if (eventGeneration != turn.generation) return
                if (!turn.completionArmed && _nowPlaying.value.resolved == null) {
                    trace("FAILED ignored: nothing resolved gen=$eventGeneration error=${engineState.error}")
                    return
                }
                val retryAt = engineState.retryAtPositionMs
                val currentItemId = queue.queue.value.current?.id
                if (retryAt != null && currentItemId != truncatedRetryItemId) {
                    truncatedRetryItemId = currentItemId
                    pendingResumePositionMs = retryAt
                    startCurrent(ListeningChange.AUTOMATIC, applyResumePosition = true)
                    return
                }
                turn.disarm()
                val current = _nowPlaying.value.resolved ?: return
                val reason = engineState.error
                trace("engine FAILED for ${current.track.traceLabel()}: $reason -> fallback")
                startFallback(current, reason, eventGeneration)
            }
            else -> Unit
        }
    }

    /** Moves the session across a boundary the engine already crossed. False when this event is finished. */
    private fun crossSeamlessBoundary(engineState: EngineState): Boolean {
        val ready = prefetcher.seamlessArrival(engineState.seamlessItemId)
        if (ready != null && turn.completionArmed) {
            val wrap = queueState.value.shuffle && queue.isWrapToStart(respectRepeatOne = true)
            val next = queue.nextIndex() ?: return false
            prefetcher.clearSeamless()
            turn.adoptSeamless(engineState.playGeneration)
            queue.jumpTo(next, reshuffle = wrap)
            val item = queueState.value.current ?: return false
            val resolved = ready.copy(track = ready.track.copy(durationMs = engineState.durationMs))
            _nowPlaying.update { it.copy(track = resolved.track, queueItemId = item.id,
                resolved = resolved, positionMs = engineState.positionMs, durationMs = engineState.durationMs,
                isPlaying = engineState.status == EngineStatus.PLAYING, buffering = false,
                error = null, fallback = null, favorite = isFavorite(item.track.canonicalId)) }
            ledger.record(ListeningChange.AUTOMATIC)
            refiller.refillIfLow()
            prefetcher.schedule(turn.generation)
            return true
        }
        // The queue changed after mpv crossed the boundary but before this event
        // reached the session. Restart the newly selected successor normally.
        trace("seamless target superseded at boundary")
        handleNaturalCompletion(turn.generation)
        return false
    }

    fun updatePreferences(preferences: PlaybackPreferences) {
        onSession {
            _preferences.value = preferences
            engine.configureGapless(preferences.gapless && _nowPlaying.value.resolved?.source?.provider == ProviderId.LOCAL)
            prefetcher.schedule(turn.generation)
        }
    }

    /**
     * Restore queue + Now Playing metadata from a session snapshot without starting audio.
     * Preserves [QueueItem] ids and shuffle order. Play later re-resolves and seeks to
     * the coarse saved position.
     */
    fun restorePaused(snapshot: SessionSnapshot) = onSession { installPaused(snapshot) }

    /**
     * Read the persisted session and install it paused only if this session has never accepted a
     * recorded change. An accepted action always wins over a late restore. Then start persisting.
     */
    suspend fun initializePersistence() {
        val recorder = persistence ?: return
        val restored = recorder.readRestore()
        withContext(sessionContext) {
            when {
                restored == null -> Unit
                ledger.revision != 0L -> trace("restore skipped: session revision moved from 0 to ${ledger.revision}")
                else -> {
                    ledger.adoptRestored(restored.origin, restored.snapshot.remoteSession)
                    if (restored.snapshot.items.isNotEmpty()) installPaused(restored.snapshot)
                }
            }
            ledger.markReady()
        }
        recorder.start()
    }

    /** Write a private checkpoint now (backgrounding, exit) and wait for it. Never pauses playback. */
    suspend fun flushPersistence(): FlushResult {
        val recorder = persistence ?: return FlushResult.Committed()
        val sequence = withContext(sessionContext) { if (ledger.persistenceReady) ledger.checkpoint() else null }
            ?: return FlushResult.Failed(IllegalStateException("Listening persistence has not initialized"))
        return recorder.awaitCommitted(sequence)
    }

    /** Runs [block] on the session thread; it must use the `*Now` calls, which apply inline. */
    internal suspend fun <T> onSessionThread(block: () -> T): T = withContext(sessionContext) { block() }

    /**
     * Installs a queue the user moved here from another device and plays it. Unlike
     * [loadRemotePaused] it skips the recency, not-playing and applied-handoff checks: the user asked.
     */
    internal suspend fun installTransferAndPlay(snapshot: SessionSnapshot): Boolean = withContext(sessionContext) {
        if (snapshot.items.isEmpty()) return@withContext false
        trace("installTransferAndPlay items=${snapshot.items.size} index=${snapshot.currentIndex} pos=${snapshot.positionMs}")
        installPaused(snapshot)
        playTransportNow()
        true
    }

    internal class HandoffView(val local: LocalHandoffState, val revision: SessionRevision)

    internal suspend fun handoffView(): HandoffView = withContext(sessionContext) {
        val now = _nowPlaying.value
        HandoffView(LocalHandoffState(now.isPlaying, now.buffering, ledger.lastActiveAtMs, ledger.remoteSession), localRevision)
    }

    internal suspend fun loadRemotePaused(
        snapshot: SessionSnapshot,
        peer: PeerHandoff,
        expected: SessionRevision,
        exchange: ListeningExchangeStore,
        expectedPublication: PublicationRevision?,
    ): HandoffDecision = remoteLoader.load(snapshot, peer, expected, exchange, expectedPublication)

    /** Captures what a remote install replaces; the result puts it back. */
    private fun captureLocalListening(): () -> Unit {
        val oldQueue = queue.queue.value
        val oldNow = _nowPlaying.value
        val oldResume = pendingResumePositionMs
        return {
            queue.restore(oldQueue)
            _nowPlaying.value = oldNow
            pendingResumePositionMs = oldResume
        }
    }

    private fun installPaused(snapshot: SessionSnapshot) {
        refiller.drop()
        val restored = snapshot.toPlaybackQueue()
        failedItemIds.clear()
        ledger.markPausedInstall()
        if (restored.items.isEmpty()) {
            pendingResumePositionMs = null
            queue.restore(restored)
            _nowPlaying.value = NowPlayingState()
            return
        }
        beginTransition()
        playJob?.cancel()
        playJob = null
        pendingResumePositionMs = snapshot.positionMs.takeIf { it > 0L }
        queue.restore(restored)
        val item = queue.queue.value.current
        _nowPlaying.value = NowPlayingState(
            track = item?.let { sources.prepareTrack(it.track) },
            queueItemId = item?.id,
            resolved = null,
            isPlaying = false,
            positionMs = snapshot.positionMs.coerceAtLeast(0L),
            durationMs = item?.track?.durationMs,
            buffering = false,
            favorite = item?.let { isFavorite(it.track.canonicalId) } ?: false,
            fallback = null,
            error = null,
            syncWarning = null,
        )
        trace(
            "installPaused items=${restored.items.size} index=${restored.currentIndex} " +
                "pos=${snapshot.positionMs} shuffle=${restored.shuffle} repeat=${restored.repeat} " +
                "track=${item?.track.traceLabel()}",
        )
    }

    fun play(track: Track) = onSession {
        beginUserPlay()
        refiller.drop()
        queue.playNow(track)
        startCurrent(ListeningChange.LOCAL_INTENT)
    }

    suspend fun playAwait(track: Track) {
        val job = withContext(sessionContext) {
            beginUserPlay()
            refiller.drop()
            queue.playNow(track)
            startCurrent(ListeningChange.LOCAL_INTENT)
        }
        job?.join()
    }

    /** [continuation] keeps appending to this queue until other playback replaces it or it is cleared. */
    fun play(tracks: List<Track>, startIndex: Int = 0, continuation: QueueContinuation? = null) = onSession {
        beginUserPlay()
        refiller.drop()
        queue.playNow(tracks, startIndex)
        refiller.attach(continuation)
        startCurrent(ListeningChange.LOCAL_INTENT)
    }

    /**
     * Play the already-queued item at [index] without rebuilding the queue.
     * Under shuffle, starts a fresh random order with that track first so short sessions
     * do not keep walking the same leftover permutation.
     */
    fun playQueueIndex(index: Int) = onSession { playQueueIndexNow(index) }

    internal fun playQueueIndexNow(index: Int) {
        if (index !in queue.queue.value.items.indices) return
        beginUserPlay()
        queue.jumpTo(index, reshuffle = true)
        startCurrent(ListeningChange.LOCAL_INTENT)
    }

    fun addToQueue(track: Track) = onSession { editQueue { addToQueue(track) } }

    fun playNext(track: Track) = onSession { editQueue { playNext(track) } }

    fun stopTransport() = onSession {
        val position = startupTarget?.positionMs ?: _nowPlaying.value.positionMs
        beginTransition()
        playJob?.cancel()
        playJob = null
        pendingResumePositionMs = position
        engine.stop()
        _nowPlaying.update { it.copy(isPlaying = false, buffering = false, positionMs = position) }
        ledger.record(ListeningChange.USER_PAUSE, playing = false)
    }

    fun clearQueue() = onSession { clearQueueNow(recorded = queue.queue.value.items.isNotEmpty()) }

    private fun clearQueueNow(recorded: Boolean) {
        trace("clearQueue")
        refiller.drop()
        beginTransition()
        playJob?.cancel()
        playJob = null
        pendingResumePositionMs = null
        failedItemIds.clear()
        queue.clear()
        engine.stop()
        _nowPlaying.value = NowPlayingState()
        if (recorded) ledger.record(ListeningChange.LOCAL_INTENT)
    }

    fun undoQueueEdit(): Boolean {
        if (!queue.canUndo()) return false
        onSession { undoQueueEditNow() }
        return true
    }

    private fun undoQueueEditNow() {
        val restored = queue.undo()
        if (!restored) return
        val snapshot = queue.queue.value
        val currentId = _nowPlaying.value.queueItemId
        when {
            snapshot.items.isEmpty() -> {
                refiller.drop()
                beginTransition()
                playJob?.cancel()
                playJob = null
                engine.stop()
                _nowPlaying.value = NowPlayingState()
                ledger.record(ListeningChange.LOCAL_INTENT)
            }
            snapshot.current?.id != currentId || _nowPlaying.value.track == null -> startCurrent(ListeningChange.LOCAL_INTENT)
            else -> ledger.record(ListeningChange.LOCAL_INTENT)
        }
    }

    fun retryPlayback() = onSession {
        if (queue.queue.value.current == null) return@onSession
        failedItemIds.clear()
        startCurrent(ListeningChange.LOCAL_INTENT)
    }

    fun tryAnotherSource() = onSession {
        val resolved = _nowPlaying.value.resolved ?: return@onSession
        if (resolved.fallbacks.isEmpty()) return@onSession
        val generation = turn.generation
        startFallback(resolved, "Trying another source", generation)
    }

    fun removeFromQueue(itemId: String) = onSession {
        val before = queue.queue.value
        val removingCurrent = before.current?.id == itemId
        val hasSuccessor = queue.remove(itemId)
        if (queue.queue.value === before) return@onSession
        when {
            queue.queue.value.items.isEmpty() -> clearQueueNow(recorded = true)
            !removingCurrent -> ledger.record(ListeningChange.LOCAL_INTENT)
            hasSuccessor -> startCurrent(ListeningChange.LOCAL_INTENT)
            else -> {
                // Removed the final item: stop rather than replaying its predecessor.
                stopAtQueueEnd(ListeningChange.LOCAL_INTENT)
                val item = queue.queue.value.current ?: return@onSession
                val track = sources.prepareTrack(item.track)
                _nowPlaying.update {
                    it.copy(
                        track = track,
                        queueItemId = item.id,
                        resolved = null,
                        durationMs = track.durationMs,
                        favorite = isFavorite(track.canonicalId),
                        fallback = null,
                    )
                }
            }
        }
    }

    fun moveInQueue(from: Int, to: Int) = onSession { editQueue { move(from, to) } }

    fun moveInPlaybackOrder(fromOrderPos: Int, toOrderPos: Int) = onSession { moveInPlaybackOrderNow(fromOrderPos, toOrderPos) }

    internal fun moveInPlaybackOrderNow(fromOrderPos: Int, toOrderPos: Int) {
        editQueue { moveInPlaybackOrder(fromOrderPos, toOrderPos) }
    }

    private inline fun editQueue(edit: QueueController.() -> Unit) {
        val before = queue.queue.value
        queue.edit()
        if (queue.queue.value !== before) ledger.record(ListeningChange.LOCAL_INTENT)
    }

    fun togglePlayPause() = onSession { togglePlayPauseNow() }

    internal fun togglePlayPauseNow() {
        val now = _nowPlaying.value
        trace("togglePlayPause buffering=${now.buffering} isPlaying=${now.isPlaying}")
        when {
            now.buffering || now.isPlaying -> pauseTransportNow(ListeningChange.USER_PAUSE)
            else -> playTransportNow()
        }
    }

    /** Idempotent pause; cancels in-flight resolve/buffering. */
    fun pauseTransport() = onSession { pauseTransportNow(ListeningChange.USER_PAUSE) }

    internal fun pauseTransportNow() = pauseTransportNow(ListeningChange.USER_PAUSE)

    internal fun pauseForSleepTimer() = onSession { pauseTransportNow(ListeningChange.AUTOMATIC) }

    private fun pauseTransportNow(change: ListeningChange) {
        val now = _nowPlaying.value
        if (!now.buffering && !now.isPlaying && !ledger.lastRecordedPlaying) return
        val engineStatus = engine.state.value.status
        if (now.buffering && !(now.resolved != null && turn.completionArmed)) {
            trace(
                "pauseTransport while buffering -> cancel start of ${_nowPlaying.value.track.traceLabel()} " +
                    "(engine=$engineStatus gen=${turn.generation})",
            )
            pendingResumePositionMs = startupTarget?.positionMs ?: now.positionMs.takeIf { it > 0 }
            beginTransition()
            playJob?.cancel()
            playJob = null
            engine.stop()
            _nowPlaying.update { it.copy(buffering = false, isPlaying = false) }
            ledger.record(change, playing = false)
            return
        }
        trace("pauseTransport engine=$engineStatus gen=${turn.generation} track=${_nowPlaying.value.track.traceLabel()}")
        pausePending = true
        _nowPlaying.update { it.copy(isPlaying = false, buffering = false) }
        engine.pause()
        ledger.record(change, playing = false)
    }

    /** Idempotent play/resume; no-op when already playing or buffering. */
    fun playTransport() = onSession { playTransportNow() }

    internal fun playTransportNow() {
        val now = _nowPlaying.value
        trace("playTransport buffering=${now.buffering} isPlaying=${now.isPlaying} engine=${engine.state.value.status}")
        if (now.buffering || now.isPlaying) return
        val engineStatus = currentGenerationEngineStatus()
        if (pausePending || engineStatus == EngineStatus.PAUSED) {
            pausePending = false
            _nowPlaying.update { it.copy(isPlaying = true) }
            engine.resume()
            ledger.record(ListeningChange.LOCAL_INTENT)
        } else if (engineStatus == EngineStatus.IDLE || engineStatus == EngineStatus.ENDED || engineStatus == EngineStatus.FAILED) {
            failedItemIds.clear()
            startCurrent(ListeningChange.LOCAL_INTENT, applyResumePosition = true)
        }
    }

    private fun currentGenerationEngineStatus(): EngineStatus =
        engine.state.value.takeIf { it.playGeneration == turn.generation }?.status ?: EngineStatus.IDLE

    fun seekTo(positionMs: Long) = onSession { seekNow(positionMs) }

    internal fun seekNow(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        startupTarget?.positionMs = target
        engine.seekTo(positionMs)
        if (queue.queue.value.items.isEmpty()) return
        when (engine.state.value.status) {
            EngineStatus.IDLE, EngineStatus.ENDED, EngineStatus.FAILED -> pendingResumePositionMs = target.takeIf { it > 0L }
            EngineStatus.PLAYING, EngineStatus.BUFFERING, EngineStatus.PAUSED -> Unit
        }
        pausedSeekTargetMs = target.takeUnless { _nowPlaying.value.isPlaying }
        _nowPlaying.update { it.copy(positionMs = target) }
        ledger.record(ListeningChange.LOCAL_INTENT)
    }

    /** Linear gain 0–1 applied to the active engine (and remembered for the next track). */
    fun setVolume(volume: Float) {
        val next = volume.coerceIn(0f, 1f)
        _volume.value = next
        onSession { engine.setVolume(next) }
    }

    /** Manual next: escapes Repeat One. */
    fun skipToNext() = onSession { skipToNextNow() }

    internal fun skipToNextNow() {
        trace("skipToNext")
        failedItemIds.clear()
        advance(manual = true)
    }

    fun skipToPrevious() = onSession { skipToPreviousNow() }

    internal fun skipToPreviousNow() {
        val current = _nowPlaying.value
        trace("skipToPrevious pos=${current.positionMs}")
        if (current.positionMs > 3_000) {
            seekNow(0)
            return
        }
        val previous = queue.previousIndex() ?: return
        failedItemIds.clear()
        queue.jumpTo(previous)
        startCurrent(ListeningChange.LOCAL_INTENT)
    }

    fun canSkipNext(): Boolean = queue.nextIndex(respectRepeatOne = false) != null

    fun toggleShuffle() = onSession { setShuffleNow(!queue.queue.value.shuffle) }

    internal fun setShuffleNow(enabled: Boolean) {
        editQueue { setShuffle(enabled) }
    }

    fun setRepeat(mode: RepeatMode) = onSession { setRepeatNow(mode) }

    internal fun setRepeatNow(mode: RepeatMode) {
        editQueue { setRepeat(mode) }
    }

    fun cycleRepeat() = onSession {
        val next = when (queue.queue.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        editQueue { setRepeat(next) }
    }

    fun setFavorite(favorite: Boolean) {
        _nowPlaying.update { it.copy(favorite = favorite) }
    }

    /**
     * Keep the Now Playing heart in step with the library when [canonicalId] is still the
     * current track (hearts can change from Home sync merges or other devices mid-track).
     */
    fun syncFavorite(canonicalId: String, favorite: Boolean) {
        _nowPlaying.update { state ->
            if (state.track?.canonicalId == canonicalId && state.favorite != favorite) {
                state.copy(favorite = favorite)
            } else {
                state
            }
        }
    }

    /**
     * Sleep timer end-of-track: when [gate] returns true on natural completion, the session
     * freezes at the ended item instead of advancing. Pass null to clear.
     */
    fun setNaturalCompletionGate(gate: (() -> Boolean)?) {
        onSession {
            naturalCompletionGate = gate
            prefetcher.schedule(turn.generation)
        }
    }

    /**
     * Late-arriving cover (e.g. embedded art extracted after playback started). Only fills a
     * missing artwork on the track that is still current; never overrides existing art.
     */
    fun updateCurrentTrackArtwork(canonicalId: String, artwork: Artwork) = onSession {
        val current = _nowPlaying.value.track ?: return@onSession
        if (current.canonicalId != canonicalId || current.artwork != null) return@onSession
        val updated = current.copy(
            artwork = artwork,
            album = current.album?.let { it.copy(artwork = it.artwork ?: artwork) },
        )
        _nowPlaying.update { state ->
            if (state.track?.canonicalId == canonicalId) state.copy(track = updated) else state
        }
        if (queue.queue.value.current?.track?.canonicalId == canonicalId) {
            queue.replaceCurrentTrack(updated)
        }
    }

    /** A fresh user play request: drop any restore seek and the failure-skip history. */
    private fun beginUserPlay() {
        pendingResumePositionMs = null
        failedItemIds.clear()
    }

    private fun handleNaturalCompletion(generation: Long) {
        if (generation != turn.generation) return
        if (naturalCompletionGate?.invoke() == true) {
            freezeAfterNaturalEnd()
            return
        }
        advance(manual = false, fromGeneration = generation)
    }

    /** Keep the ended item current and idle after a sleep-timer end-of-track consume. */
    private fun freezeAfterNaturalEnd() {
        val position = _nowPlaying.value.durationMs?.takeIf { it > 0 }
            ?: _nowPlaying.value.positionMs
        trace("freezeAfterNaturalEnd track=${_nowPlaying.value.track.traceLabel()} pos=$position")
        beginTransition()
        playJob?.cancel()
        playJob = null
        engine.stop()
        _nowPlaying.update {
            it.copy(
                isPlaying = false,
                buffering = false,
                positionMs = position,
                error = null,
            )
        }
        ledger.record(ListeningChange.AUTOMATIC)
    }

    private fun advance(manual: Boolean, fromGeneration: Long? = null) {
        if (fromGeneration != null && fromGeneration != turn.generation) {
            trace("advance dropped: gen=$fromGeneration current=${turn.generation}")
            return
        }
        val change = if (manual) ListeningChange.LOCAL_INTENT else ListeningChange.AUTOMATIC
        val snapshot = queue.queue.value
        if (snapshot.items.isEmpty()) return
        val next = queue.nextIndex(respectRepeatOne = !manual)
        trace(
            "advance manual=$manual current=${snapshot.currentIndex} next=$next size=${snapshot.items.size} " +
                "repeat=${snapshot.repeat} shuffle=${snapshot.shuffle}",
        )
        if (next == null) {
            val refill = refiller.pendingRefill()
            if (refill != null) {
                val generationAtExhaustion = turn.generation
                playJob?.cancel()
                playJob = scope.launch(sessionContext) {
                    refill.join()
                    continueAfterRefill(generationAtExhaustion)
                }
                return
            }
            stopAtQueueEnd(change)
            return
        }
        val wrapReshuffle = snapshot.shuffle && queue.isWrapToStart(respectRepeatOne = !manual)
        queue.jumpTo(next, reshuffle = wrapReshuffle)
        startCurrent(change)
    }

    /** The queue ran out while a refill was pending: play what it appended, or stop. */
    private fun continueAfterRefill(generationAtExhaustion: Long) {
        if (generationAtExhaustion != turn.generation) return
        val next = queue.nextIndex(respectRepeatOne = false)
        if (next == null) {
            stopAtQueueEnd(ListeningChange.AUTOMATIC)
            return
        }
        queue.jumpTo(next)
        startCurrent(ListeningChange.AUTOMATIC)
    }

    private fun stopAtQueueEnd(change: ListeningChange) {
        trace("stopAtQueueEnd")
        beginTransition()
        playJob?.cancel()
        playJob = null
        engine.stop()
        _nowPlaying.update {
            it.copy(isPlaying = false, buffering = false, positionMs = 0, error = null)
        }
        ledger.record(change)
    }

    private fun beginTransition() {
        prefetcher.invalidate()
        turn.begin()
        startupTarget = null
        pausedSeekTargetMs = null
        pausePending = false
    }

    private fun startCurrent(change: ListeningChange, applyResumePosition: Boolean = false): Job? {
        val item = queue.queue.value.current ?: return null
        val resumeAt = if (applyResumePosition) pendingResumePositionMs else null
        pendingResumePositionMs = null
        val ready = prefetcher.take(item)
        beginTransition()
        val target = StartupTarget(resumeAt)
        startupTarget = target
        val generation = turn.generation
        if (ready != null) prefetcher.markSpeculativeStart(generation)
        trace("startCurrent ${item.track.traceLabel()} gen=$generation (cancel previous job + engine.stopForTransition)")
        playJob?.cancel()
        engine.stopForTransition()
        _nowPlaying.update {
            it.copy(
                track = item.track,
                queueItemId = item.id,
                resolved = null,
                isPlaying = false,
                positionMs = resumeAt?.coerceAtLeast(0L) ?: 0,
                durationMs = item.track.durationMs,
                buffering = true,
                error = null,
                fallback = null,
                favorite = isFavorite(item.track.canonicalId),
                syncWarning = null,
            )
        }
        ledger.record(change)
        refiller.refillIfLow()
        playJob = scope.launch(sessionContext) {
            try {
                starter.start(item.track, item.id, generation, target, ready)
            } finally {
                if (startupTarget === target) startupTarget = null
            }
        }
        return playJob
    }

    private fun startFallback(current: ResolvedPlayback, reason: String?, generation: Long) {
        val target = startupTarget?.let { StartupTarget(it.positionMs) }
        playJob?.cancel()
        startupTarget = target
        playJob = scope.launch(sessionContext) {
            try {
                starter.tryFallback(current, reason, generation, target)
            } finally {
                if (startupTarget === target) startupTarget = null
            }
        }
    }

    /** Skip a dead track when the queue still has something after it. */
    private fun advanceAfterTerminalFailure(generation: Long) {
        if (generation != turn.generation) return
        queue.queue.value.current?.id?.let(failedItemIds::add)
        // Ignore Repeat One here: re-queuing the same dead item would never reach a healthy one.
        val next = queue.nextIndex(respectRepeatOne = false)
        if (next == null) {
            val refill = refiller.pendingRefill()
            if (refill != null) {
                engine.stop()
                playJob = scope.launch(sessionContext) {
                    refill.join()
                    continueAfterRefill(generation)
                }
                return
            }
            failedItemIds.clear()
            beginTransition()
            engine.stop()
            return
        }
        val snapshot = queue.queue.value
        val nextId = snapshot.items.getOrNull(next)?.id
        if (nextId == null || nextId in failedItemIds) {
            // Every reachable entry failed since the last good start: stop instead of cycling forever.
            trace("advanceAfterTerminalFailure: ${failedItemIds.size} entries failed; stopping")
            failedItemIds.clear()
            beginTransition()
            engine.stop()
            _nowPlaying.update { it.copy(isPlaying = false, buffering = false) }
            ledger.record(ListeningChange.AUTOMATIC)
            return
        }
        trace("advanceAfterTerminalFailure -> skip to $next (failed=${failedItemIds.size})")
        val wrapReshuffle = snapshot.shuffle && queue.isWrapToStart(respectRepeatOne = false)
        queue.jumpTo(next, reshuffle = wrapReshuffle)
        startCurrent(ListeningChange.AUTOMATIC)
    }
}
