package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.session.HandoffClaim
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.PeerReceipt
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueHash
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.StoredPeerListening
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningHandoffTest {
    private val phone = HandoffPeer("phone", "Poco F7 Ultra")
    private val tablet = HandoffPeer("tablet", "Tablet")

    @Test
    fun aLoadShowsTheNoticeUntilItIsDismissed() = runTest {
        val rig = rig()
        rig.store.peer("phone", revision = 4, activeAtMs = T0 - 1_000, "p1", "p2")

        val decision = rig.handoff.run(listOf(phone))

        assertEquals("p1", ((decision as HandoffDecision.Load).peer.state.current?.track?.track?.title))
        assertEquals(HandoffNotice(1, "Poco F7 Ultra"), rig.handoff.notice.value)
        assertEquals(listOf("yt:p1", "yt:p2"), rig.session.queueState.value.items.map { it.track.canonicalId })
        assertEquals(mapOf("phone" to 4L), rig.store.applied)

        rig.handoff.dismissNotice(2)
        assertEquals(HandoffNotice(1, "Poco F7 Ultra"), rig.handoff.notice.value, "dismissing an older notice keeps the newer one")
        rig.handoff.dismissNotice(1)
        assertNull(rig.handoff.notice.value)
    }

    @Test
    fun theSameRowIsNotLoadedTwice() = runTest {
        val rig = rig()
        rig.store.peer("phone", revision = 4, activeAtMs = T0 - 1_000, "p1")
        rig.handoff.run(listOf(phone))
        rig.handoff.dismissNotice(1)

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.ALREADY_APPLIED), rig.handoff.run(listOf(phone)))
        assertNull(rig.handoff.notice.value)
    }

    @Test
    fun theNewestLoadablePeerWins() = runTest {
        val rig = rig()
        rig.store.peer("phone", revision = 4, activeAtMs = T0 - 5_000, "p1")
        rig.store.peer("tablet", revision = 2, activeAtMs = T0 - 1_000, "t1")

        rig.handoff.run(listOf(phone, tablet))

        assertEquals(HandoffNotice(1, "Tablet"), rig.handoff.notice.value)
        assertEquals(listOf("yt:t1"), rig.session.queueState.value.items.map { it.track.canonicalId })
    }

    @Test
    fun aRefusedClaimLeavesTheSessionAlone() = runTest {
        val rig = rig()
        playAndPause(rig, "mine")
        rig.store.peer("phone", revision = 4, activeAtMs = T0 + 60_000, "p1")
        rig.store.claim = HandoffClaim.LOCAL_CHANGED
        advanceTimeBy(120_000)

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.LOCAL_CHANGED), rig.handoff.run(listOf(phone)))
        assertEquals(listOf("yt:mine"), rig.session.queueState.value.items.map { it.track.canonicalId })
        assertNull(rig.handoff.notice.value)
    }

    @Test
    fun aLocalActionNotYetWrittenStillBeatsAnOlderPeer() = runTest {
        val rig = rig()
        rig.store.peer("phone", revision = 4, activeAtMs = T0 + 30_000, "p1")
        rig.store.localActiveAtMs = T0
        advanceTimeBy(60_000)
        playAndPause(rig, "mine")

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.NOT_NEWER), rig.handoff.run(listOf(phone)))
        assertEquals(listOf("yt:mine"), rig.session.queueState.value.items.map { it.track.canonicalId })
    }

    @Test
    fun durableActivityFromAnEarlierRunBeatsAnOlderPeer() = runTest {
        val rig = rig()
        rig.store.peer("phone", revision = 4, activeAtMs = T0 - 5_000, "p1")
        rig.store.localActiveAtMs = T0 - 1_000

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.NOT_NEWER), rig.handoff.run(listOf(phone)))
    }

    @Test
    fun noStoredRowMeansNoDecision() = runTest {
        assertNull(rig().handoff.run(listOf(phone, tablet)))
    }

    @Test
    fun aRejectedSessionInstallDoesNotConsumeThePeerRevision() = runTest {
        val rig = rig()
        rig.store.peer("phone", 4, T0 - 1_000, "p1")
        rig.store.beforeClaim = { rig.session.play(listOf(yt("mine")), startIndex = 0) }

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.LOCAL_CHANGED), rig.handoff.run(listOf(phone)))
        assertNull(rig.store.appliedHandoff("phone"), "a rejected install must remain retryable")
    }

    @Test
    fun aPeerSupersededBetweenClaimAndInstallCannotReplaceTheSession() = runTest {
        val rig = rig()
        rig.store.peer("phone", 10, T0 - 1_000, "p10")
        rig.store.afterClaim = {
            rig.store.peer("phone", 11, T0, "p11")
            rig.store.applied["phone"] = 11
        }

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.PEER_CHANGED), rig.handoff.run(listOf(phone)))
        assertEquals(emptyList(), rig.session.queueState.value.items)
        assertNull(rig.handoff.notice.value)
    }

    @Test
    fun aDelayedOtherGuiPublicationInvalidatesTheObservedLocalGeneration() = runTest {
        val rig = rig()
        rig.store.localActiveAtMs = T0 - 10_000
        rig.store.peer("phone", 4, T0 - 1_000, "p1")
        rig.store.beforeClaim = { rig.store.localRevision = 2 }

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.LOCAL_CHANGED), rig.handoff.run(listOf(phone)))
        assertNull(rig.store.appliedHandoff("phone"))
        assertEquals(emptyList(), rig.session.queueState.value.items)
    }

    @Test
    fun anOlderUnappliedPeerCannotReplaceTheNewerRepresentedRemoteSession() = runTest {
        val rig = rig()
        rig.store.peer("phone", 4, T0 - 5_000, "p1")
        rig.store.peer("tablet", 2, T0 - 1_000, "t1")
        rig.handoff.run(listOf(phone, tablet))

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.NOT_NEWER), rig.handoff.run(listOf(phone)))
        assertEquals(listOf("yt:t1"), rig.session.queueState.value.items.map { it.track.canonicalId })
        assertEquals(mapOf("tablet" to 2L), rig.store.applied)
    }

    private class Rig(val session: PlayerSession, val store: FakeExchange, val handoff: ListeningHandoff)

    private fun TestScope.rig(): Rig {
        val clock = { T0 + testScheduler.currentTime }
        val session = PlayerSession(ScriptedEngine(), DefaultSourceResolver(), backgroundScope, clock = clock)
        val store = FakeExchange()
        return Rig(session, store, ListeningHandoff(store, session, localTracks = { emptyList() }, clock = clock))
    }

    private fun TestScope.playAndPause(rig: Rig, id: String) {
        rig.session.play(listOf(yt(id)), startIndex = 0)
        runCurrent()
        rig.session.pauseTransport()
        runCurrent()
    }
}

