package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.sync.CertFingerprint
import com.universalmusic.player.data.sync.DiscoveredHub
import com.universalmusic.player.data.sync.HomeLanSyncController
import com.universalmusic.player.data.sync.HomeLanSyncStatus
import com.universalmusic.player.data.sync.HomeLanTrust
import com.universalmusic.player.data.sync.HubPairingSession
import com.universalmusic.player.data.sync.PairedPeer
import com.universalmusic.player.data.sync.PairingAttempt
import com.universalmusic.player.data.sync.PairingCode
import com.universalmusic.player.data.sync.VaultCopyDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSyncPresenterTest {
    private val hub = DiscoveredHub("11".repeat(32), "pc", "192.0.2.1", 43823, "Kainos on studio")
    private val peer = PairedPeer("pc", "Kainos on studio", CertFingerprint.parse("11".repeat(32))!!,
        "192.0.2.1", 43823, 123L)

    @Test
    fun searchingToPairedRequiresConfirmationAndPcResult() = runTest {
        val service = FakeSyncController(hub, peer)
        val presenter = SettingsSyncPresenter(service, backgroundScope)
        presenter.search()
        runCurrent()
        assertEquals(PairingUiState.Found(listOf(hub)), presenter.state.value.pairing)
        presenter.pair(hub)
        runCurrent()
        assertEquals(PairingUiState.Confirming("attempt-1", hub, PairingCode("012345")), presenter.state.value.pairing)
        assertFalse(service.confirmed)
        presenter.confirm()
        runCurrent()
        assertEquals(PairingUiState.WaitingForPc("attempt-1", hub, PairingCode("012345")), presenter.state.value.pairing)
        assertEquals(true, service.confirmed)
        service.paired.complete(peer)
        runCurrent()
        assertEquals(PairingUiState.Paired(peer), presenter.state.value.pairing)
    }

    @Test
    fun confirmingToFailedOnCancelEndsTheSession() = runTest {
        val service = FakeSyncController(hub, peer)
        val presenter = SettingsSyncPresenter(service, backgroundScope)
        presenter.search()
        runCurrent()
        presenter.pair(hub)
        runCurrent()
        assertEquals(PairingUiState.Confirming("attempt-1", hub, PairingCode("012345")), presenter.state.value.pairing)
        presenter.cancelPairing()
        runCurrent()
        assertEquals(PairingUiState.Failed("Pairing cancelled. Start again when you are ready."), presenter.state.value.pairing)
        assertEquals(1, service.cancelled)
        assertFalse(service.confirmed)
    }

    @Test
    fun pcRejectionIsShownBeforePhoneConfirmation() = runTest {
        val service = FakeSyncController(hub, peer)
        val presenter = SettingsSyncPresenter(service, backgroundScope)
        presenter.search()
        runCurrent()
        presenter.pair(hub)
        runCurrent()
        service.ended.complete("The PC rejected the pairing")
        runCurrent()
        assertEquals(PairingUiState.Failed("The PC rejected the pairing"), presenter.state.value.pairing)
        assertEquals(1, service.cancelled)
        assertFalse(service.confirmed)
    }

    @Test
    fun noDiscoveredPcGivesAnActionableFailure() = runTest {
        val service = FakeSyncController(hub, peer, emitHub = false)
        val presenter = SettingsSyncPresenter(service, backgroundScope, discoveryTimeoutMs = 100)
        presenter.search()
        runCurrent()
        assertEquals(PairingUiState.Searching, presenter.state.value.pairing)
        testScheduler.advanceTimeBy(101)
        runCurrent()
        assertEquals(PairingUiState.Failed("No PCs found. Open Kainos on your PC and enable home sync on the same Wi-Fi."), presenter.state.value.pairing)
    }

    @Test
    fun leavingSettingsCancelsThePendingSession() = runTest {
        val service = FakeSyncController(hub, peer)
        val presenter = SettingsSyncPresenter(service, backgroundScope)
        presenter.search()
        runCurrent()
        presenter.pair(hub)
        runCurrent()
        presenter.dispose()
        runCurrent()
        assertEquals(PairingUiState.Idle, presenter.state.value.pairing)
        assertEquals(1, service.cancelled)
        assertFalse(service.confirmed)
    }

    @Test
    fun aScannedCodeListsItsPcAndPairsOnlyAfterTheTapAndCodeCompare() = runTest {
        val scannedHub = DiscoveredHub("ab".repeat(32), "pc-7f3a", "192.168.1.20", 43822, "Kainos on studio")
        val service = FakeSyncController(scannedHub, peer)
        val presenter = SettingsSyncPresenter(service, backgroundScope)
        presenter.scanned("kainos-pair:3?fp=${"ab".repeat(32)}&id=pc-7f3a&host=192.168.1.20&port=43822&name=Kainos%20on%20studio")
        runCurrent()
        assertEquals(PairingUiState.Found(listOf(scannedHub), scanned = true), presenter.state.value.pairing)
        assertEquals(emptyList(), service.pairedWith)
        presenter.pair(scannedHub)
        runCurrent()
        assertEquals(listOf(scannedHub), service.pairedWith)
        assertEquals(PairingUiState.Confirming("attempt-1", scannedHub, PairingCode("012345")), presenter.state.value.pairing)
        assertFalse(service.confirmed)
    }

    @Test
    fun aScannedCodeThatIsNotAPairingLinkSaysWhy() = runTest {
        val service = FakeSyncController(hub, peer)
        val presenter = SettingsSyncPresenter(service, backgroundScope)
        presenter.scanned("kainos-homesync:2?host=192.168.1.20&port=43822&cert=${"ab".repeat(32)}")
        runCurrent()
        assertEquals(
            PairingUiState.Failed("This is an old pairing link. Open Devices and sync on your PC and scan the code shown there."),
            presenter.state.value.pairing,
        )
        presenter.scanned("https://example.com/menu")
        runCurrent()
        assertEquals(PairingUiState.Failed("This is not a Kainos pairing code."), presenter.state.value.pairing)
        presenter.scanUnavailable("Scanning needs the camera.")
        runCurrent()
        assertEquals(PairingUiState.Failed("Scanning needs the camera."), presenter.state.value.pairing)
        assertEquals(emptyList(), service.pairedWith)
    }

    @Test
    fun thePcShowsItsPairingLinkWhileItsHubListensAndFollowsANewAddress() = runTest {
        val service = FakeSyncController(hub, peer)
        val presenter = SettingsSyncPresenter(service, backgroundScope, pairingLinkRefreshMs = 1_000)
        backgroundScope.launch { presenter.pairingLink.collect {} }
        runCurrent()
        assertEquals(null, presenter.pairingLink.value)
        service.status.value = service.status.value.copy(hubListening = true)
        runCurrent()
        assertEquals("kainos-pair:3?host=192.168.1.20", presenter.pairingLink.value)
        service.address = "192.168.1.31"
        testScheduler.advanceTimeBy(1_001)
        runCurrent()
        assertEquals("kainos-pair:3?host=192.168.1.31", presenter.pairingLink.value)
        service.status.value = service.status.value.copy(hubListening = false)
        runCurrent()
        assertEquals(null, presenter.pairingLink.value)
    }

    private class FakeSyncController(val hub: DiscoveredHub, val peer: PairedPeer, val emitHub: Boolean = true) : HomeLanSyncController {
        override val status = MutableStateFlow(HomeLanSyncStatus())
        override val trust = MutableStateFlow(HomeLanTrust())
        override val incomingPairing = MutableStateFlow<PairingAttempt?>(null)
        val paired = CompletableDeferred<PairedPeer>()
        val ended = CompletableDeferred<String>()
        var confirmed = false
        var cancelled = 0
        val pairedWith = mutableListOf<DiscoveredHub>()
        var address = "192.168.1.20"
        override fun pairingLink() = "kainos-pair:3?host=$address".takeIf { status.value.hubListening }
        override suspend fun enable(enabled: Boolean) { status.value = status.value.copy(enabled = enabled) }
        override fun discoverHubs() = flow { if (emitHub) emit(hub); awaitCancellation() }
        override suspend fun pairWith(hub: DiscoveredHub) = object : HubPairingSession {
            init { pairedWith += hub }
            override val attemptId = "attempt-1"
            override val hub = this@FakeSyncController.hub
            override val code = PairingCode("012345")
            override suspend fun confirm(): PairedPeer { confirmed = true; return paired.await() }
            override suspend fun cancel() { cancelled++ }
            override suspend fun awaitEnd() = ended.await()
        }
        override suspend fun confirmIncomingPairing(attemptId: String) = Result.success(Unit)
        override suspend fun rejectIncomingPairing(attemptId: String) = Result.success(Unit)
        override suspend fun removeDevice(deviceId: String) = Unit
        override suspend fun resetIdentity() = Unit
        override suspend fun syncNow() = Result.success("Synced")
        override suspend fun confirmVaultTransfer(relPath: String, direction: VaultCopyDirection) = Result.success("Transferred")
        override suspend fun dismissVaultTransfer(relPath: String, direction: VaultCopyDirection) = Result.success("Skipped")
        override suspend fun confirmCachedAudioTransfer(ownerCanonicalId: String, direction: VaultCopyDirection) = Result.success("Copied")
        override suspend fun dismissCachedAudioTransfer(ownerCanonicalId: String, direction: VaultCopyDirection) = Result.success("Skipped")
        override suspend fun confirmAllVaultTransfers() = Result.success("Transferred all")
        override suspend fun dismissAllVaultTransfers() = Result.success("Skipped all")
        override suspend fun startHubIfNeeded() = Unit
        override suspend fun stopHub() = Unit
        override suspend fun tombstoneVaultPath(relPath: String) = Result.success("Removed")
        override suspend fun resolveConflictKeepLocal(relPath: String) = Result.success("Kept local")
        override suspend fun resolveConflictKeepRemote(relPath: String) = Result.success("Kept remote")
    }
}
