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

actual suspend fun scheduleBackgroundListeningDelivery() =
    com.universalmusic.player.data.sync.ListeningDeliveryWorker.enqueue(androidContext)

actual suspend fun readLocalArtworkBytes(location: String, maxBytes: Int): ByteArray? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val uri = if (location.startsWith("/")) android.net.Uri.fromFile(java.io.File(location)) else android.net.Uri.parse(location)
            androidContext.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > maxBytes) return@use null
                }
                buffer.toByteArray()
            }
        }.getOrNull()
    }

actual suspend fun cacheRemoteArtwork(key: String, bytes: ByteArray): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            if (!key.matches(Regex("[0-9a-f]{8,64}"))) return@runCatching null
            val dir = java.io.File(androidContext.cacheDir, "remote-art").apply { mkdirs() }
            val target = java.io.File(dir, "$key.img")
            if (!target.isFile || target.length() != bytes.size.toLong()) {
                val tmp = java.io.File(dir, "$key.img.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            dir.listFiles { file -> file != target && file.name.endsWith(".img") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(8)
                ?.forEach { it.delete() }
            target.toURI().toASCIIString()
        }.getOrNull()
    }
