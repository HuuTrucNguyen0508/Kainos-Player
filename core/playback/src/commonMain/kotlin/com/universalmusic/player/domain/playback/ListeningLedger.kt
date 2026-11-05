package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.RemoteSession
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.ProviderId
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal enum class ListeningChange(val user: Boolean, val reclaimsOrigin: Boolean) {
    LOCAL_INTENT(user = true, reclaimsOrigin = true),
    USER_PAUSE(user = true, reclaimsOrigin = false),
    AUTOMATIC(user = false, reclaimsOrigin = false),
}

/**
 * What this device has recorded about its listening: the revision, who owns publication, when it
 * was last active, and the checkpoint timer that runs while playing. Session thread only.
 */
internal class ListeningLedger(
    private val persistence: NowPlayingRecorder?,
    private val queue: StateFlow<PlaybackQueue>,
    private val nowPlaying: StateFlow<NowPlayingState>,
    private val radio: () -> ProviderId?,
    private val scope: CoroutineScope,
    private val sessionContext: CoroutineContext,
    private val clock: () -> Long,
) {
    /** Advances before any recorded change returns, even while its write is pending. */
    var revision = 0L
        private set
    var lastActiveAtMs: Long? = null
        private set
    var lastRecordedPlaying = false
        private set
    var remoteSession: RemoteSession? = null
        private set
    var persistenceReady = false
        private set
    private var origin = PublicationOrigin.LOCAL
    private var checkpointJob: Job? = null

    fun record(change: ListeningChange, playing: Boolean = nowPlaying.value.isPlaying) {
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

    /** A private snapshot of the current state; null without persistence. */
    fun checkpoint(): Long? = offerCapture(local = false)

    fun markReady() {
        persistenceReady = true
    }

    fun adoptRestored(origin: PublicationOrigin, remoteSession: RemoteSession?) {
        this.origin = origin
        this.remoteSession = remoteSession
    }

    fun markPausedInstall() {
        lastRecordedPlaying = false
        restartCheckpoints(playing = false)
    }

    /**
     * Claims the next revision for a remote install. The returned rollback calls [restoreLocal] and
     * undoes the claim, unless another change has recorded since.
     */
    fun beginRemoteInstall(restoreLocal: () -> Unit): () -> Unit {
        val oldOrigin = origin
        val oldRemote = remoteSession
        revision++
        val installedRevision = revision
        return {
            if (revision == installedRevision) {
                restoreLocal()
                origin = oldOrigin
                remoteSession = oldRemote
                revision++
                offerCapture(local = false)
            }
        }
    }

    fun adoptRemote(remoteSession: RemoteSession?) {
        origin = PublicationOrigin.REMOTE_SUPPRESSED
        this.remoteSession = remoteSession
    }

    private fun offerCapture(local: Boolean, now: Long = clock()): Long? = persistence?.offer(
        queue = queue.value,
        positionMs = nowPlaying.value.positionMs,
        isPlaying = lastRecordedPlaying,
        capturedAtMs = now,
        lastActiveAtMs = lastActiveAtMs.takeIf { local },
        origin = origin,
        local = local,
        remoteSession = remoteSession,
        radio = radio(),
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
                        record(ListeningChange.AUTOMATIC)
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
        const val PRIVATE_CHECKPOINT_MS = 10_000L
        const val HEARTBEAT_MS = 60_000L
    }
}
