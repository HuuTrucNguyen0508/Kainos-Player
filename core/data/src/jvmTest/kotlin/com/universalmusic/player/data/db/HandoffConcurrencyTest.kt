package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.HandoffClaim
import com.universalmusic.player.data.session.HandoffRequest
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.LocalListeningUpdate
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.RemoteSession
import com.universalmusic.player.data.session.SessionSnapshot
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class HandoffConcurrencyTest {
    @Test
    fun twoConnectionsCannotInstallTenAfterElevenFinalizes() = runBlocking {
        val directory = Files.createTempDirectory("handoff-concurrency")
        val url = "jdbc:sqlite:${directory.resolve("kainos.db")}"
        val firstDriver = JdbcSqliteDriver(url, properties(), KainosDatabase.Schema)
        val secondDriver = JdbcSqliteDriver(url, properties())
        try {
            val first = KainosStorage({ firstDriver }, clock = { 0 })
            val second = KainosStorage({ secondDriver }, clock = { 0 })
            val a = DbListeningExchangeStore(first)
            val b = DbListeningExchangeStore(second)
            b.readLocalExport()
            a.receivePeer(peer(10))
            a.receivePeer(peer(11))
            val accepting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val newer = async(Dispatchers.Default) {
                a.claimHandoff(HandoffRequest("phone", PublicationRevision(11), null)) {
                    accepting.complete(Unit)
                    release.await()
                    remoteCommit(11)
                }
            }
            withTimeout(5_000) { accepting.await() }
            val older = async(Dispatchers.Default) {
                b.claimHandoff(HandoffRequest("phone", PublicationRevision(10), null)) { error("stale GUI must not install") }
            }
            release.complete(Unit)
            assertEquals(HandoffClaim.CLAIMED, withTimeout(5_000) { newer.await() })
            assertEquals(HandoffClaim.PEER_CHANGED, withTimeout(5_000) { older.await() })
            assertEquals(PublicationRevision(11), b.appliedHandoff("phone"))
            assertEquals(remoteCommit(11).snapshot, DbListeningStore(second).readRestore().snapshot)
        } finally {
            firstDriver.close()
            secondDriver.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun anOlderGuiCheckpointCannotOverwriteTheNewerFinalizedSnapshot() = runBlocking {
        val directory = Files.createTempDirectory("handoff-checkpoint")
        val url = "jdbc:sqlite:${directory.resolve("kainos.db")}"
        val firstDriver = JdbcSqliteDriver(url, properties(), KainosDatabase.Schema)
        val secondDriver = JdbcSqliteDriver(url, properties())
        try {
            val first = KainosStorage({ firstDriver }, clock = { 0 })
            val second = KainosStorage({ secondDriver }, clock = { 0 })
            val a = DbListeningExchangeStore(first)
            val oldRecorder = DbListeningStore(first)
            val b = DbListeningExchangeStore(second)
            a.receivePeer(peer(10))
            assertEquals(HandoffClaim.CLAIMED, a.claimHandoff(HandoffRequest("phone", PublicationRevision(10), null)) { remoteCommit(10) })
            b.receivePeer(peer(11))
            assertEquals(HandoffClaim.CLAIMED, b.claimHandoff(HandoffRequest("phone", PublicationRevision(11), null)) { remoteCommit(11) })
            oldRecorder.commit(remoteCommit(10).copy(writerId = "old-gui", sequence = 99))

            val restored = DbListeningStore(second).readRestore()
            assertEquals(remoteCommit(11).snapshot, restored.snapshot)
            assertEquals(PublicationOrigin.REMOTE_SUPPRESSED, restored.origin)
            assertEquals(PublicationRevision(11), b.appliedHandoff("phone"))
            assertNull(b.readLocalExport(), "remote finalization never manufactures local activity")
            oldRecorder.commit(remoteCommit(10).copy(writerId = "cold-gui", sequence = 1,
                snapshot = SessionSnapshot(items = items(10)), origin = PublicationOrigin.LOCAL))
            assertEquals(remoteCommit(11).snapshot, DbListeningStore(second).readRestore().snapshot)

            val localItems = items(12)
            val localQueue = (PortableListeningQueue.project(localItems) as QueueProjection.Complete).queue
            val localSnapshot = SessionSnapshot(items = localItems)
            DbListeningStore(second).commit(ListeningCommit("local-gui", 1, localSnapshot, PublicationOrigin.LOCAL,
                LocalListeningUpdate.Publish(1, 12_000, ListeningState.create(localQueue, "q-12", 0, 12_000, false)), 12_000))
            oldRecorder.commit(remoteCommit(10).copy(writerId = "old-gui", sequence = 100))
            assertEquals(localSnapshot, DbListeningStore(second).readRestore().snapshot)
        } finally {
            firstDriver.close()
            secondDriver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun peer(revision: Long): PeerListening {
        val queue = (PortableListeningQueue.project(items(revision)) as QueueProjection.Complete).queue
        return PeerListening("phone", PublicationRevision(revision), revision * 1_000,
            PeerListeningState.Available(queue.hash, queue.entries.single(), 42_000, revision * 1_000, false))
    }

    private fun remoteCommit(revision: Long) = ListeningCommit(
        "gui-$revision", 1,
        SessionSnapshot(items = items(revision), positionMs = 42_000, remoteSession = RemoteSession("phone", revision, revision * 1_000)),
        PublicationOrigin.REMOTE_SUPPRESSED, null, revision * 1_000,
    )

    private fun items(revision: Long) = listOf(PersistedQueueItem("q-$revision",
        PersistedTrack("yt:$revision", "Track $revision", sources = listOf(PersistedSource("YOUTUBE_MUSIC", "$revision")))))

    private fun properties() = Properties().apply {
        put("journal_mode", "WAL")
        put("busy_timeout", "5000")
    }
}
