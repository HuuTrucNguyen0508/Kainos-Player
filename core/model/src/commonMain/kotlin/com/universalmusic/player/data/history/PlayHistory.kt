package com.universalmusic.player.data.history

import com.universalmusic.player.domain.model.Track

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Events older than this are pruned on each write, so the table stays bounded. */
const val PLAY_HISTORY_RETENTION_MS = 365 * DAY_MS

/** "On repeat" counts plays in this window. */
const val ON_REPEAT_WINDOW_MS = 30 * DAY_MS

/** A track needs this many counted plays in the window to be "on repeat" rather than just recent. */
const val ON_REPEAT_MIN_PLAYS = 2

/** A play counts when it finished or ran at least this long; a quick skip does not. */
const val COUNTED_PLAY_MS = 30_000L

/** "Rediscover" offers hearted tracks whose newest play is at least this old. */
const val REDISCOVER_AFTER_MS = 60 * DAY_MS

/** Most rows one Home shelf shows. */
const val HOME_SHELF_LIMIT = 12

/** One listen of one track on this device, recorded when the listener moves on from it. */
data class PlayEvent(
    val track: Track,
    val startedAtMs: Long,
    val listenedMs: Long,
    val completed: Boolean,
)

data class PlayCount(val track: Track, val plays: Int, val lastPlayedAtMs: Long)

data class PlayHistory(
    /** Counted plays in the [ON_REPEAT_WINDOW_MS] before the read, most first, at least [ON_REPEAT_MIN_PLAYS] each. */
    val onRepeat: List<PlayCount>,
    /** Newest event of any length for every track still in history. */
    val lastPlayedAtMs: Map<String, Long>,
) {
    companion object {
        val EMPTY = PlayHistory(emptyList(), emptyMap())
    }
}

/** Device-local only: never synced and never sent off the device. */
interface PlayHistoryStore {
    /** Appends [event] and drops events that started before [keepSinceMs]. */
    suspend fun record(event: PlayEvent, keepSinceMs: Long)

    suspend fun read(nowMs: Long): PlayHistory
}
