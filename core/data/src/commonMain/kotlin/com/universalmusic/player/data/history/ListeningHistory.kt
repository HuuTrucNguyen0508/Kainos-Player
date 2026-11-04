package com.universalmusic.player.data.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Records this device's listens and keeps the latest read of them for Home's shelves. */
class ListeningHistory(
    private val store: PlayHistoryStore?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _history = MutableStateFlow(PlayHistory.EMPTY)
    val history: StateFlow<PlayHistory> = _history.asStateFlow()
    private val mutex = Mutex()

    suspend fun load() = mutex.withLock {
        store?.let { _history.value = it.read(clock()) }
    }

    fun record(event: PlayEvent) {
        val store = store ?: return
        scope.launch {
            mutex.withLock {
                val now = clock()
                store.record(event, keepSinceMs = now - PLAY_HISTORY_RETENTION_MS)
                _history.value = store.read(now)
            }
        }
    }
}
