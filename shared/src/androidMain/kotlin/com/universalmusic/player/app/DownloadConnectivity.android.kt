package com.universalmusic.player.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.universalmusic.player.platform.androidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal actual fun monitorDownloadConnectivity(scope: CoroutineScope, changed: () -> Unit) {
    val manager = androidContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) changed()
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    scope.coroutineContext[Job]?.invokeOnCompletion { manager.unregisterNetworkCallback(callback) }
}
