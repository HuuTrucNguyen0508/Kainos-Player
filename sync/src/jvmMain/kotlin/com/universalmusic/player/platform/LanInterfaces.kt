package com.universalmusic.player.platform

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

data class LanInterface(
    val name: String,
    val index: Int,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val ipv4: List<String>,
)

/** The one interface and address the hub advertises and puts in the pairing URI. */
data class LanAddress(val interfaceName: String, val interfaceIndex: Int, val ipv4: String)

/** Docker, VM, VPN and container bridges are not reachable from the phone on home Wi-Fi. */
private val VIRTUAL_INTERFACE_PREFIXES = listOf(
    "docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "podman", "cni", "flannel",
    "lxcbr", "lxdbr", "tailscale", "tun", "tap", "wg", "zt",
)

/**
 * Picks the interface that is up, not loopback, not virtual, and has a private IPv4 address.
 * The default-route interface wins; otherwise 192.168/16, then 10/8, then 172.16/12.
 */
fun selectLanAddress(interfaces: List<LanInterface>, defaultRouteInterface: String?): LanAddress? {
    val eligible = interfaces
        .filter { it.isUp && !it.isLoopback && VIRTUAL_INTERFACE_PREFIXES.none(it.name::startsWith) }
        .flatMap { nic -> nic.ipv4.filter(::isPrivateIpv4).map { LanAddress(nic.name, nic.index, it) } }
    return eligible.firstOrNull { it.interfaceName == defaultRouteInterface }
        ?: eligible.minByOrNull { address ->
            when {
                address.ipv4.startsWith("192.168.") -> 0
                address.ipv4.startsWith("10.") -> 1
                else -> 2
            }
        }
}

fun currentLanAddress(): LanAddress? =
    runCatching { selectLanAddress(systemInterfaces(), defaultRouteInterface()) }.getOrNull()

private fun systemInterfaces(): List<LanInterface> =
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { nic ->
        LanInterface(
            name = nic.name,
            index = nic.index,
            isUp = nic.isUp,
            isLoopback = nic.isLoopback,
            ipv4 = nic.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress },
        )
    }

/** Interface the kernel would use for the default route; a UDP connect sends no packets. */
private fun defaultRouteInterface(): String? = runCatching {
    DatagramSocket().use { socket ->
        socket.connect(InetAddress.getByName("1.1.1.1"), 53)
        NetworkInterface.getByInetAddress(socket.localAddress)?.name
    }
}.getOrNull()

private fun isPrivateIpv4(host: String): Boolean =
    host.startsWith("10.") ||
        host.startsWith("192.168.") ||
        host.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\..*"""))
