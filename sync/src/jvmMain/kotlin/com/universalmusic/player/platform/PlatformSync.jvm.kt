package com.universalmusic.player.platform

import io.ktor.serialization.kotlinx.json.json
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

actual fun createHomeLanSyncHub(
    pairing: com.universalmusic.player.data.sync.HomeLanSyncPairing,
    onHearts: suspend (com.universalmusic.player.data.sync.HeartsSyncDocument) -> com.universalmusic.player.data.sync.HeartsSyncDocument,
    onPlaylists: suspend (com.universalmusic.player.data.playlist.PlaylistsSyncDocument) -> com.universalmusic.player.data.playlist.PlaylistsSyncDocument,
    vault: com.universalmusic.player.data.sync.HomeLanVaultStore?,
    heartedVaultFileNames: () -> Set<String>?,
): com.universalmusic.player.data.sync.HomeLanSyncHub {
    val pin = pairing.hubCertSha256Hex
        ?: error("Hub certificate pin missing; re-run Start hub pairing")
    val tls = com.universalmusic.player.data.sync.loadHomeLanHubTls(
        configDir = homeLanConfigDir().toFile(),
        sharedSecretHex = pairing.sharedSecretHex,
        expectedCertSha256Hex = pin,
    )
    return com.universalmusic.player.data.sync.DesktopHomeLanSyncHub(
        pairing = pairing,
        onHearts = onHearts,
        onPlaylists = onPlaylists,
        vault = vault,
        tls = tls,
        heartedVaultFileNames = heartedVaultFileNames,
    )
}

actual fun createHomeLanVaultStore(
    vaultRootProvider: () -> String?,
): com.universalmusic.player.data.sync.HomeLanVaultStore =
    com.universalmusic.player.data.sync.JvmHomeLanVaultStore(
        vaultRootProvider = vaultRootProvider,
        statePath = homeLanConfigDir().resolve("vault-sync-state.json"),
    )

actual fun homeLanHubPort(): Int = DesktopIsolation.current.hubPort

actual fun detectLanHostAddress(): String? =
    runCatching { defaultRouteIpv4() }.getOrNull()?.takeIf(::isPrivateIpv4)
        ?: runCatching { pickLanHostAddress(lanIpv4Candidates()) }.getOrNull()

/** Source address the kernel would use for the default route; a UDP connect sends no packets. */
private fun defaultRouteIpv4(): String? =
    java.net.DatagramSocket().use { socket ->
        socket.connect(java.net.InetAddress.getByName("1.1.1.1"), 53)
        (socket.localAddress as? java.net.Inet4Address)?.hostAddress
    }

private fun lanIpv4Candidates(): List<Pair<String, String>> =
    java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nic ->
            nic.inetAddresses.toList()
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { addr -> addr.hostAddress?.let { nic.name to it } }
        }

/** Docker, VM, VPN and container bridges are not reachable from the phone on home Wi-Fi. */
private val VIRTUAL_INTERFACE_PREFIXES = listOf(
    "docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "podman", "cni", "flannel",
    "lxcbr", "lxdbr", "tailscale", "tun", "tap", "wg", "zt",
)

/** Picks a private IPv4 on a physical interface, preferring 192.168/16, then 10/8, then 172.16/12. */
internal fun pickLanHostAddress(candidates: List<Pair<String, String>>): String? =
    candidates
        .filter { (name, host) ->
            isPrivateIpv4(host) && VIRTUAL_INTERFACE_PREFIXES.none { name.startsWith(it) }
        }
        .minByOrNull { (_, host) ->
            when {
                host.startsWith("192.168.") -> 0
                host.startsWith("10.") -> 1
                else -> 2
            }
        }
        ?.second

private fun isPrivateIpv4(host: String): Boolean =
    host.startsWith("10.") ||
        host.startsWith("192.168.") ||
        host.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\..*"""))

actual suspend fun <T> withVaultSyncForeground(
    label: String,
    block: suspend () -> T,
): T = block()

actual fun setHomeLanHubAutostart(enabled: Boolean) {
    val home = System.getenv("HOME") ?: return
    val autostart = java.nio.file.Path.of(home, ".config", "autostart")
    val desktop = autostart.resolve("kainos-home-sync-hub.desktop")
    if (!enabled) {
        runCatching { java.nio.file.Files.deleteIfExists(desktop) }
        return
    }
    runCatching {
        java.nio.file.Files.createDirectories(autostart)
        val exec = System.getenv("KAINOS_PLAYER_EXEC")
            ?: listOf(
                "$home/.local/bin/kainos-player",
                "/usr/local/bin/kainos-player",
            ).firstOrNull { java.io.File(it).canExecute() }
            ?: return
        val body = """
            |[Desktop Entry]
            |Type=Application
            |Name=Kainos Home Sync Hub
            |Comment=Listen for phone library sync on the home LAN
            |Exec=$exec --hub-only
            |X-GNOME-Autostart-enabled=true
            |Terminal=false
            """.trimMargin()
        java.nio.file.Files.writeString(desktop, body)
    }
}

actual suspend fun rematchLocalHeartsAfterVaultSync(): Int {
    // Desktop: local hearts use absolute paths; vault sync keeps those paths stable when
    // the vault root is the music folder. Filename rematch is primarily an Android need.
    return 0
}
