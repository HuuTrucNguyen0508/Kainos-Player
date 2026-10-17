package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.DesktopIsolation
import com.universalmusic.player.platform.HubUserService
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val HUB_OWNED_BY_ANOTHER_INSTANCE = "Home sync hub owned by another instance"

enum class HubProcessMode(val lockTimeoutMs: Long) {
    GUI(5_000),
    HUB_ONLY(30_000),
    SUPERVISED_HUB_ONLY(30_000),
}

class HubOwnership(
    private val isolation: DesktopIsolation,
    private val service: HubUserService,
    val mode: HubProcessMode,
) {
    private sealed interface State {
        data object New : State
        data object ServiceStopRequested : State
        data class Owned(val lock: HubOwnerLock) : State
        data object Unavailable : State
        data class Closing(val previous: State) : State
        data object Closed : State
    }

    private var state: State = State.New
    private val mutex = Mutex()
    private val closeMutex = Mutex()

    suspend fun acquire(): Boolean = mutex.withLock {
        check(state == State.New)
        if (mode == HubProcessMode.GUI && serviceEnabledForOwnership()) {
            state = State.ServiceStopRequested
            service.stop()
        }
        val lock = HubOwnerLock.acquire(isolation.dataDir, mode.lockTimeoutMs)
        state = lock?.let(State::Owned) ?: State.Unavailable
        lock != null
    }

    suspend fun startHub(start: suspend () -> Unit) = mutex.withLock {
        check(state is State.Owned) { HUB_OWNED_BY_ANOTHER_INSTANCE }
        start()
    }

    suspend fun handoffUnmanagedHub(stopHub: suspend () -> Unit): Boolean {
        if (mode != HubProcessMode.HUB_ONLY || !serviceEnabledForOwnership()) return false
        close(stopHub)
        return true
    }

    suspend fun close(stopHub: suspend () -> Unit) = withContext(NonCancellable) {
        closeMutex.withLock {
            val current = mutex.withLock {
                val current = state
                if (current == State.Closed) return@withContext
                if (current is State.Closing) current.previous else current.also { state = State.Closing(it) }
            }
            if (current is State.Owned) {
                stopHub()
                current.lock.close()
            }
            mutex.withLock { state = State.Closed }
            if (mode == HubProcessMode.SUPERVISED_HUB_ONLY ||
                current !is State.Owned && current != State.ServiceStopRequested
            ) return@withLock
            if (!serviceEnabledForOwnership()) return@withLock
            if (current == State.ServiceStopRequested && !shouldRestartServiceAfterAcquisitionFailure()) return@withLock
            service.start()
        }
    }

    private fun serviceEnabledForOwnership(): Boolean = try {
        service.isEnabled()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        System.err.println("Kainos hub service unavailable: ${error.message}")
        false
    }

    private fun shouldRestartServiceAfterAcquisitionFailure(): Boolean {
        val probe = try {
            HubOwnerLock.acquire(isolation.dataDir, 0)
        } catch (error: IOException) {
            System.err.println("Kainos hub recovery cannot open lock: ${error.message}")
            return true
        }
        if (probe == null) return false
        probe.close()
        return true
    }
}
