package com.universalmusic.player.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningRestore
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.LocalListeningPublication
import com.universalmusic.player.data.session.LocalListeningUpdate
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.SessionSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ListeningStoreTest {
    @Test
    fun anEmptyPublicationStillCarriesShuffle() = runTest {
        memoryDriver().use { driver ->
            val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
            val queue = (PortableListeningQueue.project(emptyList()) as QueueProjection.Complete).queue
            val state = ListeningState.create(queue, null, 0, 1_000, false, shuffle = true)
            store.commit(ListeningCommit("empty", 1, SessionSnapshot(), PublicationOrigin.LOCAL,
                LocalListeningUpdate.Publish(1, 1_000, state), 1_000))
            assertEquals(state, (store.readLocalExport() as LocalListeningExport.Available).publication.state)
        }
    }

    @Test
    fun anOldPeerJsonRowWithoutShuffleDecodesAsOff() = runTest {
        memoryDriver().use { driver ->
            val storage = KainosStorage(openDriver = { driver }, clock = { 0 })
            val exchange = DbListeningExchangeStore(storage)
            val queue = (PortableListeningQueue.project(items("a")) as QueueProjection.Complete).queue
            storage.write { db ->
                db.nowPlayingQueries.upsertPeer("old-phone", 1, 1_000,
                    """{"positionMs":42000,"positionAtMs":1000,"isPlaying":false}""", queue.hash.hex, queue.encodeJson())
            }
            val peer = exchange.readPeer("old-phone")!!.listening.state as com.universalmusic.player.data.session.PeerListeningState.Available
            assertEquals(false, peer.shuffle)
            assertEquals(42_000, peer.positionMs)
        }
    }

    @Test
    fun shufflePublicationSurvivesReopenAndPrivateStateDoesNotOverwriteIt() = runTest {
        val directory = Files.createTempDirectory("kainos-listening-shuffle")
        val items = items("a", "b")
        val state = state(items, 1, 42_000, 5_000, false, shuffle = true)
        try {
            fileDriver(directory).use { driver ->
                val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
                store.commit(commit("gui", 1, items, 1, local = LocalListeningUpdate.Publish(1, 5_000, state)))
                assertEquals(true, (store.readLocalExport() as LocalListeningExport.Available).publication.state.shuffle)
                store.commit(commit("gui", 2, items, 1, origin = PublicationOrigin.REMOTE_SUPPRESSED, local = null))
            }
            fileDriver(directory).use { driver ->
                val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
                val published = (store.readLocalExport() as LocalListeningExport.Available).publication
                assertEquals(state, published.state)
                assertEquals(PublicationRevision(1), published.revision)
                val queueJson = driver.row("SELECT queue_json FROM local_listening_queue")
                driver.execute(null, "CREATE TRIGGER reject_queue_rewrite BEFORE INSERT ON local_listening_queue BEGIN SELECT RAISE(ABORT, 'queue rewrite on shuffle toggle'); END", 0)
                driver.execute(null, "CREATE TRIGGER reject_session_queue_rewrite BEFORE INSERT ON session_queue_item BEGIN SELECT RAISE(ABORT, 'session queue rewrite on shuffle toggle'); END", 0)
                store.commit(commit("new", 1, items, 1, local = LocalListeningUpdate.Publish(1, 6_000, state(items, 1, 42_000, 5_000, false))))
                assertEquals(queueJson, driver.row("SELECT queue_json FROM local_listening_queue"))
                assertEquals(false, (store.readLocalExport() as LocalListeningExport.Available).publication.state.shuffle)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun publishesTheRowAndItsMatchingQueueInOneCommit() = runTest {
        val driver = memoryDriver()
        val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
        val queue = items("a", "b", "c")
        assertNull(store.readLocalExport())

        store.commit(commit("gui", 1, queue, current = 1, local = publish(1, queue, current = 1, positionMs = 42_000, at = 5_000, playing = true, active = 5_000)))

        assertEquals(
            LocalListeningExport.Available(LocalListeningPublication(
                PublicationRevision(1), 5_000, state(queue, current = 1, positionMs = 42_000, at = 5_000, playing = true),
            )),
            store.readLocalExport(),
        )
        assertEquals(ListeningRestore(snapshot(queue, current = 1), PublicationOrigin.LOCAL), store.readRestore())
        assertEquals(
            listOf(1L, 5_000L, "q-b", 42_000L, 5_000L, 1L),
            driver.row("SELECT revision, last_active_at_ms, queue_item_id, position_ms, position_at_ms, is_playing FROM local_listening"),
        )
        driver.close()
    }

    @Test
    fun skipWithAnUnchangedQueueRewritesNoQueueRows() = runTest {
        val driver = memoryDriver()
        val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
        val queue = items("a", "b", "c")
        store.commit(commit("gui", 1, queue, current = 0, local = publish(1, queue, current = 0, active = 1_000)))
        val queueJson = driver.row("SELECT queue_json FROM local_listening_queue")

        val before = driver.totalChanges()
        store.commit(commit("gui", 2, queue, current = 1, local = publish(2, queue, current = 1, active = 2_000)))

        assertEquals(4L, driver.totalChanges() - before, "session_state, local_listening, export and receipt only")
        assertEquals(queueJson, driver.row("SELECT queue_json FROM local_listening_queue"))
        assertEquals(PublicationRevision(2), (store.readLocalExport() as LocalListeningExport.Available).publication.revision)

        val edited = items("a", "b", "c", "d")
        val beforeEdit = driver.totalChanges()
        store.commit(commit("gui", 3, edited, current = 1, local = publish(3, edited, current = 1, active = 3_000)))
        assertEquals(12L, driver.totalChanges() - beforeEdit, "3 deleted + 4 inserted queue items, state, portable queue, row, export, receipt")
        driver.close()
    }

    @Test
    fun unavailableExportHidesTheOlderPublicationAfterReopening() = runTest {
        val directory = Files.createTempDirectory("kainos-listening-unavailable")
        try {
            val queue = items("a", "b")
            fileDriver(directory).use { driver ->
                val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
                store.commit(commit("gui", 1, queue, current = 0, local = publish(1, queue, current = 0, active = 1_000)))
                store.commit(commit("gui", 2, queue, current = 1,
                    local = LocalListeningUpdate.Unavailable(2, 9_000, ProjectionFailure.UNSUPPORTED_TRACK)))
            }
            fileDriver(directory).use { driver ->
                val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
                assertEquals(
                    LocalListeningExport.Unavailable(PublicationRevision(2), 9_000, ProjectionFailure.UNSUPPORTED_TRACK),
                    store.readLocalExport(),
                )
                assertEquals(listOf(1L, "q-a"), driver.row("SELECT revision, queue_item_id FROM local_listening"), "diagnostic row kept")
                assertEquals(1, store.readRestore().snapshot.currentIndex)

                store.commit(commit("next-gui", 1, queue, current = 1, local = publish(1, queue, current = 1, active = 10_000)))
                assertEquals(PublicationRevision(3), (store.readLocalExport() as LocalListeningExport.Available).publication.revision)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun privateOnlyCommitPersistsOriginAndLeavesLocalActivityAlone() = runTest {
        val directory = Files.createTempDirectory("kainos-listening-origin")
        try {
            val local = items("a")
            val remote = items("r1", "r2")
            fileDriver(directory).use { driver ->
                val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
                store.commit(commit("gui", 1, local, current = 0, local = publish(1, local, current = 0, active = 1_000)))
                store.commit(commit("gui", 2, remote, current = 1, origin = PublicationOrigin.REMOTE_SUPPRESSED, local = null))
            }
            fileDriver(directory).use { driver ->
                val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
                assertEquals(ListeningRestore(snapshot(remote, current = 1), PublicationOrigin.REMOTE_SUPPRESSED), store.readRestore())
                val publication = (store.readLocalExport() as LocalListeningExport.Available).publication
                assertEquals(PublicationRevision(1), publication.revision)
                assertEquals(1_000, publication.lastActiveAtMs)
                assertEquals("q-a", publication.state.current?.itemId)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun nullActivityKeepsTheDurableActivityTime() = runTest {
        val driver = memoryDriver()
        val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
        val queue = items("a", "b")
        store.commit(commit("gui", 1, queue, current = 0, local = publish(1, queue, current = 0, active = 1_000)))
        store.commit(commit("gui", 2, queue, current = 1, local = publish(2, queue, current = 1, active = null)))
        assertEquals(1_000, (store.readLocalExport() as LocalListeningExport.Available).publication.lastActiveAtMs)
        driver.close()
    }

    @Test
    fun interleavedWritersPublishStrictlyIncreasingRevisionsThatSurviveRestart() = runTest {
        val directory = Files.createTempDirectory("kainos-listening-two-writers")
        try {
            val first = fileDriver(directory)
            val second = fileDriver(directory)
            val a = DbListeningStore(KainosStorage(openDriver = { first }, clock = { 0 }))
            val b = DbListeningStore(KainosStorage(openDriver = { second }, clock = { 0 }))
            val queueA = items("a1", "a2")
            val queueB = items("b1", "b2", "b3")
            val seen = mutableListOf<Pair<Long, String?>>()
            for (round in 1L..3L) {
                a.commit(commit("gui-a", round, queueA, current = 1, local = publish(round, queueA, current = 1, active = round)))
                seen += b.readLocalExport().revisionAndCurrent()
                b.commit(commit("gui-b", round, queueB, current = 2, local = publish(round, queueB, current = 2, active = round)))
                seen += a.readLocalExport().revisionAndCurrent()
            }
            assertEquals<List<Pair<Long, String?>>>(listOf(1L to "q-a2", 2L to "q-b3", 3L to "q-a2", 4L to "q-b3", 5L to "q-a2", 6L to "q-b3"), seen)
            first.close()
            second.close()

            fileDriver(directory).use { driver ->
                val restarted = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
                restarted.commit(commit("gui-c", 1, queueA, current = 0, local = publish(1, queueA, current = 0, active = 9)))
                assertEquals(7L to "q-a1", restarted.readLocalExport().revisionAndCurrent())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun retryOfAnAlreadyCommittedWriteDoesNotReplayOverANewerWriter() = runTest {
        val directory = Files.createTempDirectory("kainos-listening-ambiguous-retry")
        try {
            val first = fileDriver(directory)
            val second = fileDriver(directory)
            val a = DbListeningStore(KainosStorage(openDriver = { first }, clock = { 0 }))
            val b = DbListeningStore(KainosStorage(openDriver = { second }, clock = { 0 }))
            val queueA = items("a1", "a2")
            val queueB = items("b1")
            val fromA = commit("gui-a", 4, queueA, current = 1, local = publish(4, queueA, current = 1, active = 100))
            a.commit(fromA)
            b.commit(commit("gui-b", 1, queueB, current = 0, local = publish(1, queueB, current = 0, active = 200)))

            a.commit(fromA)

            assertEquals(2L to "q-b1", a.readLocalExport().revisionAndCurrent())
            assertEquals(snapshot(queueB, current = 0), a.readRestore().snapshot)
            assertEquals(
                listOf(4L, 4L),
                second.row("SELECT private_sequence, local_sequence FROM listening_writer_receipt WHERE writer_id = 'gui-a'"),
            )
            first.close()
            second.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedCommitRollsBackTheSnapshotRowQueueAndReceipt() = runTest {
        val driver = memoryDriver()
        val store = DbListeningStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
        val queue = items("a", "b")
        store.commit(commit("gui", 1, queue, current = 0, local = publish(1, queue, current = 0, active = 1_000)))
        val edited = items("a", "b", "c")
        val next = commit("gui", 2, edited, current = 2, local = publish(2, edited, current = 2, active = 2_000))

        driver.execute(null, "CREATE TRIGGER fail_row BEFORE INSERT ON local_listening BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        assertFails { store.commit(next) }
        assertEquals(snapshot(queue, current = 0), store.readRestore().snapshot)
        assertEquals(1L to "q-a", store.readLocalExport().revisionAndCurrent())
        assertEquals(listOf(1L, 1L), driver.row("SELECT private_sequence, local_sequence FROM listening_writer_receipt"))

        driver.execute(null, "DROP TRIGGER fail_row", 0)
        store.commit(next)
        assertEquals(snapshot(edited, current = 2), store.readRestore().snapshot)
        assertEquals(2L to "q-c", store.readLocalExport().revisionAndCurrent())
        driver.close()
    }

    @Test
    fun aggregateCommitRechecksAQueueWrittenByAnotherConnection() = runTest {
        val directory = Files.createTempDirectory("kainos-listening-cross-writer")
        try {
            val first = fileDriver(directory)
            val other = fileDriver(directory)
            val store = DbListeningStore(KainosStorage(openDriver = { first }, clock = { 0 }))
            val otherStore = PrivateSnapshotWriter(KainosStorage(openDriver = { other }, clock = { 0 }))
            val queue = items("a", "b", "c")
            store.commit(commit("gui", 1, queue, current = 0, local = null))
            otherStore.write(snapshot(items("x"), current = 0))
            store.commit(commit("gui", 2, queue, current = 1, local = null))
            assertEquals(snapshot(queue, current = 1), otherStore.read())
            first.close()
            other.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private fun LocalListeningExport?.revisionAndCurrent(): Pair<Long, String?> =
    (this as LocalListeningExport.Available).publication.let { it.revision.value to it.state.current?.itemId }

private fun items(vararg ids: String) = ids.map { id ->
    PersistedQueueItem("q-$id", PersistedTrack("yt:$id", "Track $id", sources = listOf(PersistedSource("YOUTUBE_MUSIC", id))))
}

private fun snapshot(items: List<PersistedQueueItem>, current: Int) =
    SessionSnapshot(items = items, currentIndex = current, positionMs = 7_000)

private fun state(items: List<PersistedQueueItem>, current: Int, positionMs: Long, at: Long, playing: Boolean, shuffle: Boolean = false) =
    ListeningState.create(
        (PortableListeningQueue.project(items) as QueueProjection.Complete).queue,
        items[current].id,
        positionMs,
        at,
        playing,
        shuffle,
    )

private fun publish(
    sequence: Long,
    items: List<PersistedQueueItem>,
    current: Int,
    positionMs: Long = 0,
    at: Long = 1_000,
    playing: Boolean = false,
    active: Long?,
) = LocalListeningUpdate.Publish(sequence, active, state(items, current, positionMs, at, playing))

private fun commit(
    writer: String,
    sequence: Long,
    items: List<PersistedQueueItem>,
    current: Int,
    origin: PublicationOrigin = PublicationOrigin.LOCAL,
    local: LocalListeningUpdate?,
) = ListeningCommit(writer, sequence, snapshot(items, current), origin, local, committedAtMs = 1_000)

private fun memoryDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { KainosDatabase.Schema.create(it) }

private fun fileDriver(directory: Path): SqlDriver = JdbcSqliteDriver(
    "jdbc:sqlite:" + directory.resolve("kainos.db"),
    Properties().apply {
        put("foreign_keys", "true")
        put("journal_mode", "WAL")
    },
    KainosDatabase.Schema,
)

internal fun SqlDriver.row(sql: String): List<Any?> {
    val columns = sql.substringAfter("SELECT ").substringBefore(" FROM").split(',').size
    return executeQuery(null, sql, { cursor ->
        cursor.next()
        QueryResult.Value(List(columns) { index -> cursor.getString(index)?.let { it.toLongOrNull() ?: it } })
    }, 0).value
}
