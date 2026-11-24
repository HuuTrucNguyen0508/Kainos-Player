package com.universalmusic.player.platform

import java.net.BindException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * What the status bar shows for mobile data, from Android's `TelephonyDisplayInfo`: its network
 * type and carrier override, passed as their raw `TelephonyManager` / `TelephonyDisplayInfo`
 * constants so this stays testable off the device.
 */
internal fun cellularRadioLabel(networkType: Int, overrideType: Int): String = when {
    networkType == NETWORK_TYPE_NR -> "5G SA"
    overrideType == OVERRIDE_NR_ADVANCED -> "5G+ NSA"
    overrideType == OVERRIDE_NR_NSA || overrideType == OVERRIDE_NR_NSA_MMWAVE -> "5G NSA"
    networkType == NETWORK_TYPE_LTE || networkType == NETWORK_TYPE_LTE_CA ->
        if (overrideType == OVERRIDE_LTE_CA || overrideType == OVERRIDE_LTE_ADVANCED_PRO) "4G+" else "4G"
    networkType in THREE_G -> "3G"
    networkType in TWO_G -> "2G"
    networkType == NETWORK_TYPE_IWLAN -> "IWLAN"
    networkType == NETWORK_TYPE_UNKNOWN -> "unknown radio"
    else -> "radio type $networkType"
}

/**
 * Whether [address] is still assigned to this device. A socket bound to an address the device has
 * lost can never receive again, however long it waits. Binding a throwaway UDP socket to it fails
 * with EADDRNOTAVAIL exactly then; any other failure says nothing, so it counts as present.
 */
internal fun addressStillOnDevice(address: InetAddress): Boolean = try {
    DatagramSocket(InetSocketAddress(address, 0)).close()
    true
} catch (failure: BindException) {
    // Android: "bind failed: EADDRNOTAVAIL (Cannot assign requested address)"; desktop JVM: the text only.
    val message = failure.message.orEmpty()
    !(message.contains("EADDRNOTAVAIL") || message.contains("Cannot assign requested address"))
} catch (_: Exception) {
    true
}

private const val NETWORK_TYPE_UNKNOWN = 0
private const val NETWORK_TYPE_LTE = 13
private const val NETWORK_TYPE_IWLAN = 18
private const val NETWORK_TYPE_LTE_CA = 19
private const val NETWORK_TYPE_NR = 20
private val TWO_G = setOf(1, 2, 4, 7, 11, 16)
private val THREE_G = setOf(3, 5, 6, 8, 9, 10, 12, 14, 15, 17)
private const val OVERRIDE_LTE_CA = 1
private const val OVERRIDE_LTE_ADVANCED_PRO = 2
private const val OVERRIDE_NR_NSA = 3
private const val OVERRIDE_NR_NSA_MMWAVE = 4
private const val OVERRIDE_NR_ADVANCED = 5
