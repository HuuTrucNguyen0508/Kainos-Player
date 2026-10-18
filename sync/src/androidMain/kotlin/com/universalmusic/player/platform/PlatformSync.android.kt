package com.universalmusic.player.platform

actual fun createHomeLanSyncHub(config: HomeLanHubConfig): com.universalmusic.player.data.sync.HomeLanSyncHub =
    com.universalmusic.player.data.sync.NoOpHomeLanSyncHub()

actual fun createHomeLanVaultStore(
    vaultRootProvider: () -> String?,
): com.universalmusic.player.data.sync.HomeLanVaultStore =
    com.universalmusic.player.data.sync.AndroidHomeLanVaultStore(
        context = androidContext,
        vaultRootProvider = vaultRootProvider,
    )

actual fun homeLanHubPort(): Int = com.universalmusic.player.data.sync.HOME_LAN_SYNC_DEFAULT_PORT

actual fun homeLanDeviceName(): String =
    android.provider.Settings.Global.getString(androidContext.contentResolver, "device_name")
        ?.takeIf { it.isNotBlank() }
        ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

actual suspend fun <T> withVaultSyncForeground(
    label: String,
    block: suspend () -> T,
): T = com.universalmusic.player.data.sync.AndroidVaultSyncForeground.run(label, block)

actual fun setHomeLanHubAutostart(enabled: Boolean) = Unit

actual fun homeLanHubAutostartEnabled(): Boolean? = null

actual suspend fun rematchLocalHeartsAfterVaultSync(): Int = 0

actual fun scheduleBackgroundListeningDelivery() =
    com.universalmusic.player.data.sync.ListeningDeliveryWorker.enqueue(androidContext)
