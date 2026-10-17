package com.universalmusic.player.platform

import io.ktor.serialization.kotlinx.json.json
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

actual fun createHomeLanSyncHub(config: HomeLanHubConfig): com.universalmusic.player.data.sync.HomeLanSyncHub {
    val identity = jvmPlatformDeviceIdentity()
    val hub = com.universalmusic.player.data.sync.DesktopHomeLanSyncHub(config, identity.material())
    val advertiser = com.universalmusic.player.data.sync.AvahiHubAdvertiser(
        port = config.port,
        certSha256Hex = identity.fingerprint().hex,
        deviceId = config.deviceId,
        instanceLabel = DesktopIsolation.current.instance,
    )
    return AdvertisedHub(hub, advertiser)
}

private class AdvertisedHub(
    private val hub: com.universalmusic.player.data.sync.HomeLanSyncHub,
    private val advertiser: com.universalmusic.player.data.sync.AvahiHubAdvertiser,
) : com.universalmusic.player.data.sync.HomeLanSyncHub by hub {
    override suspend fun start() {
        hub.start()
        advertiser.start()
    }

    override suspend fun stop() {
        advertiser.stop()
        hub.stop()
    }
}

actual fun createHomeLanVaultStore(
    vaultRootProvider: () -> String?,
): com.universalmusic.player.data.sync.HomeLanVaultStore =
    com.universalmusic.player.data.sync.JvmHomeLanVaultStore(
        vaultRootProvider = vaultRootProvider,
        statePath = homeLanConfigDir().resolve("vault-sync-state.json"),
    )

actual fun homeLanHubPort(): Int = DesktopIsolation.current.hubPort

actual fun homeLanDeviceName(): String =
    runCatching { java.io.File("/etc/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "Linux PC"

actual suspend fun <T> withVaultSyncForeground(
    label: String,
    block: suspend () -> T,
): T = block()

actual fun setHomeLanHubAutostart(enabled: Boolean) =
    setHomeLanHubAutostart(enabled, DesktopIsolation.current, System.getenv(), ::println)

internal fun setHomeLanHubAutostart(
    enabled: Boolean,
    isolation: DesktopIsolation,
    env: Map<String, String>,
    log: (String) -> Unit,
) {
    if (isolation.isIsolated) {
        log("Hub autostart unchanged: isolated instance")
        return
    }
    val home = env["HOME"] ?: return
    val autostart = java.nio.file.Path.of(home, ".config", "autostart")
    val desktop = autostart.resolve("kainos-home-sync-hub.desktop")
    if (!enabled) {
        runCatching { java.nio.file.Files.deleteIfExists(desktop) }
        return
    }
    runCatching {
        java.nio.file.Files.createDirectories(autostart)
        val exec = env["KAINOS_PLAYER_EXEC"]
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
