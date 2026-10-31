package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.PlaybackHandle

import com.universalmusic.player.data.session.HandoffClaim
import com.universalmusic.player.data.session.HandoffRequest
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.RemoteSession
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
import com.universalmusic.player.domain.model.SourceFallbackEvent
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.queue.QueueController
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class NowPlayingState(
    val track: Track? = null,
    val queueItemId: String? = null,
    val resolved: ResolvedPlayback? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val buffering: Boolean = false,
    val favorite: Boolean = false,
    val fallback: SourceFallbackEvent? = null,
    val error: String? = null,
    /** Set when Spotify playback cannot be confirmed against the receiver. */
    val syncWarning: String? = null,
)

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

    private var playJob: Job? = null
    private var prefetchJob: Job? = null
    private data class Prefetched(
        val itemId: String,
        val track: Track,
        val preferences: PlaybackPreferences,
        val resolved: ResolvedPlayback,
        val validUntil: Long,
    )
    private var prefetched: Prefetched? = null

    /** Refills the tail of the queue it was started with; dropped when other playback replaces it. */
    private var continuation: QueueContinuation? = null
    private var continuationJob: Job? = null
    /** Bumped on every play/stop transition so late ENDED/FAILED from a prior track are ignored. */
    private var playGeneration: Long = 0L
    /** True only after the current generation has successfully started engine playback. */
    private var completionArmed: Boolean = false
    private var truncatedRetryItemId: String? = null
    /** Last engine status seen by the collector; trace only on change, never per position tick. */
    private var lastTracedStatus: EngineStatus? = null
    /**
     * After cold-start restore, Play re-resolves then seeks here once.
     * Cleared on a normal track start that is not a resume-from-restore.
     */
    private var pendingResumePositionMs: Long? = null
    private class StartupTarget(var positionMs: Long?)

    private var startupTarget: StartupTarget? = null

    /**
     * Queue entries that failed terminally since the last successful start or user play request.
     * Bounds failure-skipping so an all-dead queue under Repeat All stops instead of cycling forever.
     */
    private val failedItemIds = mutableSetOf<String>()

    private var revision = 0L

    /** Session thread only. Advances before any recorded change returns, even while its write is pending. */
    val localRevision: SessionRevision get() = SessionRevision(revision)

    private var origin = PublicationOrigin.LOCAL
    private var lastActiveAtMs: Long? = null
    private var lastRecordedPlaying = false
    private var pausedSeekTargetMs: Long? = null
    private var pausePending = false
    private var persistenceReady = false
    private var checkpointJob: Job? = null
    private var remoteSession: RemoteSession? = null

    /**
     * Optional gate for sleep-timer end-of-track mode. Invoked on natural completion
     * before advance; return true to freeze playback without starting the next item.
     */
    @Volatile
    private var naturalCompletionGate: (() -> Boolean)? = null

    private fun trace(message: String) = PlaybackTrace.log("Session", message)

    private fun Track?.label(): String =
        if (this == null) "<none>" else "'$title' [${sources.firstOrNull()?.provider}] $canonicalId"

    init {
        scope.launch(sessionContext) {
            queueState.collect {
                prefetchJob?.cancel()
                prefetched = null
            }
        }
        scope.launch(sessionContext) {
            engine.state.collectLatest { engineState ->
                val eventGeneration = engineState.playGeneration
                if (engineState.status != lastTracedStatus) {
                    lastTracedStatus = engineState.status
                    trace(
                        "engine -> ${engineState.status} gen=$eventGeneration (session gen=$playGeneration " +
                            "armed=$completionArmed) pos=${engineState.positionMs} dur=${engineState.durationMs}" +
                            (engineState.error?.let { " error=$it" } ?: ""),
                    )
                }
                // Reject stale engine events before touching nowPlaying.
                if (eventGeneration != playGeneration) {
                    if (engineState.status == EngineStatus.ENDED || engineState.status == EngineStatus.FAILED) {
                        trace("dropped stale ${engineState.status} gen=$eventGeneration current=$playGeneration")
                    }
                    return@collectLatest
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
                if (_nowPlaying.value.isPlaying != lastRecordedPlaying && queue.queue.value.items.isNotEmpty()) {
                    record(Change.AUTOMATIC)
                }

                if (eventGeneration != playGeneration) return@collectLatest
                when (engineState.status) {
                    EngineStatus.PLAYING -> if (completionArmed) failedItemIds.clear()
                    EngineStatus.ENDED -> {
                        if (!completionArmed) {
                            trace("ENDED ignored: completion not armed gen=$eventGeneration")
                            return@collectLatest
                        }
                        if (eventGeneration != playGeneration) return@collectLatest
                        completionArmed = false
                        trace("natural completion of ${_nowPlaying.value.track.label()}")
                        handleNaturalCompletion(eventGeneration)
                    }
                    EngineStatus.FAILED -> {
                        if (eventGeneration != playGeneration) return@collectLatest
                        if (!completionArmed && _nowPlaying.value.resolved == null) {
                            trace("FAILED ignored: nothing resolved gen=$eventGeneration error=${engineState.error}")
                            return@collectLatest
                        }
                        val retryAt = engineState.retryAtPositionMs
                        val currentItemId = queue.queue.value.current?.id
                        if (retryAt != null && currentItemId != truncatedRetryItemId) {
                            truncatedRetryItemId = currentItemId
                            pendingResumePositionMs = retryAt
                            startCurrent(Change.AUTOMATIC, applyResumePosition = true)
                            return@collectLatest
                        }
                        completionArmed = false
                        val current = _nowPlaying.value.resolved ?: return@collectLatest
                        val reason = engineState.error
                        trace("engine FAILED for ${current.track.label()}: $reason -> fallback")
                        startFallback(current, reason, eventGeneration)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun updatePreferences(preferences: PlaybackPreferences) {
        _preferences.value = preferences
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
                revision != 0L -> trace("restore skipped: session revision moved from 0 to $revision")
                else -> {
                    origin = restored.origin
                    remoteSession = restored.snapshot.remoteSession
                    if (restored.snapshot.items.isNotEmpty()) installPaused(restored.snapshot)
                }
            }
            persistenceReady = true
        }
        recorder.start()
    }

    /** Write a private checkpoint now (backgrounding, exit) and wait for it. Never pauses playback. */
    suspend fun flushPersistence(): FlushResult {
        val recorder = persistence ?: return FlushResult.Committed()
        val sequence = withContext(sessionContext) { if (persistenceReady) offerCapture(local = false) else null }
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
        HandoffView(LocalHandoffState(now.isPlaying, now.buffering, lastActiveAtMs, remoteSession), localRevision)
    }

    internal suspend fun loadRemotePaused(
        snapshot: SessionSnapshot,
        peer: PeerHandoff,
        expected: SessionRevision,
        exchange: ListeningExchangeStore,
        expectedPublication: PublicationRevision?,
    ): HandoffDecision {
        val recorder = persistence ?: return HandoffDecision.Skip(HandoffSkipReason.PERSISTENCE_UNAVAILABLE)
        val earlySkip = withContext(sessionContext) {
            when {
                _nowPlaying.value.isPlaying -> HandoffSkipReason.LOCAL_PLAYING
                _nowPlaying.value.buffering -> HandoffSkipReason.LOCAL_BUFFERING
                revision != expected.value -> HandoffSkipReason.LOCAL_CHANGED
                else -> null
            }
        }
        if (earlySkip != null) return HandoffDecision.Skip(earlySkip)
        var decision: HandoffDecision = HandoffDecision.Skip(HandoffSkipReason.SESSION_BUSY)
        var rollback: (() -> Unit)? = null
        var finalized = false
        try {
            val claim = recorder.finalizeHandoff(exchange, HandoffRequest(peer.deviceId, peer.revision, expectedPublication)) {
                installWithinDeadline {
                    val now = _nowPlaying.value
                    val skip = when {
                        now.isPlaying -> HandoffSkipReason.LOCAL_PLAYING
                        now.buffering -> HandoffSkipReason.LOCAL_BUFFERING
                        revision != expected.value -> HandoffSkipReason.LOCAL_CHANGED
                        clock() - peer.lastActiveAtMs > HANDOFF_MAX_AGE_MS -> HandoffSkipReason.TOO_OLD
                        lastActiveAtMs != null && peer.lastActiveAtMs <= lastActiveAtMs!! -> HandoffSkipReason.NOT_NEWER
                        remoteSession != null && peer.lastActiveAtMs <= remoteSession!!.lastActiveAtMs -> HandoffSkipReason.NOT_NEWER
                        else -> null
                    }
                    if (skip != null) {
                        decision = HandoffDecision.Skip(skip)
                        return@installWithinDeadline null
                    }
                    val oldQueue = queue.queue.value
                    val oldNow = _nowPlaying.value
                    val oldOrigin = origin
                    val oldRemote = remoteSession
                    val oldResume = pendingResumePositionMs
                    revision++
                    val installedRevision = revision
                    rollback = {
                        if (revision == installedRevision) {
                            queue.restore(oldQueue)
                            _nowPlaying.value = oldNow
                            origin = oldOrigin
                            remoteSession = oldRemote
                            pendingResumePositionMs = oldResume
                            revision++
                            offerCapture(local = false)
                        }
                    }
                    val remoteSnapshot = snapshot.copy(remoteSession = RemoteSession(peer.deviceId, peer.revision.value, peer.lastActiveAtMs))
                    installPaused(remoteSnapshot)
                    origin = PublicationOrigin.REMOTE_SUPPRESSED
                    remoteSession = remoteSnapshot.remoteSession
                    decision = HandoffDecision.Load(peer)
                    recorder.remoteCommit(remoteSnapshot)
                }
            }
            return when (claim) {
                HandoffClaim.CLAIMED -> {
                    finalized = true
                    withContext(sessionContext) {
                        if (revision == expected.value + 1) engine.stopForTransition()
                    }
                    decision
                }
                HandoffClaim.INSTALL_REJECTED -> decision
                HandoffClaim.PEER_CHANGED -> HandoffDecision.Skip(HandoffSkipReason.PEER_CHANGED)
                HandoffClaim.ALREADY_APPLIED -> HandoffDecision.Skip(HandoffSkipReason.ALREADY_APPLIED)
                HandoffClaim.LOCAL_CHANGED -> HandoffDecision.Skip(HandoffSkipReason.LOCAL_CHANGED)
                HandoffClaim.NOT_NEWER -> HandoffDecision.Skip(HandoffSkipReason.NOT_NEWER)
            }
        } catch (error: Exception) {
            if (!finalized) withContext(NonCancellable + sessionContext) { rollback?.invoke() }
            throw error
        }
    }

    private enum class InstallDispatch { PENDING, RUNNING, ABANDONED }

    private suspend fun <T> installWithinDeadline(block: () -> T): T? {
        if (confineTo == null || !confineTo.isDispatchNeeded(currentCoroutineContext())) return block()
        val phase = MutableStateFlow(InstallDispatch.PENDING)
        val result = CompletableDeferred<T>()
        val job = CoroutineScope(NonCancellable + sessionContext).launch {
            if (!phase.compareAndSet(InstallDispatch.PENDING, InstallDispatch.RUNNING)) return@launch
            try {
                result.complete(block())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        val finished = withTimeoutOrNull(HANDOFF_INSTALL_DEADLINE_MS) { result.await(); true } == true
        if (!finished && phase.compareAndSet(InstallDispatch.PENDING, InstallDispatch.ABANDONED)) {
            job.cancel()
            return null
        }
        return result.await()
    }

    private fun installPaused(snapshot: SessionSnapshot) {
        dropContinuation()
        val restored = snapshot.toPlaybackQueue()
        failedItemIds.clear()
        lastRecordedPlaying = false
        restartCheckpoints(playing = false)
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
            track = item?.let { prepareTrack(it.track) },
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
                "track=${item?.track.label()}",
        )
    }

    fun play(track: Track) = onSession {
        beginUserPlay()
        dropContinuation()
        queue.playNow(track)
        startCurrent(Change.LOCAL_INTENT)
    }

    suspend fun playAwait(track: Track) {
        val job = withContext(sessionContext) {
            beginUserPlay()
            dropContinuation()
            queue.playNow(track)
            startCurrent(Change.LOCAL_INTENT)
        }
        job?.join()
    }

    /** [continuation] keeps appending to this queue until other playback replaces it or it is cleared. */
    fun play(tracks: List<Track>, startIndex: Int = 0, continuation: QueueContinuation? = null) = onSession {
        beginUserPlay()
        dropContinuation()
        queue.playNow(tracks, startIndex)
        this.continuation = continuation
        startCurrent(Change.LOCAL_INTENT)
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
        startCurrent(Change.LOCAL_INTENT)
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
        record(Change.USER_PAUSE, playing = false)
    }

    fun clearQueue() = onSession { clearQueueNow(recorded = queue.queue.value.items.isNotEmpty()) }

    private fun clearQueueNow(recorded: Boolean) {
        trace("clearQueue")
        dropContinuation()
        beginTransition()
        playJob?.cancel()
        playJob = null
        pendingResumePositionMs = null
        failedItemIds.clear()
        queue.clear()
        engine.stop()
        _nowPlaying.value = NowPlayingState()
        if (recorded) record(Change.LOCAL_INTENT)
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
                dropContinuation()
                beginTransition()
                playJob?.cancel()
                playJob = null
                engine.stop()
                _nowPlaying.value = NowPlayingState()
                record(Change.LOCAL_INTENT)
            }
            snapshot.current?.id != currentId || _nowPlaying.value.track == null -> startCurrent(Change.LOCAL_INTENT)
            else -> record(Change.LOCAL_INTENT)
        }
    }

    fun retryPlayback() = onSession {
        if (queue.queue.value.current == null) return@onSession
        failedItemIds.clear()
        startCurrent(Change.LOCAL_INTENT)
    }

    fun tryAnotherSource() = onSession {
        val resolved = _nowPlaying.value.resolved ?: return@onSession
        if (resolved.fallbacks.isEmpty()) return@onSession
        val generation = playGeneration
        startFallback(resolved, "Trying another source", generation)
    }

    fun removeFromQueue(itemId: String) = onSession {
        val before = queue.queue.value
        val removingCurrent = before.current?.id == itemId
        val hasSuccessor = queue.remove(itemId)
        if (queue.queue.value === before) return@onSession
        when {
            queue.queue.value.items.isEmpty() -> clearQueueNow(recorded = true)
            !removingCurrent -> record(Change.LOCAL_INTENT)
            hasSuccessor -> startCurrent(Change.LOCAL_INTENT)
            else -> {
                // Removed the final item: stop rather than replaying its predecessor.
                stopAtQueueEnd(Change.LOCAL_INTENT)
                val item = queue.queue.value.current ?: return@onSession
                val track = prepareTrack(item.track)
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
        if (queue.queue.value !== before) record(Change.LOCAL_INTENT)
    }

    fun togglePlayPause() = onSession { togglePlayPauseNow() }

    internal fun togglePlayPauseNow() {
        val now = _nowPlaying.value
        trace("togglePlayPause buffering=${now.buffering} isPlaying=${now.isPlaying}")
        when {
            now.buffering || now.isPlaying -> pauseTransportNow(Change.USER_PAUSE)
            else -> playTransportNow()
        }
    }

    /** Idempotent pause; cancels in-flight resolve/buffering. */
    fun pauseTransport() = onSession { pauseTransportNow(Change.USER_PAUSE) }

    internal fun pauseTransportNow() = pauseTransportNow(Change.USER_PAUSE)

    internal fun pauseForSleepTimer() = onSession { pauseTransportNow(Change.AUTOMATIC) }

    private fun pauseTransportNow(change: Change) {
        val now = _nowPlaying.value
        if (!now.buffering && !now.isPlaying && !lastRecordedPlaying) return
        val engineStatus = engine.state.value.status
        if (now.buffering && !(now.resolved != null && completionArmed)) {
            trace(
                "pauseTransport while buffering -> cancel start of ${_nowPlaying.value.track.label()} " +
                    "(engine=$engineStatus gen=$playGeneration)",
            )
            pendingResumePositionMs = startupTarget?.positionMs ?: now.positionMs.takeIf { it > 0 }
            beginTransition()
            playJob?.cancel()
            playJob = null
            engine.stop()
            _nowPlaying.update { it.copy(buffering = false, isPlaying = false) }
            record(change, playing = false)
            return
        }
        trace("pauseTransport engine=$engineStatus gen=$playGeneration track=${_nowPlaying.value.track.label()}")
        pausePending = true
        _nowPlaying.update { it.copy(isPlaying = false, buffering = false) }
        engine.pause()
        record(change, playing = false)
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
            record(Change.LOCAL_INTENT)
        } else if (engineStatus == EngineStatus.IDLE || engineStatus == EngineStatus.ENDED || engineStatus == EngineStatus.FAILED) {
            failedItemIds.clear()
            startCurrent(Change.LOCAL_INTENT, applyResumePosition = true)
        }
    }

    private fun currentGenerationEngineStatus(): EngineStatus =
        engine.state.value.takeIf { it.playGeneration == playGeneration }?.status ?: EngineStatus.IDLE

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
        record(Change.LOCAL_INTENT)
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
        startCurrent(Change.LOCAL_INTENT)
    }

    fun canSkipNext(): Boolean = queue.nextIndex(respectRepeatOne = false) != null

    fun toggleShuffle() = onSession { setShuffleNow(!queue.queue.value.shuffle) }

    internal fun setShuffleNow(enabled: Boolean) {
        editQueue { setShuffle(enabled) }
    }

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
        naturalCompletionGate = gate
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
        if (generation != playGeneration) return
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
        trace("freezeAfterNaturalEnd track=${_nowPlaying.value.track.label()} pos=$position")
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
        record(Change.AUTOMATIC)
    }

    private fun advance(manual: Boolean, fromGeneration: Long? = null) {
        if (fromGeneration != null && fromGeneration != playGeneration) {
            trace("advance dropped: gen=$fromGeneration current=$playGeneration")
            return
        }
        val change = if (manual) Change.LOCAL_INTENT else Change.AUTOMATIC
        val snapshot = queue.queue.value
        if (snapshot.items.isEmpty()) return
        val next = queue.nextIndex(respectRepeatOne = !manual)
        trace(
            "advance manual=$manual current=${snapshot.currentIndex} next=$next size=${snapshot.items.size} " +
                "repeat=${snapshot.repeat} shuffle=${snapshot.shuffle}",
        )
        if (next == null) {
            val refill = continuationJob?.takeIf { it.isActive } ?: refillQueue()
            if (refill != null) {
                val generationAtExhaustion = playGeneration
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
        if (generationAtExhaustion != playGeneration) return
        val next = queue.nextIndex(respectRepeatOne = false)
        if (next == null) {
            stopAtQueueEnd(Change.AUTOMATIC)
            return
        }
        queue.jumpTo(next)
        startCurrent(Change.AUTOMATIC)
    }

    /** Start a refill once the queue is down to the continuation's low-water mark. Session thread only. */
    private fun refillIfLow() {
        val source = continuation ?: return
        if (continuationJob?.isActive == true) return
        val snapshot = queue.queue.value
        if (snapshot.repeat == RepeatMode.ALL || snapshot.upcoming.size > source.lowWater) return
        refillQueue()
    }

    /** Ask the continuation for a batch and append it at the tail. Null when there is no continuation. */
    private fun refillQueue(): Job? {
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
            record(Change.AUTOMATIC)
        }.also { continuationJob = it }
    }

    private fun dropContinuation() {
        continuation = null
        continuationJob?.cancel()
        continuationJob = null
    }

    private fun stopAtQueueEnd(change: Change) {
        trace("stopAtQueueEnd")
        beginTransition()
        playJob?.cancel()
        playJob = null
        engine.stop()
        _nowPlaying.update {
            it.copy(isPlaying = false, buffering = false, positionMs = 0, error = null)
        }
        record(change)
    }

    private fun beginTransition() {
        prefetchJob?.cancel()
        prefetched = null
        playGeneration++
        completionArmed = false
        startupTarget = null
        pausedSeekTargetMs = null
        pausePending = false
    }

    private fun startCurrent(change: Change, applyResumePosition: Boolean = false): Job? {
        val item = queue.queue.value.current ?: return null
        val resumeAt = if (applyResumePosition) pendingResumePositionMs else null
        pendingResumePositionMs = null
        val ready = prefetched?.takeIf {
            it.itemId == item.id && it.track == item.track &&
                it.preferences == _preferences.value && clock() < it.validUntil
        }?.resolved
        prefetched = null
        prefetchJob?.cancel()
        beginTransition()
        val target = StartupTarget(resumeAt)
        startupTarget = target
        val generation = playGeneration
        trace("startCurrent ${item.track.label()} gen=$generation (cancel previous job + engine.stopForTransition)")
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
        record(change)
        refillIfLow()
        playJob = scope.launch(sessionContext) {
            try {
                playTrack(item.track, item.id, generation, target, ready)
            } finally {
                if (startupTarget === target) startupTarget = null
            }
        }
        return playJob
    }

    private suspend fun playTrack(
        track: Track,
        queueItemId: String,
        generation: Long,
        target: StartupTarget,
        ready: ResolvedPlayback? = null,
    ) {
        currentCoroutineContext().ensureActive()
        if (generation != playGeneration) return
        if (ready != null) {
            startResolved(ready, fallback = null, generation = generation, target = target, alreadyEnriched = true)
            return
        }
        val playable = withContext(workContext) { prepareTrack(track) }
        if (generation != playGeneration) return
        _nowPlaying.update {
            it.copy(
                track = playable,
                queueItemId = queueItemId,
                resolved = null,
                isPlaying = false,
                positionMs = target.positionMs?.coerceAtLeast(0L) ?: 0,
                durationMs = playable.durationMs,
                buffering = true,
                error = null,
                fallback = null,
                favorite = isFavorite(playable.canonicalId),
                syncWarning = null,
            )
        }
        val preferences = _preferences.value
        val resolved = runCatching { withContext(workContext) { resolver.resolve(playable, preferences) } }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                if (generation != playGeneration) return
                currentCoroutineContext().ensureActive()
                trace("resolve failed for ${playable.label()} gen=$generation: ${error.message}")
                _nowPlaying.update { it.copy(buffering = false, error = error.message, isPlaying = false) }
                advanceAfterTerminalFailure(generation)
                return
            }
        if (generation != playGeneration) return
        currentCoroutineContext().ensureActive()
        startResolved(resolved, fallback = null, generation = generation, target = target)
    }

    private suspend fun startResolved(
        resolved: ResolvedPlayback,
        fallback: SourceFallbackEvent?,
        generation: Long,
        target: StartupTarget?,
        alreadyEnriched: Boolean = false,
    ) {
        if (generation != playGeneration) return
        currentCoroutineContext().ensureActive()
        val enrichedSource = runCatching { withContext(workContext) { if (alreadyEnriched) resolved.source else enrichSource(resolved.track, resolved.source) } }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                if (generation != playGeneration) return
                currentCoroutineContext().ensureActive()
                trace("enrich failed for ${resolved.track.label()} via ${resolved.source.provider}: ${error.message}")
                tryFallback(resolved, error.message, generation, target)
                return
            }
        if (generation != playGeneration) {
            trace("startResolved dropped after enrich: gen=$generation current=$playGeneration")
            return
        }
        val playable = resolved.copy(source = enrichedSource)
        currentCoroutineContext().ensureActive()
        _nowPlaying.update {
            it.copy(
                track = playable.track,
                resolved = playable,
                durationMs = playable.track.durationMs ?: it.durationMs,
                buffering = true,
                fallback = fallback,
                error = null,
                favorite = isFavorite(playable.track.canonicalId),
            )
        }
        trace("engine.play ${playable.track.label()} via ${playable.source.provider} gen=$generation")
        runCatching {
            withContext(workContext) {
                val handle = playable.source.handle.let { handle ->
                    if (handle is PlaybackHandle.Url)
                        handle.copy(startPositionMs = target?.positionMs ?: 0, durationMs = playable.track.durationMs) else handle
                }
                engine.play(handle, playable.source.quality, playGeneration = generation)
            }
            if (generation == playGeneration) {
                engine.setVolume(_volume.value)
                target?.positionMs?.let(engine::seekTo)
                if (startupTarget === target) startupTarget = null
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                trace("engine.play cancelled gen=$generation (current=$playGeneration)")
                throw error
            }
            if (generation != playGeneration) return
            currentCoroutineContext().ensureActive()
            trace("engine.play failed gen=$generation: ${error.message}")
            tryFallback(playable, error.message, generation, target)
            return
        }
        if (generation != playGeneration) {
            trace("engine.play returned for superseded gen=$generation current=$playGeneration; not arming")
            return
        }
        completionArmed = true
        trace("engine.play ok gen=$generation; completion armed")
        schedulePrefetch(generation)

    }

    /** One short-lived YouTube result only; no Spotify traffic and no persisted URLs. */
    private fun schedulePrefetch(generation: Long) {
        prefetchJob?.cancel()
        val snapshot = queueState.value
        val next = snapshot.upcoming.firstOrNull() ?: return
        if (next.track.sources.isEmpty() || next.track.sources.any { it.provider != ProviderId.YOUTUBE_MUSIC }) return
        val preferences = _preferences.value
        prefetchJob = scope.launch(sessionContext) {
            delay(1_000)
            if (generation != playGeneration || !_nowPlaying.value.isPlaying) return@launch
            try {
                val resolved = withContext(workContext) {
                    val track = prepareTrack(next.track)
                    val result = resolver.resolve(track, preferences)
                    if (result.source.provider != ProviderId.YOUTUBE_MUSIC) return@withContext null
                    result.copy(source = enrichSource(result.track, result.source))
                } ?: return@launch
                currentCoroutineContext().ensureActive()
                if (generation != playGeneration || queueState.value !== snapshot || preferences != _preferences.value) return@launch
                val url = (resolved.source.handle as? PlaybackHandle.Url)?.url ?: return@launch
                // YouTube signed URLs normally carry epoch-seconds expiry. Leave a safety margin.
                val expires = url.substringAfter('?', "").split('&')
                    .firstOrNull { it.startsWith("expire=") }?.substringAfter('=')?.toLongOrNull()
                    ?.let { it * 1_000 - 30_000 }
                val validUntil = expires ?: (clock() + 30_000)
                if (validUntil > clock()) prefetched = Prefetched(next.id, next.track, preferences, resolved, validUntil)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                trace("prefetch unavailable: ${error.message}")
            }
        }
    }

    private fun startFallback(current: ResolvedPlayback, reason: String?, generation: Long) {
        val target = startupTarget?.let { StartupTarget(it.positionMs) }
        playJob?.cancel()
        startupTarget = target
        playJob = scope.launch(sessionContext) {
            try {
                tryFallback(current, reason, generation, target)
            } finally {
                if (startupTarget === target) startupTarget = null
            }
        }
    }

    private suspend fun tryFallback(
        current: ResolvedPlayback,
        reason: String?,
        generation: Long,
        target: StartupTarget?,
    ) {
        if (generation != playGeneration) return
        trace(
            "tryFallback from ${current.source.provider} reason=$reason remaining=" +
                current.fallbacks.map { it.provider },
        )
        val next = current.fallbacks.firstOrNull() ?: run {
            _nowPlaying.update {
                it.copy(
                    buffering = false,
                    isPlaying = false,
                    error = reason ?: "Playback failed and no fallback is available",
                )
            }
            advanceAfterTerminalFailure(generation)
            return
        }
        val remaining = current.fallbacks.drop(1)
        val event = SourceFallbackEvent(
            from = current.source.provider,
            to = next.provider,
            message = "${current.source.provider.displayName} unavailable → ${next.provider.displayName}",
        )
        startResolved(
            resolved = current.copy(source = next, fallbacks = remaining, reason = event.message),
            fallback = event,
            generation = generation,
            target = target,
        )
    }

    /** Skip a dead track when the queue still has something after it. */
    private fun advanceAfterTerminalFailure(generation: Long) {
        if (generation != playGeneration) return
        queue.queue.value.current?.id?.let(failedItemIds::add)
        // Ignore Repeat One here: re-queuing the same dead item would never reach a healthy one.
        val next = queue.nextIndex(respectRepeatOne = false)
        if (next == null) {
            val refill = continuationJob?.takeIf { it.isActive } ?: refillQueue()
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
            record(Change.AUTOMATIC)
            return
        }
        trace("advanceAfterTerminalFailure -> skip to $next (failed=${failedItemIds.size})")
        val wrapReshuffle = snapshot.shuffle && queue.isWrapToStart(respectRepeatOne = false)
        queue.jumpTo(next, reshuffle = wrapReshuffle)
        startCurrent(Change.AUTOMATIC)
    }

    private enum class Change(val user: Boolean, val reclaimsOrigin: Boolean) {
        LOCAL_INTENT(user = true, reclaimsOrigin = true),
        USER_PAUSE(user = true, reclaimsOrigin = false),
        AUTOMATIC(user = false, reclaimsOrigin = false),
    }

    private fun record(change: Change, playing: Boolean = _nowPlaying.value.isPlaying) {
        revision++
        if (change.reclaimsOrigin) {
            origin = PublicationOrigin.LOCAL
            remoteSession = null
        }
        val wasPlaying = lastRecordedPlaying
        lastRecordedPlaying = playing
        val now = clock()
        val local = origin == PublicationOrigin.LOCAL
        if (local && (change.user || playing || wasPlaying)) lastActiveAtMs = now
        offerCapture(local, now)
        if (playing != wasPlaying) restartCheckpoints(playing)
    }

    private fun offerCapture(local: Boolean, now: Long = clock()): Long? = persistence?.offer(
        queue = queue.queue.value,
        positionMs = _nowPlaying.value.positionMs,
        isPlaying = lastRecordedPlaying,
        capturedAtMs = now,
        lastActiveAtMs = lastActiveAtMs.takeIf { local },
        origin = origin,
        local = local,
        remoteSession = remoteSession,
    )

    private fun restartCheckpoints(playing: Boolean) {
        checkpointJob?.cancel()
        checkpointJob = if (playing && persistence != null) {
            scope.launch(sessionContext) {
                var elapsedMs = 0L
                while (true) {
                    delay(PRIVATE_CHECKPOINT_MS)
                    elapsedMs += PRIVATE_CHECKPOINT_MS
                    if (elapsedMs % HEARTBEAT_MS == 0L && origin == PublicationOrigin.LOCAL) {
                        record(Change.AUTOMATIC)
                    } else {
                        offerCapture(local = false)
                    }
                }
            }
        } else {
            null
        }
    }

    private companion object {
        const val HANDOFF_INSTALL_DEADLINE_MS = 500L
        const val PRIVATE_CHECKPOINT_MS = 10_000L
        const val HEARTBEAT_MS = 60_000L
    }
}

fun PlaybackSource.providerOrNull(): ProviderId = provider
