package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.session.HandoffClaim
import com.universalmusic.player.data.session.HandoffRequest
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.RemoteSession
import com.universalmusic.player.data.session.SessionRevision
import com.universalmusic.player.data.session.SessionSnapshot
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Installs another device's session paused, as one step with the durable handoff claim. */
internal class RemoteSessionLoader(
    private val engine: PlaybackEngine,
    private val recorder: NowPlayingRecorder?,
    private val ledger: ListeningLedger,
    private val nowPlaying: StateFlow<NowPlayingState>,
    private val confineTo: CoroutineDispatcher?,
    private val clock: () -> Long,
    /** Captures the state the install replaces; the result puts it back. */
    private val captureLocal: () -> () -> Unit,
    private val installPaused: (SessionSnapshot) -> Unit,
) {
    private val sessionContext: CoroutineContext = confineTo ?: EmptyCoroutineContext

    suspend fun load(
        snapshot: SessionSnapshot,
        peer: PeerHandoff,
        expected: SessionRevision,
        exchange: ListeningExchangeStore,
        expectedPublication: PublicationRevision?,
    ): HandoffDecision {
        val recorder = recorder ?: return HandoffDecision.Skip(HandoffSkipReason.PERSISTENCE_UNAVAILABLE)
        val earlySkip = withContext(sessionContext) {
            when {
                nowPlaying.value.isPlaying -> HandoffSkipReason.LOCAL_PLAYING
                nowPlaying.value.buffering -> HandoffSkipReason.LOCAL_BUFFERING
                ledger.revision != expected.value -> HandoffSkipReason.LOCAL_CHANGED
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
                    val now = nowPlaying.value
                    val skip = when {
                        now.isPlaying -> HandoffSkipReason.LOCAL_PLAYING
                        now.buffering -> HandoffSkipReason.LOCAL_BUFFERING
                        ledger.revision != expected.value -> HandoffSkipReason.LOCAL_CHANGED
                        clock() - peer.lastActiveAtMs > HANDOFF_MAX_AGE_MS -> HandoffSkipReason.TOO_OLD
                        ledger.lastActiveAtMs != null && peer.lastActiveAtMs <= ledger.lastActiveAtMs!! -> HandoffSkipReason.NOT_NEWER
                        ledger.remoteSession != null && peer.lastActiveAtMs <= ledger.remoteSession!!.lastActiveAtMs -> HandoffSkipReason.NOT_NEWER
                        else -> null
                    }
                    if (skip != null) {
                        decision = HandoffDecision.Skip(skip)
                        return@installWithinDeadline null
                    }
                    rollback = ledger.beginRemoteInstall(captureLocal())
                    val remoteSnapshot = snapshot.copy(remoteSession = RemoteSession(peer.deviceId, peer.revision.value, peer.lastActiveAtMs))
                    installPaused(remoteSnapshot)
                    ledger.adoptRemote(remoteSnapshot.remoteSession)
                    decision = HandoffDecision.Load(peer)
                    recorder.remoteCommit(remoteSnapshot)
                }
            }
            return when (claim) {
                HandoffClaim.CLAIMED -> {
                    finalized = true
                    withContext(sessionContext) {
                        if (ledger.revision == expected.value + 1) engine.stopForTransition()
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

    private companion object {
        const val HANDOFF_INSTALL_DEADLINE_MS = 500L
    }
}
