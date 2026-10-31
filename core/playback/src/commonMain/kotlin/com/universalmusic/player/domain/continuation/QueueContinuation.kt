package com.universalmusic.player.domain.continuation

import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.Track

/**
 * Supplies more tracks for the tail of the queue it was started with. [PlayerSession] drops it
 * as soon as other playback replaces that queue or the queue is cleared.
 */
interface QueueContinuation {
    /** Ask for a batch once this many or fewer entries remain after the current one. */
    val lowWater: Int

    /** Tracks to append at the tail of [queue]. Empty means the continuation is finished. */
    suspend fun nextBatch(queue: PlaybackQueue): List<Track>
}
