package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.history.ListeningHistory
import com.universalmusic.player.data.history.PlayEvent
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val DAY = 24L * 60 * 60 * 1000

class PlayHistoryStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { KainosDatabase.Schema.create(it) }
    private val store = DbPlayHistoryStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
    private val now = 400 * DAY

    private fun track(id: String) = Track(id, id.substringAfter(':'), listOf(ArtistRef("artist:a", "A")), durationMs = 200_000)

    private suspend fun play(track: Track, daysAgo: Long, listenedMs: Long = 200_000, completed: Boolean = true) =
        store.record(PlayEvent(track, now - daysAgo * DAY, listenedMs, completed), keepSinceMs = 0)

    @Test
    fun onRepeatCountsFinishedOrLongPlaysFromTheLastThirtyDaysAndNeedsTwo() = runTest {
        val often = track("local:often")
        val twice = track("local:twice")
        val skipped = track("local:skipped")
        val oldWindow = track("local:old")
        repeat(3) { play(often, daysAgo = 1) }
        play(twice, daysAgo = 2, listenedMs = 45_000, completed = false)
        play(twice, daysAgo = 29)
        play(skipped, daysAgo = 1)
        play(skipped, daysAgo = 1, listenedMs = 5_000, completed = false)
        play(oldWindow, daysAgo = 3)
        play(oldWindow, daysAgo = 31)

        val history = store.read(now)

        assertEquals(listOf("local:often" to 3, "local:twice" to 2), history.onRepeat.map { it.track.canonicalId to it.plays })
        assertEquals(now - 2 * DAY, history.onRepeat[1].lastPlayedAtMs)
        assertEquals(
            mapOf("local:often" to now - DAY, "local:twice" to now - 2 * DAY, "local:skipped" to now - DAY, "local:old" to now - 3 * DAY),
            history.lastPlayedAtMs,
        )
    }

    @Test
    fun storesTrackMetadataButNeverAStreamUrl() = runTest {
        val youtube = track("yt:abc").copy(sources = listOf(PlaybackSource(
            ProviderId.YOUTUBE_MUSIC, "abc", streamUrl = "https://rr1.googlevideo.com/videoplayback?sig=1",
            isPlayable = true, handle = PlaybackHandle.Url("https://rr1.googlevideo.com/videoplayback?sig=1"),
        )))
        play(youtube, daysAgo = 1)
        play(youtube, daysAgo = 2)

        val json = driver.row("SELECT track_json FROM play_history_track").single() as String
        assertFalse("googlevideo" in json, json)
        assertEquals("abc", store.read(now).onRepeat.single().track.sources.single().providerTrackId)
    }

    @Test
    fun recordingPrunesEventsOlderThanTheKeepLimitAndTheirTracks() = runTest {
        val ancient = track("local:ancient")
        val fresh = track("local:fresh")
        play(ancient, daysAgo = 380)
        store.record(PlayEvent(fresh, now, 200_000, completed = true), keepSinceMs = now - 365 * DAY)

        assertEquals(listOf(1L), driver.row("SELECT count(*) FROM play_event"))
        assertEquals(listOf("local:fresh"), driver.row("SELECT canonical_id FROM play_history_track"))
    }

    @Test
    fun historyPublishesWhatItRecorded() = runTest {
        val history = ListeningHistory(store, backgroundScope, clock = { now })
        history.load()
        history.record(PlayEvent(track("local:a"), now - 2 * DAY, 200_000, completed = true))
        history.record(PlayEvent(track("local:a"), now - DAY, 200_000, completed = true))
        val published = withContext(Dispatchers.Default) {
            withTimeout(5_000) { history.history.first { it.onRepeat.isNotEmpty() } }
        }
        assertEquals(listOf("local:a" to 2), published.onRepeat.map { it.track.canonicalId to it.plays })
    }
}
