package com.universalmusic.player.data.db

import com.universalmusic.player.data.history.COUNTED_PLAY_MS
import com.universalmusic.player.data.history.HOME_SHELF_LIMIT
import com.universalmusic.player.data.history.ON_REPEAT_MIN_PLAYS
import com.universalmusic.player.data.history.ON_REPEAT_WINDOW_MS
import com.universalmusic.player.data.history.PlayCount
import com.universalmusic.player.data.history.PlayEvent
import com.universalmusic.player.data.history.PlayHistory
import com.universalmusic.player.data.history.PlayHistoryStore
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.library.withPersistedSourcesOnly
import com.universalmusic.player.domain.failure.StorageArea
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class DbPlayHistoryStore(storage: KainosStorage) : PlayHistoryStore {
    private val storage = AreaStorage(storage, StorageArea.HISTORY)

    override suspend fun record(event: PlayEvent, keepSinceMs: Long) = storage.write { db ->
        val queries = db.playHistoryQueries
        val track = event.track.withPersistedSourcesOnly().toPersisted(event.startedAtMs)
        queries.upsertTrack(track.canonicalId, storageJson.encodeToString(track))
        queries.insertEvent(track.canonicalId, event.startedAtMs, event.listenedMs, event.completed)
        if (queries.deleteEventsBefore(keepSinceMs).value > 0) queries.deleteOrphanTracks()
    }

    override suspend fun read(nowMs: Long): PlayHistory = storage.read { db ->
        val queries = db.playHistoryQueries
        PlayHistory(
            onRepeat = queries.mostPlayedSince(
                sinceMs = nowMs - ON_REPEAT_WINDOW_MS,
                minListenedMs = COUNTED_PLAY_MS,
                minPlays = ON_REPEAT_MIN_PLAYS.toLong(),
                limit = HOME_SHELF_LIMIT.toLong(),
            ).executeAsList().mapNotNull { row ->
                readStorageRow(strict = false) {
                    PlayCount(
                        track = storageJson.decodeFromString<PersistedTrack>(row.track_json).toDomain(),
                        plays = row.plays.toInt(),
                        lastPlayedAtMs = row.last_played_at_ms ?: 0,
                    )
                }
            },
            lastPlayedAtMs = queries.lastPlayed().executeAsList()
                .associate { it.canonical_id to (it.last_played_at_ms ?: 0) },
        )
    }
}
