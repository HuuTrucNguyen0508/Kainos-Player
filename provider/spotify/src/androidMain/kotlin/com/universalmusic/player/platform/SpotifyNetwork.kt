package com.universalmusic.player.platform

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal data class DefaultNetwork(val label: String, val validated: Boolean) {
    override fun toString() = label
}

/** Android's default network as the receiver sees it, for traces and recovery decisions. */
internal fun ConnectivityManager?.defaultNetwork(): DefaultNetwork {
    val capabilities = runCatching { this?.activeNetwork?.let(::getNetworkCapabilities) }.getOrNull()
        ?: return DefaultNetwork(spotifyNetworkLabel(present = false), validated = false)
    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    return DefaultNetwork(
        spotifyNetworkLabel(
            present = true,
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            validated = validated,
        ),
        validated,
    )
}
