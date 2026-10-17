package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.sync.HomeLanSyncController
import com.universalmusic.player.data.sync.HomeLanSyncPairing
import com.universalmusic.player.data.sync.HomeLanSyncStatus
import com.universalmusic.player.data.sync.VaultCopyDirection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSyncPresenterTest {
    @Test
    fun manualPairingUsesTheRemoteDefaultInsteadOfTheLocalListenPort() = runTest {
        val controller = RecordingPairingController()
        val presenter = SettingsSyncPresenter(controller, backgroundScope)
        presenter.pairManually(" 192.168.1.2 ", " secret ", " peer ", " pin ", " cert ")
        runCurrent()
        assertEquals(43822, controller.remotePort)
        assertEquals(listOf("192.168.1.2", "secret", "peer", "pin", "cert"), controller.fields)
    }
}

private class RecordingPairingController : HomeLanSyncController {
    override val status = MutableStateFlow(HomeLanSyncStatus())
    override val pairing = MutableStateFlow<HomeLanSyncPairing?>(
        HomeLanSyncPairing(deviceId = "local", sharedSecretHex = "local-secret", hubPort = 44005))
    var remotePort: Int? = null
    var fields: List<String>? = null
    override suspend fun completeClientPairing(hubHost: String, hubPort: Int, sharedSecretHex: String,
        peerDeviceId: String, pairingPin: String, hubCertSha256Hex: String) {
        remotePort = hubPort
        fields = listOf(hubHost, sharedSecretHex, peerDeviceId, pairingPin, hubCertSha256Hex)
    }
    override fun pairingUri(): String? = null
    override suspend fun enable(enabled: Boolean) = error("unused")
    override suspend fun beginHubPairing(): HomeLanSyncPairing = error("unused")
    override suspend fun completeClientPairingFromUri(uri: String) = error("unused")
    override suspend fun unpair() = error("unused")
    override suspend fun syncNow(): Result<String> = error("unused")
    override suspend fun confirmVaultTransfer(relPath: String, direction: VaultCopyDirection): Result<String> = error("unused")
    override suspend fun dismissVaultTransfer(relPath: String, direction: VaultCopyDirection): Result<String> = error("unused")
    override suspend fun startHubIfNeeded() = error("unused")
    override suspend fun stopHub() = error("unused")
    override suspend fun tombstoneVaultPath(relPath: String): Result<String> = error("unused")
    override suspend fun resolveConflictKeepLocal(relPath: String): Result<String> = error("unused")
    override suspend fun resolveConflictKeepRemote(relPath: String): Result<String> = error("unused")
}
