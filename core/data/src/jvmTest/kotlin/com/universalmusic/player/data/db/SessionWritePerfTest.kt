package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningStore
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.persistedItems
import com.universalmusic.player.data.session.toSessionSnapshot
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.FlushResult
import com.universalmusic.player.domain.playback.NowPlayingRecorder
import com.universalmusic.player.domain.playback.PlayerSession
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.util.Properties
import kotlin.coroutines.EmptyCoroutineContext
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
    fun skipThroughRecorderAfterLearning500SafDurations() = runTest {
        assumeTrue(System.getProperty("kainos.perf") == "true")
        val directory = Files.createTempDirectory("kainos-recorder-perf")
        val driver = TracingDriver(JdbcSqliteDriver(
            "jdbc:sqlite:" + directory.resolve("kainos.db"),
            Properties().apply { put("journal_mode", "WAL"); put("foreign_keys", "true") },
            KainosDatabase.Schema,
        ))
        try {
            val storage = KainosStorage(openDriver = { driver }, clock = { 0 })
            val store = TimedStore(DbListeningStore(storage))
            val queue = PlaybackQueue(items = (0 until 500).map(::safItem))
            PrivateSnapshotWriter(storage, "seed").write(queue.toSessionSnapshot(0, 0))
            val recorded = PlayerSession(
                DurationEngine(), DefaultSourceResolver(), backgroundScope,
                persistence = NowPlayingRecorder(store, backgroundScope, workerContext = EmptyCoroutineContext),
            )
            val plain = PlayerSession(DurationEngine(), DefaultSourceResolver(), backgroundScope)
            recorded.initializePersistence()
            plain.restorePaused(queue.toSessionSnapshot(0, 0))
            for (index in 0 until 500) {
                recorded.playQueueIndex(index)
                plain.playQueueIndex(index)
                runCurrent()
            }
            assertEquals(FlushResult.Committed(), recorded.flushPersistence())
            assertTrue(PrivateSnapshotWriter(storage).read().items.all { it.track.durationMs == 180_000L })

            val withRecorder = mutableListOf<Long>()
            val withoutRecorder = mutableListOf<Long>()
            store.nanos.clear()
            driver.threads.clear()
            repeat(200) { index ->
                driver.reset()
                withoutRecorder += nanos { plain.playQueueIndex(index) }
                withRecorder += nanos { recorded.playQueueIndex(index) }
                runCurrent()
                assertEquals(FlushResult.Committed(), recorded.flushPersistence())
                assertTrue(driver.statements.none { "session_queue_item" in it && !it.startsWith("SELECT") })
                assertTrue(driver.statements.none { "local_listening_queue" in it && !it.startsWith("SELECT") })
                assertTrue("INSERT OR REPLACE INTO local_listening" in driver.statements)
            }
            assertEquals(199, PrivateSnapshotWriter(storage).read().currentIndex)
            val sessionThread = Thread.currentThread().name.substringBefore(" @")
            assertTrue(sessionThread !in driver.threads, "SQL ran on the session thread $sessionThread: ${driver.threads}")

            val projection = mutableListOf<Long>()
            repeat(50) {
                val items = queue.persistedItems(0)
                projection += nanos { check(PortableListeningQueue.project(items) is QueueProjection.Complete) }
            }
            println("TRACE HS5 session=$sessionThread sql=${driver.threads.toSortedSet()} statements=${driver.statements.joinToString("; ")}")
            println(
                "PERF HS5 sessionThread withRecorderMedianUs=${micros(withRecorder.median())} " +
                    "withoutRecorderMedianUs=${micros(withoutRecorder.median())} " +
                    "addedMedianUs=${micros(withRecorder.median() - withoutRecorder.median())}",
            )
            println("PERF HS5 listeningCommit commits=${store.nanos.size} medianMs=${millis(store.nanos.median())}")
            println("PERF HS5 projection500 medianMs=${millis(projection.median())}")
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
            val store = PrivateSnapshotWriter(KainosStorage(openDriver = { driver }, clock = { 0 }))
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
                assertEquals(2L, delta, "session_state and the writer receipt")
                assertTrue(driver.statements.none { "session_queue_item" in it && !it.startsWith("SELECT") })
                deltas += delta
                lastTrace = driver.statements.groupingBy { it }.eachCount()
            }
            assertEquals(queueSnapshot(currentIndex = 200, cachedAtMs = 0), store.read())
            driver.close()
            println("TRACE HS1 " + lastTrace.entries.joinToString("; ") { "${it.value}x ${it.key}" })
            println("PERF HS1 delta=${deltas.max()} medianMs=${millis(nanos.median())}")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private class TimedStore(private val inner: ListeningStore) : ListeningStore by inner {
    val nanos: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    override suspend fun commit(commit: ListeningCommit) {
        val start = System.nanoTime()
        inner.commit(commit)
        nanos += System.nanoTime() - start
    }
}

private inline fun nanos(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return System.nanoTime() - start
}

private fun List<Long>.median(): Long = sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

private fun millis(nanos: Long) = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)

private fun micros(nanos: Long) = String.format(Locale.ROOT, "%.1f", nanos / 1_000.0)

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
