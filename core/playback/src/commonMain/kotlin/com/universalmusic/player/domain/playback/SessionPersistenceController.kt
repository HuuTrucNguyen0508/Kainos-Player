package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.SessionSnapshotStore
import com.universalmusic.player.data.session.toSessionSnapshot
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Observes [PlayerSession] queue/transport state and persists a versioned session snapshot.
 * Restores paused (no engine play). Writes are debounced; position is coarse (~10s).
 */
class SessionPersistenceController(
    private val player: PlayerSession,
    private val store: SessionSnapshotStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { currentTimeMillis() },
    private val writeDebounceMs: Long = 400L,
    private val positionPersistIntervalMs: Long = 10_000L,
) {
    private val writeMutex = Mutex()
    private var debounceJob: Job? = null
    private var lastPersistedPositionMs: Long = -1L
    private var observing = false

    /** Load snapshot into the session without starting audio. Safe on corrupt/missing files. */
    suspend fun restore() {
        val snapshot = runCatching { store.read() }.getOrDefault(SessionSnapshot())
        if (snapshot.items.isEmpty()) return
        player.restorePaused(snapshot)
        lastPersistedPositionMs = snapshot.positionMs
    }

    fun startObserving() {
        if (observing) return
        observing = true

        scope.launch {
            player.queue.queue
                .map { queueKey(it) }
                .distinctUntilChanged()
                .collectLatest {
                    scheduleWrite(immediate = false)
                }
        }

        scope.launch {
            player.nowPlaying
                .map { Triple(it.isPlaying, it.positionMs / positionPersistIntervalMs, it.queueItemId) }
                .distinctUntilChanged()
                .collectLatest { (isPlaying, _, _) ->
                    if (!isPlaying) {
                        // Flush on pause / idle so force-stop keeps a recent position.
                        scheduleWrite(immediate = true)
                    } else {
                        scheduleWrite(immediate = false)
                    }
                }
        }
    }

    /** Cancel debounce and write the current session immediately (tests / lifecycle). */
    suspend fun flush() {
        debounceJob?.cancel()
        debounceJob = null
        persistNow()
    }

    private fun scheduleWrite(immediate: Boolean) {
        debounceJob?.cancel()
        if (immediate) {
            debounceJob = scope.launch { persistNow() }
            return
        }
        debounceJob = scope.launch {
            delay(writeDebounceMs)
            persistNow()
        }
    }

    private suspend fun persistNow() = writeMutex.withLock {
        val now = player.nowPlaying.value
        val queue = player.queue.queue.value.withLearnedDuration(now)
        val snapshot = queue.toSessionSnapshot(positionMs = now.positionMs, nowMs = clock())
        runCatching { store.write(snapshot) }
        lastPersistedPositionMs = now.positionMs
    }

    private fun queueKey(queue: PlaybackQueue): String = buildString {
        append(queue.currentIndex)
        append('|')
        append(queue.shuffle)
        append('|')
        append(queue.repeat.name)
        append('|')
        append(queue.shuffleOrder.joinToString(","))
        append('|')
        queue.items.forEach { item ->
            append(item.id)
            append(':')
            append(item.track.canonicalId)
            append(';')
        }
    }
}

/**
 * Android folder (SAF) scans have no track length; once the engine reports one, keep it on the
 * current item so a cold-start restore can show the real duration instead of "--:--".
 */
internal fun PlaybackQueue.withLearnedDuration(now: NowPlayingState): PlaybackQueue {
    val item = current ?: return this
    val learned = now.durationMs?.takeIf { it > 0 } ?: return this
    if (item.track.durationMs != null || now.queueItemId != item.id) return this
    val index = items.indexOf(item)
    val updated = items.toMutableList()
    updated[index] = item.copy(track = item.track.copy(durationMs = learned))
    return copy(items = updated)
}
