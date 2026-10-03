package com.universalmusic.player.platform

actual fun createHomeLanSyncHub(
    pairing: com.universalmusic.player.data.sync.HomeLanSyncPairing,
    onHearts: suspend (com.universalmusic.player.data.sync.HeartsSyncDocument) -> com.universalmusic.player.data.sync.HeartsSyncDocument,
    onPlaylists: suspend (com.universalmusic.player.data.playlist.PlaylistsSyncDocument) -> com.universalmusic.player.data.playlist.PlaylistsSyncDocument,
    vault: com.universalmusic.player.data.sync.HomeLanVaultStore?,
    heartedVaultFileNames: () -> Set<String>?,
): com.universalmusic.player.data.sync.HomeLanSyncHub =
    com.universalmusic.player.data.sync.NoOpHomeLanSyncHub()

actual fun createHomeLanVaultStore(
    vaultRootProvider: () -> String?,
): com.universalmusic.player.data.sync.HomeLanVaultStore =
    com.universalmusic.player.data.sync.AndroidHomeLanVaultStore(
        context = androidContext,
        vaultRootProvider = vaultRootProvider,
    )

actual fun detectLanHostAddress(): String? = null

actual suspend fun <T> withVaultSyncForeground(
    label: String,
    block: suspend () -> T,
): T = com.universalmusic.player.data.sync.AndroidVaultSyncForeground.run(label, block)

actual fun setHomeLanHubAutostart(enabled: Boolean) = Unit

actual suspend fun rematchLocalHeartsAfterVaultSync(): Int = 0
