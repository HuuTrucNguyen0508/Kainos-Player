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

actual fun detectLanHostAddress(): String? {
    return runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (nic in interfaces) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("10.") ||
                        host.startsWith("192.168.") ||
                        host.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\..*"""))
                    ) {
                        return host
                    }
                }
            }
        }
        null
    }.getOrNull()
}

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
