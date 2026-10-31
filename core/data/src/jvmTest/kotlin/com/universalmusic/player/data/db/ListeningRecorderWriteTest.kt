package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.session.toSessionSnapshot
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.FlushResult
import com.universalmusic.player.domain.playback.NowPlayingRecorder
import com.universalmusic.player.domain.playback.PlayerSession
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningRecorderWriteTest {
    @Test
    fun firstPlaySkipsAcrossNullDurationSafTracksOnlyUpdateTheLearnedRowAndState() = runTest {
        // In-memory JDBC shares one connection across IO workers. Use the production-style
        // file driver so concurrent recorder transactions get separate connections.
        val directory = java.nio.file.Files.createTempDirectory("listening-recorder")
        val driver = TracingDriver(JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("test.db")}",
            java.util.Properties().apply { put("journal_mode", "WAL") })
            .also { KainosDatabase.Schema.create(it) })
        try {
            val storage = KainosStorage(openDriver = { driver }, clock = { 0 })
            PrivateSnapshotWriter(storage, "earlier-run").write(PlaybackQueue(items = (0 until 8).map(::safItem)).toSessionSnapshot(0, 0))
            val player = recordingSession(storage, backgroundScope)
            player.initializePersistence()
            assertEquals(FlushResult.Committed(), player.flushPersistence())

            repeat(8) { index ->
                driver.reset()
                if (index == 0) player.playQueueIndex(0) else player.skipToNext()
                runCurrent()
                assertEquals(FlushResult.Committed(), player.flushPersistence())
                val queueWrites = driver.statements.filter { "session_queue_item" in it && !it.startsWith("SELECT") }
                assertTrue(queueWrites.all { it.startsWith("UPDATE") } && queueWrites.size <= 1, "skip $index wrote $queueWrites")
                val stored = PrivateSnapshotWriter(storage).read()
                assertEquals(index, stored.currentIndex)
                assertEquals(List(8) { if (it <= index) 180_000L else null }, stored.items.map { it.track.durationMs })
            }
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun skipsAfterLearningWriteNoQueueRowsAndRestoreKeepsDurations() = runTest {
        // In-memory JDBC shares one connection across IO workers. Use the production-style
        // file driver so concurrent recorder transactions get separate connections.
        val directory = java.nio.file.Files.createTempDirectory("listening-recorder")
        val driver = TracingDriver(JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("test.db")}",
            java.util.Properties().apply { put("journal_mode", "WAL") })
            .also { KainosDatabase.Schema.create(it) })
        try {
            val storage = KainosStorage(openDriver = { driver }, clock = { 0 })
            PrivateSnapshotWriter(storage, "earlier-run").write(PlaybackQueue(items = listOf(safItem(0), safItem(1))).toSessionSnapshot(0, 0))
            val player = recordingSession(storage, backgroundScope)
            player.initializePersistence()
            player.playQueueIndex(0)
            runCurrent()
            player.skipToNext()
            runCurrent()
            player.flushPersistence()

            repeat(4) {
                driver.reset()
                player.playQueueIndex(it % 2)
                runCurrent()
                player.flushPersistence()
                assertEquals(
                    emptyList(),
                    driver.statements.filter { ("session_queue_item" in it || "local_listening_queue" in it) && !it.startsWith("SELECT") },
                )
            }

            val restored = recordingSession(storage, backgroundScope)
            restored.initializePersistence()
            assertEquals(listOf(180_000L, 180_000L), restored.queueState.value.items.map { it.track.durationMs })
            assertFalse(restored.nowPlaying.value.isPlaying)
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun localRowFollowsPlayPauseSkipAndSeekWithoutLocalPaths() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { KainosDatabase.Schema.create(it) }
        try {
            val storage = KainosStorage(openDriver = { driver }, clock = { 0 })
            val player = recordingSession(storage, backgroundScope, clock = { 1_000 + testScheduler.currentTime })
            player.initializePersistence()
            val row = "SELECT revision, is_playing, position_ms, queue_item_id, track_json FROM local_listening"

            player.play((0 until 3).map { safItem(it).track }, startIndex = 0)
            runCurrent()
            player.flushPersistence()
            val playing = driver.row(row)
            assertEquals(1L, playing[1])
            assertEquals("localfile:0", storageJson.decodeFromString<com.universalmusic.player.data.library.PersistedTrack>(playing[4] as String).canonicalId)
            assertFalse("content://" in (playing[4] as String))

            player.pauseTransport()
            runCurrent()
            player.flushPersistence()
            assertEquals(0L, driver.row(row)[1])

            player.seekTo(42_000)
            runCurrent()
            player.flushPersistence()
            assertEquals(listOf(0L, 42_000L), driver.row(row).subList(1, 3))

            player.skipToNext()
            runCurrent()
            player.flushPersistence()
            val skipped = driver.row(row)
            assertEquals(listOf(1L, 0L), skipped.subList(1, 3))
            assertEquals(player.queueState.value.current?.id, skipped[3])
            assertTrue((skipped[0] as Long) > (playing[0] as Long))
        } finally {
            driver.close()
        }
    }

    private fun TestScope.recordingSession(
        storage: KainosStorage,
        scope: CoroutineScope,
        clock: () -> Long = { 10 },
    ) = PlayerSession(
        engine = DurationEngine(),
        resolver = DefaultSourceResolver(),
        scope = scope,
        persistence = NowPlayingRecorder(DbListeningStore(storage), scope, workerContext = EmptyCoroutineContext, clock = clock),
        clock = clock,
    )
}
