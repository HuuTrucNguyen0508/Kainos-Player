package com.universalmusic.player.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.SessionSnapshot
import java.nio.file.Files
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class SessionWritePerfTest {
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
                deltas += driver.rowsChanged
                lastTrace = driver.statements.groupingBy { it }.eachCount()
            }
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
    var rowsChanged = 0L
        private set

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
        statements += sql.substringBefore('(').trim()
        rowsChanged += result.value
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
