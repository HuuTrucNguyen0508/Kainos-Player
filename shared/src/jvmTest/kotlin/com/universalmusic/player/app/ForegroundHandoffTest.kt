package com.universalmusic.player.app

import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.HandoffClaim
import com.universalmusic.player.data.session.HandoffRequest
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.ListeningPairingIdentity
import com.universalmusic.player.data.session.ListeningRestore
import com.universalmusic.player.data.session.ListeningStore
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.PeerReceipt
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueHash
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.StoredPeerListening
import com.universalmusic.player.data.sync.CertFingerprint
import com.universalmusic.player.data.sync.DeliveryTarget
import com.universalmusic.player.data.sync.ListeningDelivery
import com.universalmusic.player.data.sync.ListeningDeliveryRunner
import com.universalmusic.player.data.sync.ListeningHubClient
import com.universalmusic.player.data.sync.ListeningReply
import com.universalmusic.player.data.sync.ListeningReport
import com.universalmusic.player.data.sync.ListeningReportState
import com.universalmusic.player.data.sync.PairedPeer
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.HandoffNotice
import com.universalmusic.player.domain.playback.HandoffPeer
import com.universalmusic.player.domain.playback.ListeningHandoff
import com.universalmusic.player.domain.playback.NowPlayingRecorder
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundHandoffTest {
    @Test
    fun startupWaitsForTheExchangeAndLoadsOnlyTheFreshRowWithOneNotice() = runTest {
        val store = MemoryListeningStore()
        store.peer = peer(10)
        val session = PlayerSession(IdleEngine(), DefaultSourceResolver(), backgroundScope,
            persistence = NowPlayingRecorder(store, backgroundScope), clock = { 20_000 })
        session.initializePersistence()
        val handoff = ListeningHandoff(store, session, localTracks = { emptyList() }, clock = { 20_000 })
        val trigger = ForegroundHandoff {
            backgroundScope.launch { handoff.run(listOf(HandoffPeer("pc", "PC"))) }
        }
        val notifying = PeerNotifyingExchangeStore(store, trigger::onPeerChanged)
        val release = CompletableDeferred<Unit>()
        val next = peer(11)
        val nextQueue = requireNotNull(next.queue)
        val hub = object : ListeningHubClient {
            override suspend fun fetchListening(): ListeningReply {
                release.await()
                return ListeningReply(false, ListeningReport("pc", 11, 9_000,
                    ListeningReportState(nextQueue.hash.hex, nextQueue.entries.single().let { PersistedQueueItem(it.itemId, it.track.track) },
                        42_000, 9_000, false)))
            }
            override suspend fun fetchQueue(hash: QueueHash): PortableListeningQueue {
                yield()
                assertEquals(emptyList(), store.installed, "peer receipt cannot load before the foreground exchange completes")
                return nextQueue
            }
            override suspend fun report(report: ListeningReport): ListeningReply = error("fetch-only cold start")
            override suspend fun uploadQueue(queue: PortableListeningQueue) = error("fetch-only cold start")
            override fun close() = Unit
        }
        val pin = requireNotNull(CertFingerprint.parse("aa".repeat(32)))
        val delivery = ListeningDelivery(notifying,
            { DeliveryTarget(PairedPeer("pc", "PC", pin, "127.0.0.1", 1, 1), "phone", pin) }, { hub }, clock = { 20_000 })
        val runner = ListeningDeliveryRunner(backgroundScope, delivery, { true }, {}, isAppForeground = { true },
            onForegroundExchangeCompleted = trigger::onExchangeCompleted)
        trigger.onForeground()
        trigger.enable()
        runner.request(exchangeOnForeground = true)
        runCurrent()
        assertEquals(emptyList(), store.installed)
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(11L), store.installed)
        assertEquals(listOf("yt:11"), session.queueState.value.items.map { it.track.canonicalId })
        assertEquals(HandoffNotice(1, "PC"), handoff.notice.value)
    }

    @Test
    fun completionBeforeUiEnableWaitsForEnableAndNeverRequestsTwice() {
        var requests = 0
        val trigger = ForegroundHandoff { requests++ }
        trigger.onForeground()
        trigger.onExchangeCompleted()
        assertEquals(0, requests)
        trigger.enable()
        trigger.enable()
        assertEquals(1, requests)
    }

    @Test
    fun theNextForegroundWaitsAgainAndIncomingPeerRowsStillRequestWhileIdle() {
        var requests = 0
        val trigger = ForegroundHandoff { requests++ }
        trigger.enable()
        trigger.onForeground()
        trigger.onPeerChanged()
        assertEquals(0, requests)
        trigger.onExchangeCompleted()
        assertEquals(1, requests)
        trigger.onPeerChanged()
        assertEquals(2, requests)
        trigger.onForeground()
        trigger.onPeerChanged()
        assertEquals(2, requests)
        trigger.onExchangeCompleted()
        assertEquals(3, requests)
    }

    private fun peer(revision: Long): StoredPeerListening {
        val items = listOf(PersistedQueueItem("q-$revision", PersistedTrack("yt:$revision", "Track $revision",
            sources = listOf(PersistedSource("YOUTUBE_MUSIC", "$revision")))))
        val queue = (PortableListeningQueue.project(items) as QueueProjection.Complete).queue
        return StoredPeerListening(PeerListening("pc", PublicationRevision(revision), revision * 1_000,
            PeerListeningState.Available(queue.hash, queue.entries.single(), 42_000, revision * 1_000, false)), queue)
    }
}

private class MemoryListeningStore : ListeningStore, ListeningExchangeStore {
    var peer: StoredPeerListening? = null
    var restore = ListeningRestore(SessionSnapshot(), PublicationOrigin.LOCAL)
    val installed = mutableListOf<Long>()
    override suspend fun readRestore() = restore
    override suspend fun commit(commit: ListeningCommit) { restore = ListeningRestore(commit.snapshot, commit.origin) }
    override suspend fun refusedPairing(): ListeningPairingIdentity? = null
    override suspend fun setRefusedPairing(pairing: ListeningPairingIdentity?) = Unit
    override suspend fun readLocalExport(): LocalListeningExport? = null
    override suspend fun readLocalQueue(hash: QueueHash): PortableListeningQueue? = null
    override suspend fun receivePeer(peer: PeerListening): PeerReceipt {
        this.peer = StoredPeerListening(peer, null)
        return PeerReceipt(true, true)
    }
    override suspend fun attachPeerQueue(deviceId: String, queue: PortableListeningQueue): Boolean {
        peer = requireNotNull(peer).copy(queue = queue)
        return true
    }
    override suspend fun readPeer(deviceId: String) = peer
    override suspend fun deliveredRevision(hubDeviceId: String): PublicationRevision? = null
    override suspend fun markDelivered(hubDeviceId: String, revision: PublicationRevision) = false
    override suspend fun appliedHandoff(deviceId: String) = installed.lastOrNull()?.let(::PublicationRevision)
    override suspend fun claimHandoff(request: HandoffRequest, accept: suspend () -> ListeningCommit?): HandoffClaim {
        val commit = accept() ?: return HandoffClaim.INSTALL_REJECTED
        commit(commit)
        installed += request.revision.value
        return HandoffClaim.CLAIMED
    }
}

private class IdleEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun stop() = Unit
    override fun setVolume(volume: Float) = Unit
}
