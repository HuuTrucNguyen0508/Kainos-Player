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

    private class FakeSyncController(val hub: DiscoveredHub, val peer: PairedPeer, val emitHub: Boolean = true) : HomeLanSyncController {
        override val status = MutableStateFlow(HomeLanSyncStatus())
        override val trust = MutableStateFlow(HomeLanTrust())
        override val incomingPairing = MutableStateFlow<PairingAttempt?>(null)
        val paired = CompletableDeferred<PairedPeer>()
        val ended = CompletableDeferred<String>()
        var confirmed = false
        var cancelled = 0
        override suspend fun enable(enabled: Boolean) { status.value = status.value.copy(enabled = enabled) }
        override fun discoverHubs() = flow { if (emitHub) emit(hub); awaitCancellation() }
        override suspend fun pairWith(hub: DiscoveredHub) = object : HubPairingSession {
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
        override suspend fun startHubIfNeeded() = Unit
        override suspend fun stopHub() = Unit
        override suspend fun tombstoneVaultPath(relPath: String) = Result.success("Removed")
        override suspend fun resolveConflictKeepLocal(relPath: String) = Result.success("Kept local")
        override suspend fun resolveConflictKeepRemote(relPath: String) = Result.success("Kept remote")
    }
}
