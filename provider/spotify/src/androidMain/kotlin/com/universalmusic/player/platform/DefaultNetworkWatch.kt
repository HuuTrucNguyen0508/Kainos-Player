package com.universalmusic.player.platform

import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reports when Android's default network has settled on a different network than the one seen at
 * [start]: Wi-Fi to mobile data and back, or a loss followed by a new network. Sockets opened on
 * the old network are dead or about to be, so long-lived connections should be replaced.
 */
internal class DefaultNetworkWatch(
    private val connectivity: ConnectivityManager?,
    private val scope: CoroutineScope,
    private val onSwitched: suspend () -> Unit,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var settling: Job? = null
    private var baseline: Network? = null

    fun start() = synchronized(this) {
        val connectivity = connectivity
        if (connectivity == null || callback != null) return@synchronized
        baseline = connectivity.activeNetwork
        val watch = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
        }
        runCatching { connectivity.registerDefaultNetworkCallback(watch) }
            .onSuccess { callback = watch }
            .onFailure { PlaybackTrace.log("Librespot", "network watch unavailable: $it") }
    }

    fun stop() = synchronized(this) {
        settling?.cancel()
        settling = null
        callback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        callback = null
    }

    /** Registration reports the current network too; comparing against [baseline] ignores that. */
    private fun changed() = synchronized(this) {
        settling?.cancel()
        settling = scope.launch {
            delay(SETTLE_MS)
            val switched = synchronized(this@DefaultNetworkWatch) {
                val now = connectivity?.activeNetwork
                (callback != null && now != null && now != baseline).also { if (it) baseline = now }
            }
            // A separate job: replacing the receiver stops this watch, which would cancel the settle job.
            if (switched) scope.launch { onSwitched() }
        }
    }

    private companion object {
        /** Wi-Fi hand-offs report lost, then available, within a second or two. */
        const val SETTLE_MS = 2_000L
    }
}
