package com.universalmusic.player.platform

expect fun createHomeLanSyncHub(
    pairing: com.universalmusic.player.data.sync.HomeLanSyncPairing,
    onHearts: suspend (com.universalmusic.player.data.sync.HeartsSyncDocument) -> com.universalmusic.player.data.sync.HeartsSyncDocument,
    onPlaylists: suspend (com.universalmusic.player.data.playlist.PlaylistsSyncDocument) -> com.universalmusic.player.data.playlist.PlaylistsSyncDocument,
    vault: com.universalmusic.player.data.sync.HomeLanVaultStore?,
    /**
     * When non-null, hub vault index only advertises these basenames (hearted-only mode).
     * Null means advertise the full vault.
     */
    heartedVaultFileNames: () -> Set<String>?,
): com.universalmusic.player.data.sync.HomeLanSyncHub

expect fun createHomeLanVaultStore(
    vaultRootProvider: () -> String?,
): com.universalmusic.player.data.sync.HomeLanVaultStore

/** Best-effort IPv4 LAN address for pairing URI (desktop); null on Android. */

expect fun detectLanHostAddress(): String?

/**
 * Runs [block] under an Android `dataSync` foreground service when needed.
 * Desktop runs [block] directly.
 */

expect suspend fun <T> withVaultSyncForeground(
    label: String,
    block: suspend () -> T,
): T

/** Install or remove a desktop login autostart entry for hub mode. No-op on Android. */

expect fun setHomeLanHubAutostart(enabled: Boolean)

/** Rematch local-file hearts whose paths went missing after vault sync. Returns count. */

expect suspend fun rematchLocalHeartsAfterVaultSync(): Int
