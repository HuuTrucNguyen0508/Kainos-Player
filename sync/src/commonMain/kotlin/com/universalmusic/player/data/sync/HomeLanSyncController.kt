package com.universalmusic.player.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Who this device trusts. A client stores one [hub]; a hub stores its paired [peers]. */
data class HomeLanTrust(
    val hub: PairedPeer? = null,
    val peers: List<PairedPeer> = emptyList(),
    /** An old shared-secret pairing was dropped and the user has not paired again yet. */
    val repairNeeded: Boolean = false,
)

/** One phone-side pairing after both sides derived the code; it ends in [confirm] or [cancel]. */
interface HubPairingSession {
    val attemptId: String
    val hub: DiscoveredHub
    val code: PairingCode

    /** Sends this device's confirmation, waits for the PC's, and stores the hub. */
    suspend fun confirm(): PairedPeer

    suspend fun cancel()

    suspend fun awaitEnd(): String
}

interface HomeLanSyncController {
    val status: StateFlow<HomeLanSyncStatus>
    val trust: StateFlow<HomeLanTrust>
    /** The phone asking this PC to pair right now, if any. */
    val incomingPairing: StateFlow<PairingAttempt?>
    suspend fun enable(enabled: Boolean)
    /** Hubs advertised on the LAN, as they resolve, until the collector stops. */
    fun discoverHubs(): Flow<DiscoveredHub>
    /** Starts pairing with [hub] and returns once both sides can show the code. */
    suspend fun pairWith(hub: DiscoveredHub): HubPairingSession
    /** The [hubPairingLink] a phone can scan to reach this PC's hub, or null when the hub is not listening. */
    fun pairingLink(): String?
    suspend fun confirmIncomingPairing(attemptId: String): Result<Unit>
    suspend fun rejectIncomingPairing(attemptId: String): Result<Unit>
    /** Forgets one paired device; on a client, that is its hub. */
    suspend fun removeDevice(deviceId: String)
    /** Creates a new key for this device and forgets every pairing, so each side pairs again. */
    suspend fun resetIdentity()
    suspend fun syncNow(): Result<String>
    /** Copies the pending vault and cached audio files [only] names, or every pending one when null. */
    suspend fun confirmVaultTransfers(only: Set<PendingCopyKey>? = null): Result<String>
    /** Skips the pending copies [only] names, or every pending one when null, until the file changes. */
    suspend fun dismissVaultTransfers(only: Set<PendingCopyKey>? = null): Result<String>
    /** Offers a skipped copy again: now when the last sync planned it, otherwise at the next sync. */
    suspend fun unskipCopy(kind: SkippedCopyKind, subject: String, direction: VaultCopyDirection): Result<String> =
        Result.failure(UnsupportedOperationException("Un-skip is unavailable"))
    suspend fun startHubIfNeeded()
    suspend fun stopHub()
    suspend fun tombstoneVaultPath(relPath: String): Result<String>
    suspend fun resolveConflictKeepLocal(relPath: String): Result<String>
    suspend fun resolveConflictKeepRemote(relPath: String): Result<String>
}
