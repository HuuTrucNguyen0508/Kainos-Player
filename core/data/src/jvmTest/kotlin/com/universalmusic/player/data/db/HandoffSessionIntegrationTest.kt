package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.HandoffRequest
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.RemoteSession
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.HandoffDecision
import com.universalmusic.player.domain.playback.HandoffNotice
import com.universalmusic.player.domain.playback.HandoffPeer
import com.universalmusic.player.domain.playback.HandoffSkipReason
import com.universalmusic.player.domain.playback.ListeningHandoff
import com.universalmusic.player.domain.playback.NowPlayingRecorder
import com.universalmusic.player.domain.playback.PlayerSession
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class HandoffSessionIntegrationTest {
    @Test
    fun aConfinedSessionFinalizesThroughTheRealSqliteTransaction() = runBlocking {
        realHandoff(rejectInstall = false)
    }

    @Test
    fun aConfinedSessionRejectionLeavesTheSqliteSnapshotAndMarkerUntouched() = runBlocking {
        realHandoff(rejectInstall = true)
    }

    private suspend fun realHandoff(rejectInstall: Boolean) {
        val directory = Files.createTempDirectory("handoff-real-session")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("kainos.db")}", schema = KainosDatabase.Schema)
        val owner = Executors.newSingleThreadExecutor { task -> Thread(task, "handoff-session-owner") }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + owner)
        try {
            val storage = KainosStorage({ driver }, clock = { 10_000 })
            val recorderStore = DbListeningStore(storage)
            val exchange = DbListeningExchangeStore(storage)
            val local = SessionSnapshot(items = items("local"), positionMs = 500)
            recorderStore.commit(ListeningCommit("previous", 1, local, PublicationOrigin.LOCAL, null, 0))
            var preparedOn: String? = null
            val session = PlayerSession(DurationEngine(), DefaultSourceResolver(), scope, confineTo = owner,
                prepareTrack = { if (it.canonicalId == "yt:remote") preparedOn = Thread.currentThread().name.substringBefore(" @"); it },
                persistence = NowPlayingRecorder(recorderStore, scope), clock = { 10_000 })
            session.initializePersistence()
            val queue = (PortableListeningQueue.project(items("remote")) as QueueProjection.Complete).queue
            exchange.receivePeer(PeerListening("phone", PublicationRevision(4), 9_000,
                PeerListeningState.Available(queue.hash, queue.entries.single(), 42_000, 9_000, false)))
            exchange.attachPeerQueue("phone", queue)
            var acceptedOn: String? = null
            var snapshotAfterClaim: SessionSnapshot? = null
            val observedExchange = object : ListeningExchangeStore by exchange {
                override suspend fun claimHandoff(request: HandoffRequest, accept: suspend () -> ListeningCommit?) =
                    exchange.claimHandoff(request) {
                        acceptedOn = Thread.currentThread().name.substringBefore(" @")
                        if (rejectInstall) withContext(owner) { session.seekTo(1_000) }
                        accept()
                    }.also { snapshotAfterClaim = recorderStore.readRestore().snapshot }
            }
            val handoff = ListeningHandoff(observedExchange, session, localTracks = { emptyList() }, clock = { 10_000 })
            val result = withTimeout(5_000) { handoff.run(listOf(HandoffPeer("phone", "Phone"))) }
            assertNotEquals("handoff-session-owner", acceptedOn)
            assertFalse(session.nowPlaying.value.isPlaying)
            if (rejectInstall) {
                assertEquals(HandoffDecision.Skip(HandoffSkipReason.LOCAL_CHANGED), result)
                assertEquals(local, snapshotAfterClaim)
                assertNull(exchange.appliedHandoff("phone"))
                assertNull(handoff.notice.value)
                assertEquals(listOf("yt:local"), session.queueState.value.items.map { it.track.canonicalId })
            } else {
                assertIs<HandoffDecision.Load>(result)
                assertEquals("handoff-session-owner", preparedOn)
                assertEquals(PublicationRevision(4), exchange.appliedHandoff("phone"))
                val restored = recorderStore.readRestore()
                assertEquals(PublicationOrigin.REMOTE_SUPPRESSED, restored.origin)
                assertEquals(SessionSnapshot(items = items("remote"), positionMs = 42_000,
                    remoteSession = RemoteSession("phone", 4, 9_000)), restored.snapshot)
                assertEquals(HandoffNotice(1, "Phone"), handoff.notice.value)
                assertNull(exchange.readLocalExport())
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            owner.close()
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun items(id: String) = listOf(PersistedQueueItem("q-$id",
        PersistedTrack("yt:$id", "Track $id", sources = listOf(PersistedSource("YOUTUBE_MUSIC", id)))))
}
