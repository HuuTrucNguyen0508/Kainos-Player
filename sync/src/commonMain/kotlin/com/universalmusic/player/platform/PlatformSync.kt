package com.universalmusic.player.platform

import com.universalmusic.player.data.playlist.PlaylistsSyncDocument
import com.universalmusic.player.data.sync.HeartsSyncDocument
import com.universalmusic.player.data.sync.HomeLanSyncHub
import com.universalmusic.player.data.sync.HomeLanVaultStore
import com.universalmusic.player.data.sync.PairedPeer
import com.universalmusic.player.data.sync.PairingHost

class HomeLanHubConfig(
    val deviceId: String,
    val port: Int,
    /** Read per request, so removing a device takes effect without a restart. */
    val peers: () -> List<PairedPeer>,
    val pairing: PairingHost,
    val onHearts: suspend (HeartsSyncDocument) -> HeartsSyncDocument,
    val onPlaylists: suspend (PlaylistsSyncDocument) -> PlaylistsSyncDocument,
    val vault: HomeLanVaultStore?,
    /** When non-null, the hub vault index only advertises these basenames (hearted-only mode). */
    val heartedVaultFileNames: () -> Set<String>?,
)

expect fun createHomeLanSyncHub(config: HomeLanHubConfig): HomeLanSyncHub

expect fun createHomeLanVaultStore(
    vaultRootProvider: () -> String?,
): com.universalmusic.player.data.sync.HomeLanVaultStore

/** Port this device's hub listens on: `KAINOS_HUB_PORT` on desktop, the default on Android. */

expect fun homeLanHubPort(): Int

/** Name this device shows the other side while pairing. */
expect fun homeLanDeviceName(): String

/**
 * Runs [block] under an Android `dataSync` foreground service when needed.
 * Desktop runs [block] directly.
 */

expect suspend fun <T> withVaultSyncForeground(
    label: String,
    block: suspend () -> T,
): T

/** Configure desktop hub login startup. Throws when configuration fails. No-op on Android. */

expect fun setHomeLanHubAutostart(enabled: Boolean)

expect fun homeLanHubAutostartEnabled(): Boolean

/** Rematch local-file hearts whose paths went missing after vault sync. Returns count. */

expect suspend fun rematchLocalHeartsAfterVaultSync(): Int
