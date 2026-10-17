package com.universalmusic.player.data.db

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.toSessionSnapshot
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.PlayerSession
import com.universalmusic.player.domain.playback.SessionPersistenceController
import java.nio.file.Files
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionWritePerfTest {
    @Test
    fun skipThroughControllerAfterLearning500SafDurations() = runTest {
        assumeTrue(System.getProperty("kainos.perf") == "true")
        val directory = Files.createTempDirectory("kainos-session-controller-perf")
        val driver = TracingDriver(JdbcSqliteDriver(
            "jdbc:sqlite:" + directory.resolve("kainos.db"),
            Properties().apply { put("journal_mode", "WAL"); put("foreign_keys", "true") },
            KainosDatabase.Schema,
        ))
        try {
            val store = DbSessionSnapshotStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
            val player = PlayerSession(DurationEngine(), DefaultSourceResolver(), backgroundScope)
            val controller = SessionPersistenceController(player, store, backgroundScope)
            player.restorePaused(PlaybackQueue(items = (0 until 500).map(::safItem)).toSessionSnapshot(0, 0))
            for (index in 0 until 500) {
                player.playQueueIndex(index)
                runCurrent()
            }
            controller.flush()
            assertTrue(store.read().items.all { it.track.durationMs == 180_000L })
            val nanos = mutableListOf<Long>()
            repeat(200) { index ->
                player.playQueueIndex(index)
                runCurrent()
                driver.reset()
                val start = System.nanoTime()
                controller.flush()
                nanos += System.nanoTime() - start
                val delta = driver.rowsChanged
                assertEquals(1L, delta)
                assertTrue(driver.statements.none { "session_queue_item" in it })
            }
            assertEquals(199, store.read().currentIndex)
            assertTrue(store.read().items.all { it.track.durationMs == 180_000L })
            val medianMs = nanos.sorted().let { (it[99] + it[100]) / 2.0 } / 1_000_000
            println("TRACE HS1 controller " + driver.statements.joinToString("; "))
            println("PERF HS1 controller delta=1 medianMs=${String.format(Locale.ROOT, "%.3f", medianMs)}")
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun skipInA500EntryQueue() = runBlocking {
        assumeTrue(System.getProperty("kainos.perf") == "true")
        val directory = Files.createTempDirectory("kainos-session-perf")
        try {
            val driver = TracingDriver(JdbcSqliteDriver(
                "jdbc:sqlite:" + directory.resolve("kainos.db"),
                Properties().apply {
                    put("foreign_keys", "true")
                    put("journal_mode", "WAL")
                },
                KainosDatabase.Schema,
            ))
            val store = DbSessionSnapshotStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
            store.write(queueSnapshot(currentIndex = 0, cachedAtMs = 0))
            val deltas = mutableListOf<Long>()
            val nanos = mutableListOf<Long>()
            var lastTrace = emptyMap<String, Int>()
            for (write in 1..200) {
                val snapshot = queueSnapshot(currentIndex = write % 500, cachedAtMs = write.toLong())
                driver.reset()
                val start = System.nanoTime()
                store.write(snapshot)
                nanos += System.nanoTime() - start
                val delta = driver.rowsChanged
                assertEquals(1L, delta)
                assertTrue(driver.statements.none { "session_queue_item" in it })
                deltas += delta
                lastTrace = driver.statements.groupingBy { it }.eachCount()
            }
            assertEquals(queueSnapshot(currentIndex = 200, cachedAtMs = 0), store.read())
            driver.close()
            val medianMs = nanos.sorted().let { (it[99] + it[100]) / 2.0 } / 1_000_000
            println("TRACE HS1 " + lastTrace.entries.joinToString("; ") { "${it.value}x ${it.key}" })
            println("PERF HS1 delta=${deltas.max()} medianMs=${String.format(Locale.ROOT, "%.3f", medianMs)}")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private class TracingDriver(private val inner: SqlDriver) : SqlDriver by inner {
    val statements = mutableListOf<String>()
    private var changesAtStart = 0L
    var rowsChanged = 0L
        private set

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val transaction = inner.newTransaction()
        changesAtStart = inner.totalChanges()
        return transaction
    }

    fun reset() {
        statements.clear()
        rowsChanged = 0
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val result = inner.execute(identifier, sql, parameters, binders)
        rowsChanged = inner.totalChanges() - changesAtStart
        statements += sql.substringBefore('(').trim()
        return result
    }
}

private fun queueSnapshot(currentIndex: Int, cachedAtMs: Long) = SessionSnapshot(
    items = (0 until 500).map { index ->
        PersistedQueueItem(
            id = "queue-$index",
            track = PersistedTrack(
                canonicalId = "local:track-$index",
                title = "Track $index",
                artists = listOf(PersistedArtist("artist:$index", "Artist $index")),
                albumCanonicalId = "album:${index / 10}",
                albumTitle = "Album ${index / 10}",
                durationMs = 200_000L + index,
                artworkUrl = "file:///music/album-${index / 10}/cover.jpg",
                sources = listOf(
                    PersistedSource(
                        provider = "LOCAL",
                        providerTrackId = "track-$index",
                        localLocation = "/music/album-${index / 10}/track-$index.flac",
                        qualityTier = "LOSSLESS",
                        qualityCodec = "flac",
                        qualitySampleRateHz = 44_100,
                        qualityBitDepth = 16,
                        qualityConfidence = "VERIFIED",
                    ),
                ),
                cachedAtMs = cachedAtMs,
            ),
        )
    },
    currentIndex = currentIndex,
    positionMs = 0,
)
