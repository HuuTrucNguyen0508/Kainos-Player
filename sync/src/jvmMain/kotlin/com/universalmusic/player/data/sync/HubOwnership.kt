package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.DesktopIsolation
import com.universalmusic.player.platform.HubUserService
import com.universalmusic.player.platform.HubStopOutcome
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
        data class RecoveringService(val reconcileStop: Boolean) : State
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
            // The service restarts when this GUI closes; give it this build's unit first.
            try {
                service.refreshUnit()
            } catch (error: Exception) {
                com.universalmusic.player.platform.PlaybackTrace.log("Warning", "Kainos hub unit refresh failed: ${error.message}")
            }
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
            val recovery = when (current) {
                is State.Owned -> {
                    stopHub()
                    current.lock.close()
                    State.RecoveringService(reconcileStop = false)
                }
                State.ServiceStopRequested -> State.RecoveringService(reconcileStop = true)
                is State.RecoveringService -> current
                else -> null
            }
            mutex.withLock { state = recovery ?: State.Closed }
            if (recovery != null && mode != HubProcessMode.SUPERVISED_HUB_ONLY) {
                val restart = if (recovery.reconcileStop) {
                    service.reconcileStop() == HubStopOutcome.STOPPED &&
                        service.isEnabled() == true
                } else {
                    serviceEnabledForOwnership()
                }
                if (restart && shouldRestartServiceAfterAcquisitionFailure()) service.start()
            }
            mutex.withLock { state = State.Closed }
        }
    }

    private fun serviceEnabledForOwnership(): Boolean = try {
        service.isEnabled() == true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        com.universalmusic.player.platform.PlaybackTrace.log("Warning", "Kainos hub service unavailable: ${error.message}")
        false
    }

    private fun shouldRestartServiceAfterAcquisitionFailure(): Boolean {
        val probe = try {
            HubOwnerLock.acquire(isolation.dataDir, 0)
        } catch (error: IOException) {
            com.universalmusic.player.platform.PlaybackTrace.log("Warning", "Kainos hub recovery cannot open lock: ${error.message}")
            return true
        }
        if (probe == null) return false
        probe.close()
        return true
    }
}

/**
 * Exit status of a `--hub-only` process that is not serving. Zero means another process already
 * serves this hub (the GUI holds the owner lock, or something else holds the port), so systemd's
 * `Restart=on-failure` must leave it alone: restarting cannot help until that process quits, and
 * the GUI restarts the service itself when it closes. Anything else is a real failure.
 */
fun hubOnlyExitStatus(ownerLockAcquired: Boolean, port: Int): Int = when {
    !ownerLockAcquired -> 0
    tcpPortHeld(port) -> 0
    else -> 1
}

private fun tcpPortHeld(port: Int): Boolean = try {
    java.net.ServerSocket().use { socket ->
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(port))
    }
    false
} catch (_: java.net.BindException) {
    true
}