private const val T0 = 1_000_000L

private fun yt(id: String) = track(id, "Artist", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:$id")

private class FakeExchange : ListeningExchangeStore {
    override suspend fun refusedPairing(): com.universalmusic.player.data.session.ListeningPairingIdentity? = null
    override suspend fun setRefusedPairing(pairing: com.universalmusic.player.data.session.ListeningPairingIdentity?) = Unit
    private val peers = mutableMapOf<String, StoredPeerListening>()
    val applied = mutableMapOf<String, Long>()
    var beforeClaim: suspend () -> Unit = {}
    var afterClaim: suspend () -> Unit = {}
    var localRevision = 1L
    var claim = HandoffClaim.CLAIMED
    var localActiveAtMs: Long? = null

    fun peer(deviceId: String, revision: Long, activeAtMs: Long, vararg ids: String) {
        val items = ids.map { PersistedQueueItem("q-$it", yt(it).toPersisted(0)) }
        val queue = (PortableListeningQueue.project(items) as QueueProjection.Complete).queue
        val state = PeerListeningState.Available(queue.hash, queue.entries[0], 1_000, activeAtMs, isPlaying = false)
        peers[deviceId] = StoredPeerListening(PeerListening(deviceId, PublicationRevision(revision), activeAtMs, state), queue)
    }

    override suspend fun readLocalExport(): LocalListeningExport? = localActiveAtMs?.let {
        LocalListeningExport.Unavailable(PublicationRevision(localRevision), it, ProjectionFailure.UNSUPPORTED_TRACK)
    }

    override suspend fun readLocalQueue(hash: QueueHash): PortableListeningQueue? = null
    override suspend fun receivePeer(peer: PeerListening) = PeerReceipt(stored = false, needsQueue = false)
    override suspend fun attachPeerQueue(deviceId: String, queue: PortableListeningQueue) = false
    override suspend fun readPeer(deviceId: String): StoredPeerListening? = peers[deviceId]
    override suspend fun deliveredRevision(hubDeviceId: String): PublicationRevision? = null
    override suspend fun markDelivered(hubDeviceId: String, revision: PublicationRevision) = false
    override suspend fun appliedHandoff(deviceId: String) = applied[deviceId]?.let(::PublicationRevision)

    override suspend fun claimHandoff(deviceId: String, revision: PublicationRevision): HandoffClaim {
        beforeClaim()
        if (claim == HandoffClaim.CLAIMED) applied[deviceId] = revision.value
        afterClaim()
        return claim
    }
}
