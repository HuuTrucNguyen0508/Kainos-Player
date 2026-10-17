package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.DesktopIsolation
import com.universalmusic.player.platform.HubUserService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val HUB_OWNED_BY_ANOTHER_INSTANCE = "Home sync hub owned by another instance"

enum class HubProcessMode(val lockTimeoutMs: Long) {
    GUI(5_000),
    HUB_ONLY(30_000),
}

class HubOwnership(
    private val isolation: DesktopIsolation,
    private val service: HubUserService,
    private val mode: HubProcessMode,
) {
    private sealed interface State {
        data object New : State
        data class Owned(val lock: HubOwnerLock) : State
        data object Unavailable : State
        data object Closing : State
        data object Closed : State
    }

    private var state: State = State.New
    private val mutex = Mutex()

    suspend fun acquire(): Boolean = mutex.withLock {
        check(state == State.New)
        if (mode == HubProcessMode.GUI && service.isEnabled()) service.stop()
        val lock = HubOwnerLock.acquire(isolation.dataDir, mode.lockTimeoutMs)
        state = lock?.let(State::Owned) ?: State.Unavailable
        lock != null
    }

    suspend fun startHub(start: suspend () -> Unit) = mutex.withLock {
        check(state is State.Owned) { HUB_OWNED_BY_ANOTHER_INSTANCE }
        start()
    }

    suspend fun close(stopHub: suspend () -> Unit) {
        val owned = mutex.withLock {
            val current = state
            if (current == State.Closing || current == State.Closed) return
            state = State.Closing
            current as? State.Owned
        }
        stopHub()
        owned?.lock?.close()
        mutex.withLock { state = State.Closed }
        if (owned != null && mode == HubProcessMode.GUI && service.isEnabled()) service.start()
    }
}
