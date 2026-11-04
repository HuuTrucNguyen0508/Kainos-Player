package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.history.PlayEvent
import com.universalmusic.player.domain.model.Track

/** A track counts as finished when the listener got within this much of its end. */
private const val LISTEN_END_SLACK_MS = 3_000L

/** Shorter listens are transitions (a tap on another song just as this one started), not skips. */
private const val MIN_RECORDED_LISTEN_MS = 1_000L

/**
 * Turns the session's now-playing stream into one [PlayEvent] per listen: a listen ends when the
 * queue moves to another entry, or when Repeat One jumps back to the start of a finished track.
 * Time counts only while playing, so pauses and buffering are not listening.
 */
class ListenTracker(private val clock: () -> Long) {
    private var itemId: String? = null
    private var track: Track? = null
    private var startedAtMs: Long? = null
    private var listenedMs = 0L
    private var playingSinceMs: Long? = null
    private var positionMs = 0L
    private var durationMs: Long? = null

    /** Feeds the next state; returns the listen it ended, if any. */
    fun observe(state: NowPlayingState): PlayEvent? {
        val now = clock()
        playingSinceMs?.let { listenedMs += (now - it).coerceAtLeast(0) }
        val replayed = state.queueItemId == itemId && finished() && state.positionMs + LISTEN_END_SLACK_MS < positionMs
        val ended = if (state.queueItemId != itemId || replayed) close().also { itemId = state.queueItemId } else null
        track = state.track
        positionMs = state.positionMs
        durationMs = state.durationMs ?: state.track?.durationMs
        playingSinceMs = now.takeIf { state.isPlaying }
        if (state.isPlaying && startedAtMs == null) startedAtMs = now
        // Playback that stops at the end without moving on (last entry, sleep timer) has finished this listen.
        return ended ?: if (!state.isPlaying && atEnd(positionMs)) close() else null
    }

    private fun atEnd(position: Long): Boolean {
        val duration = durationMs?.takeIf { it > 0 } ?: return false
        return position >= duration - LISTEN_END_SLACK_MS
    }

    private fun finished(): Boolean = atEnd(maxOf(positionMs, listenedMs))

    /** Ends the current listen; the same entry can start another one. */
    private fun close(): PlayEvent? {
        val event = startedAtMs?.let { started ->
            track?.takeIf { listenedMs >= MIN_RECORDED_LISTEN_MS }?.let { PlayEvent(it, started, listenedMs, completed = finished()) }
        }
        startedAtMs = null
        listenedMs = 0
        return event
    }
}
