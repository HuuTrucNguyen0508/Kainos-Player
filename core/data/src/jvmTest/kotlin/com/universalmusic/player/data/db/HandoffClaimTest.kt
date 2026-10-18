package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.HandoffClaim
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.LocalListeningUpdate
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.SessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class HandoffClaimTest {
    @Test
    fun claimRecordsThePeerRevisionWithTheLocalActivityRevision() = runTest {
        val rig = Rig()
        rig.publishLocal(activeAtMs = 1_000)
        rig.publishLocal(activeAtMs = 2_000)
        rig.receive(revision = 7, activeAtMs = 5_000)
        assertNull(rig.exchange.appliedHandoff("phone"))

        assertEquals(HandoffClaim.CLAIMED, rig.exchange.claimHandoff("phone", PublicationRevision(7)))

        assertEquals(PublicationRevision(7), rig.exchange.appliedHandoff("phone"))
        assertEquals(listOf(7L, 2L), rig.driver.row("SELECT revision, local_revision FROM handoff_applied"))
        assertEquals(HandoffClaim.ALREADY_APPLIED, rig.exchange.claimHandoff("phone", PublicationRevision(7)))
    }

    @Test
    fun aClaimWithoutAnyLocalPublicationStoresZero() = runTest {
        val rig = Rig()
        rig.receive(revision = 3, activeAtMs = 5_000)

        assertEquals(HandoffClaim.CLAIMED, rig.exchange.claimHandoff("phone", PublicationRevision(3)))
        assertEquals(listOf(3L, 0L), rig.driver.row("SELECT revision, local_revision FROM handoff_applied"))
    }

    @Test
    fun aNewerStoredRowRefusesTheOlderClaim() = runTest {
        val rig = Rig()
        rig.receive(revision = 10, activeAtMs = 5_000)
        rig.receive(revision = 11, activeAtMs = 6_000)

        assertEquals(HandoffClaim.PEER_CHANGED, rig.exchange.claimHandoff("phone", PublicationRevision(10)))
        assertEquals(HandoffClaim.PEER_CHANGED, rig.exchange.claimHandoff("tablet", PublicationRevision(10)))
        assertNull(rig.exchange.appliedHandoff("phone"))
    }

    @Test
    fun localActivityCommittedAfterThePeerRefusesTheClaim() = runTest {
        val rig = Rig()
        rig.receive(revision = 4, activeAtMs = 5_000)
        rig.publishLocal(activeAtMs = 5_000)

        assertEquals(HandoffClaim.LOCAL_CHANGED, rig.exchange.claimHandoff("phone", PublicationRevision(4)))
        assertNull(rig.exchange.appliedHandoff("phone"))
    }

    private class Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { KainosDatabase.Schema.create(it) }
        private val storage = KainosStorage(openDriver = { driver }, clock = { 0 })
        private val recorder = DbListeningStore(storage)
        val exchange = DbListeningExchangeStore(storage)
        private var sequence = 0L
        private val queue = listOf(
            PersistedQueueItem("q-a", PersistedTrack("yt:a", "Track a", sources = listOf(PersistedSource("YOUTUBE_MUSIC", "a")))),
        )
        private val portable = (PortableListeningQueue.project(queue) as QueueProjection.Complete).queue

        suspend fun publishLocal(activeAtMs: Long) {
            sequence++
            val state = ListeningState.create(portable, "q-a", 0, activeAtMs, false)
            recorder.commit(ListeningCommit(
                "gui", sequence, SessionSnapshot(items = queue), PublicationOrigin.LOCAL,
                LocalListeningUpdate.Publish(sequence, activeAtMs, state), committedAtMs = activeAtMs,
            ))
        }

        suspend fun receive(revision: Long, activeAtMs: Long) {
            val state = PeerListeningState.Available(portable.hash, portable.entries[0], 0, activeAtMs, false)
            exchange.receivePeer(PeerListening("phone", PublicationRevision(revision), activeAtMs, state))
        }
    }
}
