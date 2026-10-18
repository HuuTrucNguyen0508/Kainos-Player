package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.sync.LocalSyncIdentityIndex
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HandoffPeer(val deviceId: String, val name: String)

data class HandoffNotice(val id: Long, val deviceName: String)

class ListeningHandoff(
    private val store: ListeningExchangeStore,
    private val session: PlayerSession,
    private val localTracks: () -> List<Track>,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _notice = MutableStateFlow<HandoffNotice?>(null)
    val notice: StateFlow<HandoffNotice?> = _notice.asStateFlow()
    private var loads = 0L

    fun dismissNotice(id: Long) {
        _notice.update { if (it?.id == id) null else it }
    }

    suspend fun run(peers: List<HandoffPeer>): HandoffDecision? = mutex.withLock {
        try {
            val first = attempt(peers)
            if (first == HandoffDecision.Skip(HandoffSkipReason.SESSION_BUSY)) {
                delay(HANDOFF_BUSY_RETRY_DELAY_MS)
                attempt(peers)
            } else first
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            trace("failed: ${error.message ?: error}")
            null
        }
    }

    private suspend fun attempt(peers: List<HandoffPeer>): HandoffDecision? {
        val view = session.handoffView()
        val export = store.readLocalExport()
        val durableRevision: PublicationRevision? = when (export) {
            is LocalListeningExport.Available -> export.publication.revision
            is LocalListeningExport.Unavailable -> export.revision
            null -> null
        }
        val durableActiveAtMs = when (export) {
            is LocalListeningExport.Available -> export.publication.lastActiveAtMs
            is LocalListeningExport.Unavailable -> export.lastActiveAtMs
            null -> null
        }
        val local = view.local.copy(lastActiveAtMs = listOfNotNull(view.local.lastActiveAtMs, durableActiveAtMs).maxOrNull())
        val now = clock()
        val decisions = peers.distinctBy { it.deviceId }.mapNotNull { peer ->
            val stored = store.readPeer(peer.deviceId) ?: return@mapNotNull null
            peer to decideHandoff(local, stored, store.appliedHandoff(peer.deviceId), now)
        }
        decisions.forEach { (peer, decision) -> if (decision is HandoffDecision.Skip) log(peer, decision) }
        val (peer, load) = decisions
            .mapNotNull { (peer, decision) -> (decision as? HandoffDecision.Load)?.let { peer to it } }
            .maxByOrNull { (_, load) -> load.peer.lastActiveAtMs }
            ?: return decisions.firstOrNull()?.second
        val snapshot = load.peer.toSessionSnapshot(LocalSyncIdentityIndex(localTracks()), now)
        val result = session.loadRemotePaused(snapshot, load.peer, view.revision, store, durableRevision)
        log(peer, result)
        if (result is HandoffDecision.Load) _notice.value = HandoffNotice(++loads, peer.name)
        return result
    }

    private fun log(peer: HandoffPeer, decision: HandoffDecision) = when (decision) {
        is HandoffDecision.Load -> trace(
            "from ${peer.name}: Load rev=${decision.peer.revision.value} " +
                "track=${decision.peer.current.track.identity} position=${decision.peer.state.positionMs}",
        )
        is HandoffDecision.Skip -> trace("from ${peer.name}: Skip(${decision.reason})")
    }

    private fun trace(message: String) = PlaybackTrace.log("Handoff", message)
}

private const val HANDOFF_BUSY_RETRY_DELAY_MS = 100L
