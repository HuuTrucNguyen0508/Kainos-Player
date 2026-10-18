package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.platform.DeviceIdentity
import com.universalmusic.player.platform.DeviceIdentityResetException
import com.universalmusic.player.platform.HUB_PIN_MISMATCH
import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.homeLanHubAutostartEnabled
import com.universalmusic.player.platform.setHomeLanHubAutostart
import com.universalmusic.player.platform.homeLanDeviceName
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.scheduleBackgroundListeningDelivery
import com.universalmusic.player.platform.secureRandomBytes
import com.universalmusic.player.platform.withVaultSyncForeground
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

const val HOME_LAN_REPAIR_NOTICE = "Pair again to keep syncing"
const val HOME_LAN_IDENTITY_CHANGED = "This PC's identity changed. Pair again."
const val HOME_LAN_NOT_TRUSTED = "This PC no longer trusts this device. Pair again."
const val HOME_LAN_RESET_PENDING = "Identity reset is incomplete. Home sync is stopped and existing pairings are blocked. Retry Reset identity."

/**
 * Coordinates home-LAN hearts + playlist metadata + vault sync.
 * Platform wires hub/client/vault factories. Playlist sync never auto-copies audio.
 */
class HomeLanSyncService(
    private val scope: CoroutineScope,
    private val settings: () -> AppSettings,
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val library: LibraryRepository,
    private val playlists: KainosPlaylistRepository,
    private val hubFactory: (HomeLanHubConfig) -> HomeLanSyncHub,
    private val clientFactory: (PairedPeer) -> HomeLanSyncClient,
    private val pairingClientFactory: (HubEndpoint, CertFingerprint) -> HomeLanPairingClient,
    private val identity: DeviceIdentity,
    private val vaultStore: HomeLanVaultStore,
    private val refreshLocalLibrary: suspend () -> Unit,
    private val rematchLocalHearts: suspend () -> Int = { 0 },
    private val rematchPlaylistLocals: suspend () -> Int = { 0 },
    private val clock: () -> Long = ::currentTimeMillis,
    private val isDesktop: Boolean = platformLabel() == "Linux",
    /** The port this device's own hub listens on; a remote hub's port lives in its [PairedPeer]. */
    private val hubPort: Int = HOME_LAN_SYNC_DEFAULT_PORT,
    private val hubBrowser: HubBrowser = platformHubBrowser(),
    private val deviceName: () -> String = ::homeLanDeviceName,
    private val pairingPollMs: Long = 1_000,
    private val configureHubAutostart: (Boolean) -> Unit = ::setHomeLanHubAutostart,
    private val hubAutostartEnabled: () -> Boolean? = ::homeLanHubAutostartEnabled,
    /** This device's listening rows; null turns the listening exchange off in both directions. */
    private val listening: ListeningExchangeStore? = null,
    private val openListeningHub: suspend (PairedPeer) -> ListeningHubClient = ::openPairedListeningHub,
    private val retryListeningInProcess: () -> Boolean = { true },
    private val scheduleListeningDelivery: suspend () -> Unit = ::scheduleBackgroundListeningDelivery,
    private val isAppForeground: () -> Boolean = { true },
) : HomeLanSyncController {
    private val _status = MutableStateFlow(HomeLanSyncStatus())
    override val status: StateFlow<HomeLanSyncStatus> = _status.asStateFlow()

    private val _trust = MutableStateFlow(HomeLanTrust())
    override val trust: StateFlow<HomeLanTrust> = _trust.asStateFlow()

    private val pairingHost = PairingHost(
        hubFingerprint = identity::fingerprint,
        hubDeviceId = { settings().homeLanSyncDeviceId.orEmpty() },
        onPaired = ::trustPeer,
        clock = clock,
        isAvailable = { !resetBlocked() },
    )
    override val incomingPairing: StateFlow<PairingAttempt?> = pairingHost.incoming

    private var hub: HomeLanSyncHub? = null
    private var hubStopGeneration = 0L
    private val mutex = Mutex()
    private val identityResetMutex = Mutex()
    private val pairingAttemptGeneration = MutableStateFlow(0L)
    private val autostartMutex = Mutex()
    private var pairingExpiryJob: Job? = null

    private val listeningDelivery = listening?.let { store ->
        ListeningDelivery(
            store = store,
            target = { if (resetBlocked()) null else settings().listeningDeliveryTarget(isDesktop, identity.fingerprint()) },
            connect = openListeningHub,
            clock = clock,
        )
    }
    private val listeningRunner = listeningDelivery?.let {
        ListeningDeliveryRunner(scope, it, retryListeningInProcess, scheduleListeningDelivery, isAppForeground = isAppForeground)
    }

    private val resolutionLock = Any()
    private var resolutionGeneration = 0L

    private data class ResolutionSource(
        val target: PairedPeer,
        val generation: Long,
        val role: HomeLanSyncRole,
    )

    private fun resolutionSource(target: PairedPeer) = synchronized(resolutionLock) {
        ResolutionSource(target, resolutionGeneration, _status.value.role)
    }

    private fun isCurrent(source: ResolutionSource): Boolean = synchronized(resolutionLock) {
        !resetBlocked() && resolutionGeneration == source.generation &&
            _status.value.enabled && _status.value.role == source.role &&
            (if (shouldRunHub(source.role)) syncTarget() else _trust.value.hub) == source.target
    }

    private fun invalidateResolution() = synchronized(resolutionLock) {
        resolutionGeneration++
    }

    fun hydrateFromSettings(current: AppSettings) = synchronized(resolutionLock) {
        invalidateResolution()
        _trust.value = if (resetBlocked()) HomeLanTrust() else current.trust()
        _status.update {
            it.copy(
                enabled = current.homeLanSyncEnabled,
                role = current.homeLanSyncRole,
                lastSyncAtMs = current.homeLanSyncLastAtMs,
                lastError = if (current.homeLanSyncIdentityResetPending) HOME_LAN_RESET_PENDING else current.homeLanSyncLastError,
                lastDetail = current.homeLanSyncLastDetail,
            )
        }
    }

    suspend fun setHubAutostartEnabled(enabled: Boolean): Result<Unit> = autostartMutex.withLock {
        val configured = try {
            configureHubAutostart(enabled)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        val observed = try {
            Result.success(hubAutostartEnabled())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        val failure = configured.exceptionOrNull() ?: observed.exceptionOrNull()
            ?: observed.getOrNull()?.takeIf { it != enabled }?.let {
                IllegalStateException("Start at login did not change. Try again.")
            }
        val message = failure?.let { "Could not update hub login startup: ${it.message ?: it.toString()}" }
        observed.getOrNull()?.let { actual ->
            if (settings().homeLanSyncHubAutostart != actual) {
                updateSettings { it.copy(homeLanSyncHubAutostart = actual) }
            }
        }
        _status.update { it.copy(hubAutostartError = message) }
        failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun enable(enabled: Boolean) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        invalidateResolution()
        withContext(scope.coroutineContext.job) {
            mutex.withLock {
                caller.ensureActive()
                updateSettings { it.copy(homeLanSyncEnabled = enabled) }
                _status.update { it.copy(enabled = enabled) }
                if (!enabled) stopHubLocked()
            }
            if (enabled) startHubIfNeeded()
        }
        caller.ensureActive()
    }

    override fun discoverHubs(): Flow<DiscoveredHub> {
        val own = identity.fingerprint()
        return hubBrowser.browse().filter { CertFingerprint.parse(it.fp).let { fp -> fp != null && fp != own } }
    }

    override suspend fun pairWith(hub: DiscoveredHub): HubPairingSession {
        val generation = mutex.withLock {
            check(!resetBlocked()) { HOME_LAN_RESET_PENDING }
            pairingAttemptGeneration.value
        }
        val pin = CertFingerprint.parse(hub.fp) ?: error("${hub.name} advertised no valid certificate fingerprint")
        val own = identity.fingerprint()
        val deviceId = ensureDeviceId()
        val client = pairingClientFactory(hub.endpoint, pin)
        var attemptId: String? = null
        try {
            val nonce = secureRandomBytes(PAIRING_NONCE_BYTES)
            val started = client.start(PairStartRequest(deviceId, deviceName(), pairingCommit(nonce).toHex()))
            attemptId = started.attemptId
            val hubNonce = started.nonceHex.hexBytes(PAIRING_NONCE_BYTES) ?: error("${hub.name} sent a malformed nonce")
            client.reveal(PairRevealRequest(started.attemptId, nonce.toHex()))
            PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "pairing code ready for ${hub.endpoint}")
            return PhonePairing(started.attemptId, hub, pairingCode(pin, own, hubNonce, nonce), pin, client, generation)
        } catch (err: Throwable) {
            withContext(NonCancellable) {
                withTimeoutOrNull(3_000) { attemptId?.let { runCatching { client.cancel(it) } } }
            }
            client.close()
            throw err
        }
    }

    private inner class PhonePairing(
        override val attemptId: String,
        override val hub: DiscoveredHub,
        override val code: PairingCode,
        private val pin: CertFingerprint,
        private val client: HomeLanPairingClient,
        private val generation: Long,
    ) : HubPairingSession {
        override suspend fun confirm(): PairedPeer = try {
            checkCurrentIdentity()
            client.confirm(attemptId)
            val peer = PairedPeer(
                deviceId = awaitPaired(),
                displayName = hub.name,
                fingerprint = pin,
                lastKnownHost = hub.host,
                lastKnownPort = hub.port,
                pairedAtMs = clock(),
            )
            trustHub(peer, generation)
            peer
        } catch (failure: Throwable) {
            cancel()
            throw failure
        } finally {
            client.close()
        }

        override suspend fun cancel() {
            withContext(NonCancellable) {
                withTimeoutOrNull(3_000) { runCatching { client.cancel(attemptId) } }
            }
            client.close()
        }

        override suspend fun awaitEnd(): String {
            while (true) {
                checkCurrentIdentity()
                val status = client.status(attemptId)
                if (status.state == PairState.ENDED) return status.reason ?: "The pairing ended"
                delay(pairingPollMs)
            }
        }

        private fun checkCurrentIdentity() {
            check(!resetBlocked() && generation == pairingAttemptGeneration.value) { "Identity changed during pairing. Pair again." }
        }

        private suspend fun awaitPaired(): String {
            repeat((PAIRING_ATTEMPT_TTL_MS / pairingPollMs).toInt() + 5) {
                checkCurrentIdentity()
                val status = client.status(attemptId)
                when (status.state) {
                    PairState.PAIRED -> return status.hubDeviceId ?: hub.id ?: error("${hub.name} sent no device id")
                    PairState.ENDED -> error(status.reason ?: "The pairing ended")
                    PairState.PENDING -> delay(pairingPollMs)
                }
            }
            error("The PC did not confirm in time")
        }
    }

    override suspend fun confirmIncomingPairing(attemptId: String): Result<Unit> =
        pairingHost.confirmFromPc(attemptId).toResult()

    override suspend fun rejectIncomingPairing(attemptId: String): Result<Unit> =
        pairingHost.rejectFromPc(attemptId).toResult()

    private suspend fun trustHub(peer: PairedPeer, generation: Long) = mutex.withLock {
        check(!resetBlocked() && generation == pairingAttemptGeneration.value) { "Identity changed during pairing. Pair again." }
        invalidateResolution()
        updateSettings {
            it.copy(
                homeLanSyncEnabled = true,
                homeLanSyncRole = HomeLanSyncRole.CLIENT,
                homeLanSyncHub = peer,
                homeLanSyncRepairNeeded = false,
                homeLanSyncLastError = null,
            )
        }
        listening?.setRefusedPairing(null)
        reconcileTrust()
        _status.update {
            it.copy(enabled = true, role = HomeLanSyncRole.CLIENT, lastError = null, lastDetail = "Paired with ${peer.displayName}")
        }
        stopHubLocked()
    }

    private suspend fun trustPeer(peer: PairedPeer) {
        val generation = pairingAttemptGeneration.value
        mutex.withLock {
            check(!resetBlocked() && generation == pairingAttemptGeneration.value) { "Identity changed during pairing. Pair again." }
            updateSettings {
                it.copy(
                    homeLanSyncPeers = it.homeLanSyncPeers.filterNot { known ->
                        known.deviceId == peer.deviceId || known.fingerprint == peer.fingerprint
                    } + peer,
                    homeLanSyncRepairNeeded = false,
                )
            }
            reconcileTrust()
            _status.update { it.copy(lastError = null, lastDetail = "Paired with ${peer.displayName}") }
        }
    }

    override suspend fun removeDevice(deviceId: String) {
        invalidateResolution()
        updateSettings {
            it.copy(
                homeLanSyncHub = it.homeLanSyncHub?.takeUnless { hub -> hub.deviceId == deviceId },
                homeLanSyncPeers = it.homeLanSyncPeers.filterNot { peer -> peer.deviceId == deviceId },
            )
        }
        reconcileTrust()
    }

    private enum class ResetPhase { NOT_STARTED, TRUST_BLOCKED, ROTATED, PRESERVED, RESTORED }

    override suspend fun resetIdentity() {
        var restartGeneration: Long? = null
        var error: Exception? = null
        try {
            identityResetMutex.withLock {
                var wasListening = false
                val resuming = settings().homeLanSyncIdentityResetPending
                var phase = if (resuming) ResetPhase.TRUST_BLOCKED else ResetPhase.NOT_STARTED
                try {
                    mutex.withLock {
                        pairingAttemptGeneration.update { it + 1 }
                        wasListening = hub?.isListening == true
                        stopHubLocked()
                        restartGeneration = hubStopGeneration
                        updateSettings { it.copy(homeLanSyncIdentityResetPending = true) }
                        phase = ResetPhase.TRUST_BLOCKED
                        reconcileTrust()
                    }
                    pairingHost.abandon("This device's identity was reset")
                    try {
                        identity.reset()
                        phase = ResetPhase.ROTATED
                    } catch (failure: Exception) {
                        if (!resuming && (failure as? DeviceIdentityResetException)?.preservedFingerprint != null) {
                            phase = ResetPhase.PRESERVED
                            mutex.withLock {
                                updateSettings { it.copy(homeLanSyncIdentityResetPending = false) }
                                reconcileTrust()
                            }
                            phase = ResetPhase.RESTORED
                        }
                        throw failure
                    }
                    mutex.withLock {
                        updateSettings {
                            it.copy(homeLanSyncHub = null, homeLanSyncPeers = emptyList(),
                                homeLanSyncIdentityResetPending = false, homeLanSyncLastError = null)
                        }
                        listening?.setRefusedPairing(null)
                        reconcileTrust()
                        _status.update { it.copy(lastError = null, lastDetail = "Created a new identity for this device") }
                    }
                } catch (cancelled: CancellationException) {
                    reconcileTrust()
                    if (settings().homeLanSyncIdentityResetPending) _status.update { it.copy(lastError = HOME_LAN_RESET_PENDING) }
                    throw cancelled
                } catch (failure: Exception) {
                    val unchanged = phase == ResetPhase.NOT_STARTED || phase == ResetPhase.RESTORED
                    if (!wasListening || !unchanged) restartGeneration = null
                    val message = if (unchanged) {
                        "Could not reset this device's identity. Existing pairings remain."
                    } else if (phase == ResetPhase.ROTATED) {
                        "A new identity was created, but pairing cleanup could not be saved. $HOME_LAN_RESET_PENDING"
                    } else if (phase == ResetPhase.PRESERVED) {
                        "The original identity remains, but the reset marker could not be cleared. $HOME_LAN_RESET_PENDING"
                    } else {
                        "Could not reset this device's identity. The stored identity could not be verified as unchanged. $HOME_LAN_RESET_PENDING"
                    }
                    reconcileTrust()
                    _status.update { it.copy(lastError = message) }
                    error = IllegalStateException(message, failure)
                }
            }
        } finally {
            reconcileTrust()
        }
        restartGeneration?.let { startHubIfNeeded(it) }
        error?.let { failure ->
            _status.update { it.copy(lastError = failure.message) }
            throw failure
        }
    }

    override suspend fun syncNow(): Result<String> {
        // Never merge into a library that has not finished loading: the merge would be saved
        // over the real file (this wiped Android play history when sync beat startup).
        library.awaitLoaded()
        playlists.awaitLoaded()
        // Hub must be up before the PC acts as its own client (avoids Connection refused).
        if (shouldRunHub()) {
            startHubIfNeeded()
        }
        return mutex.withLock {
            if (resetBlocked()) return@withLock Result.failure(IllegalStateException(HOME_LAN_RESET_PENDING))
            if (!_status.value.enabled) {
                return@withLock Result.failure(IllegalStateException("Home sync is disabled"))
            }
            val target = syncTarget()
            if (target == null) {
                val msg = if (settings().homeLanSyncRepairNeeded) HOME_LAN_REPAIR_NOTICE else "Not paired"
                _status.update { it.copy(lastError = msg) }
                return@withLock Result.failure(IllegalStateException(msg))
            }
            var source = resolutionSource(target)
            val startedAt = clock()
            PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "sync start")
            runCatching {
                val deviceId = ensureDeviceId()
                val (connection, resolved) = openHubClient(target).getOrThrow()
                connection.use { client ->
                    check(rememberHubAddress(source, resolved)) { "Pairing changed during hub discovery" }
                    if (!shouldRunHub(source.role)) source = source.copy(target = target.at(resolved.endpoint))
                    withVaultSyncForeground("Home library sync") {
                        val remoteHearts = client.syncHearts().getOrThrow()
                        library.mergeAndPersistSyncState(remoteHearts)
                        val remotePlaylists = client.syncPlaylists().getOrThrow()
                        playlists.mergeAndPersistSyncState(remotePlaylists)
                        val rematchedPortable = rematchLocalHearts()
                        val rematchedPlaylistLocals = rematchPlaylistLocals()
                        refreshLocalLibrary()
                        val rematchedAgain = rematchLocalHearts()
                        val rematchedPlaylistAgain = rematchPlaylistLocals()

                        var vaultDetail = "vault skipped (not configured)"
                        var conflicts = emptyList<VaultConflict>()
                        var pending = emptyList<PendingVaultTransfer>()
                        if (vaultStore.isConfigured()) {
                            val heartsOnly = settings().homeLanSyncVaultHeartsOnly
                            val heartedNames = heartedLocalAudioFileNames(library)
                            val result = planVaultTransfers(
                                deviceId = deviceId,
                                store = vaultStore,
                                client = client,
                                heartsOnly = heartsOnly,
                                heartedBasenamesLower = heartedNames,
                            )
                            conflicts = result.conflicts
                            pending = result.pendingTransfers
                            vaultDetail =
                                "vault pending ↓${pending.count { it.direction == VaultCopyDirection.TO_LOCAL }} " +
                                    "↑${pending.count { it.direction == VaultCopyDirection.TO_REMOTE }} " +
                                    "tombs=${result.tombstonesApplied}" +
                                    if (heartsOnly) " (hearted only, ${heartedNames.size} names)" else ""
                        }
                        val rematched = rematchedPortable + rematchedAgain
                        val rematchedPl = rematchedPlaylistLocals + rematchedPlaylistAgain
                        val detail = buildString {
                            append("Hearts (${remoteHearts.ops.size} peer ops)")
                            append("; playlists (${remotePlaylists.playlists.size} peer)")
                            append("; $vaultDetail")
                            if (rematched > 0) append(", rematched $rematched local hearts")
                            if (rematchedPl > 0) append(", rematched $rematchedPl playlist locals")
                            if (pending.isNotEmpty()) {
                                append("; confirm ${pending.size} file transfer(s)")
                            }
                        }
                        val now = clock()
                        updateSettings {
                            it.copy(
                                homeLanSyncLastAtMs = now,
                                homeLanSyncLastError = null,
                                homeLanSyncLastDetail = detail,
                            )
                        }
                        _status.update {
                            it.copy(
                                lastSyncAtMs = now,
                                lastError = null,
                                lastDetail = detail,
                                vaultProgress = null,
                                pendingConflicts = conflicts,
                                pendingTransfers = pending,
                                bytesTransferred = 0,
                                bytesTotal = 0,
                            )
                        }
                        detail
                    }
                }
            }.onSuccess {
                PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "sync ok in ${clock() - startedAt} ms")
            }.onFailure { err ->
                if (!isCurrent(source)) return@onFailure
                val message = err.message ?: err.toString()
                PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "sync failed in ${clock() - startedAt} ms: $message")
                updateSettings { it.copy(homeLanSyncLastError = message) }
                _status.update {
                    it.copy(lastError = message, vaultProgress = null)
                }
            }
        }
    }

    override suspend fun confirmVaultTransfer(
        relPath: String,
        direction: VaultCopyDirection,
    ): Result<String> = mutex.withLock {
        val target = syncTarget()
            ?: return Result.failure(IllegalStateException("Not paired"))
        val path = relPath.normalizeVaultRelPath()
        val transfer = _status.value.pendingTransfers.firstOrNull {
            it.relPath == path && it.direction == direction
        } ?: return Result.failure(IllegalStateException("No pending transfer for $path"))
        runCatching {
            require(vaultStore.isConfigured()) { "Vault not configured" }
            withVaultSyncForeground("Vault transfer") {
                clientFactory(target).use { client ->
                    executeVaultTransfer(vaultStore, client, transfer, onProgress = ::reportVaultProgress)
                }
            }
            refreshLocalLibrary()
            rematchLocalHearts()
            _status.update {
                it.copy(
                    pendingTransfers = it.pendingTransfers.filterNot { t ->
                        t.relPath == path && t.direction == direction
                    },
                    vaultProgress = null,
                    bytesTransferred = 0,
                    bytesTotal = 0,
                    lastDetail = "Transferred $path",
                )
            }
            "Transferred $path"
        }.onFailure { err ->
            val message = err.message ?: err.toString()
            _status.update { it.copy(lastError = message, vaultProgress = null) }
        }
    }

    override suspend fun dismissVaultTransfer(
        relPath: String,
        direction: VaultCopyDirection,
    ): Result<String> = mutex.withLock {
        val path = relPath.normalizeVaultRelPath()
        _status.update {
            it.copy(
                pendingTransfers = it.pendingTransfers.filterNot { t ->
                    t.relPath == path && t.direction == direction
                },
            )
        }
        Result.success("Skipped $path")
    }

    override suspend fun tombstoneVaultPath(relPath: String): Result<String> = mutex.withLock {
        runCatching {
            val path = relPath.normalizeVaultRelPath()
            require(path.isNotBlank()) { "Blank path" }
            require(vaultStore.isConfigured()) { "Vault not configured" }
            val deviceId = ensureDeviceId()
            val now = clock()
            vaultStore.delete(path)
            val next = mergeTombstones(
                vaultStore.loadTombstones(),
                listOf(VaultTombstone(path, deletedAtMs = now, deviceId = deviceId, revision = now)),
            )
            vaultStore.saveTombstones(next)
            "Tombstoned $path"
        }
    }

    override suspend fun resolveConflictKeepLocal(relPath: String): Result<String> = mutex.withLock {
        val path = relPath.normalizeVaultRelPath()
        runCatching {
            val target = syncTarget() ?: error("Not paired")
            require(vaultStore.isConfigured()) { "Vault not configured" }
            val size = vaultStore.localSize(path) ?: error("Local file missing for $path")
            val transfer = PendingVaultTransfer(path, VaultCopyDirection.TO_REMOTE, sizeBytes = size)
            withVaultSyncForeground("Vault conflict") {
                clientFactory(target).use { client ->
                    executeVaultTransfer(
                        vaultStore,
                        client,
                        transfer,
                        replaceExisting = true,
                        onProgress = ::reportVaultProgress,
                    )
                }
            }
            resolvedConflict(path, "Kept local $path")
        }.onFailure { err -> conflictFailed(err) }
    }

    override suspend fun resolveConflictKeepRemote(relPath: String): Result<String> = mutex.withLock {
        val path = relPath.normalizeVaultRelPath()
        runCatching {
            val target = syncTarget() ?: error("Not paired")
            require(vaultStore.isConfigured()) { "Vault not configured" }
            val conflict = _status.value.pendingConflicts.firstOrNull { it.relPath == path }
                ?: error("No pending conflict for $path")
            val transfer = PendingVaultTransfer(
                relPath = path,
                direction = VaultCopyDirection.TO_LOCAL,
                sizeBytes = conflict.remote.sizeBytes,
                mtimeMs = conflict.remote.mtimeMs,
                contentHash = conflict.remote.contentHash,
            )
            try {
                withVaultSyncForeground("Vault conflict") {
                    clientFactory(target).use { client ->
                        executeVaultTransfer(
                            vaultStore,
                            client,
                            transfer,
                            replaceExisting = true,
                            onProgress = ::reportVaultProgress,
                        )
                    }
                }
            } catch (err: Throwable) {
                // Local copy is untouched (writes are staged); drop the partial remote bytes.
                runCatching { vaultStore.discardStaged(path) }
                throw err
            }
            refreshLocalLibrary()
            resolvedConflict(path, "Kept remote $path")
        }.onFailure { err -> conflictFailed(err) }
    }

    private fun reportVaultProgress(detail: String, transferred: Long, total: Long) {
        _status.update {
            it.copy(vaultProgress = detail, bytesTransferred = transferred, bytesTotal = total)
        }
    }

    /** Drops [path] from pending conflicts only after its copy fully succeeded. */
    private fun resolvedConflict(path: String, detail: String): String {
        _status.update {
            it.copy(
                pendingConflicts = it.pendingConflicts.filterNot { c -> c.relPath == path },
                vaultProgress = null,
                bytesTransferred = 0,
                bytesTotal = 0,
                lastDetail = detail,
            )
        }
        return detail
    }

    /** Failed resolution keeps the conflict pending so the user can retry. */
    private fun conflictFailed(err: Throwable) {
        val message = err.message ?: err.toString()
        _status.update { it.copy(lastError = message, vaultProgress = null) }
    }

    override suspend fun startHubIfNeeded() = startHubIfNeeded(null)

    private suspend fun startHubIfNeeded(expectedStopGeneration: Long?) {
        if (resetBlocked() || !shouldRunHub()) return
        if (!_status.value.enabled) return
        val deviceId = ensureDeviceId()
        mutex.withLock {
            if (expectedStopGeneration != null && (hubStopGeneration != expectedStopGeneration || !scope.isActive)) return
            if (resetBlocked() || !shouldRunHub() || !_status.value.enabled) return
            if (hub?.isListening == true) {
                return
            }
            hub?.stop()
            runCatching {
                val created = hubFactory(
                    HomeLanHubConfig(
                        deviceId = deviceId,
                        port = hubPort,
                        peers = { if (resetBlocked()) emptyList() else settings().homeLanSyncPeers },
                        pairing = pairingHost,
                        onHearts = { remote -> library.mergeAndPersistSyncState(remote) },
                        onPlaylists = { remote -> playlists.mergeAndPersistSyncState(remote) },
                        vault = vaultStore.takeIf { it.isConfigured() },
                        heartedVaultFileNames = {
                            if (settings().homeLanSyncVaultHeartsOnly) heartedLocalAudioFileNames(library) else null
                        },
                        listening = listening,
                    ),
                )
                created.start()
                hub = created
                _status.update {
                    it.copy(hubListening = true, lastError = null, lastDetail = "Hub listening on $hubPort")
                }
                startPairingExpiry()
            }.onFailure { err ->
                hub = null
                _status.update {
                    it.copy(
                        hubListening = false,
                        lastError = err.message ?: "Hub failed to start",
                    )
                }
            }
        }
    }

    override suspend fun stopHub() {
        invalidateResolution()
        mutex.withLock { stopHubLocked() }
    }

    private suspend fun stopHubLocked() {
        invalidateResolution()
        hubStopGeneration++
        pairingExpiryJob?.cancel()
        pairingExpiryJob = null
        hub?.stop()
        hub = null
        _status.update { it.copy(hubListening = false) }
    }

    private class ForegroundResolutionAborted : Exception()

    fun requestListeningDelivery() {
        listeningRunner?.request()
    }

    /** When the store cannot tell, the worker is scheduled anyway and decides from the store itself. */
    suspend fun scheduleListeningDeliveryIfPending() {
        val delivery = listeningDelivery ?: return
        val pending = try {
            delivery.canDeliverPending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "listening pending check failed: ${err.message ?: err}")
            true
        }
        if (pending) scheduleListeningDelivery()
    }

    fun onAppForeground() {
        listeningRunner?.request(exchangeOnForeground = true)
        scope.launch {
            var source: ResolutionSource? = null
            runCatching {
                if (!_status.value.enabled) return@runCatching
                startHubIfNeeded()
                if (!shouldRunClient()) return@runCatching
                if (!mutex.tryLock()) return@runCatching
                val captured = try {
                    if (resetBlocked() || !_status.value.enabled || !shouldRunClient()) return@runCatching
                    val target = settings().homeLanSyncHub ?: return@runCatching
                    resolutionSource(target)
                } finally {
                    mutex.unlock()
                }
                source = captured
                val reachable = openHubClient(captured.target) { peer ->
                    if (!mutex.tryLock()) throw ForegroundResolutionAborted()
                    try {
                        if (!isCurrent(captured) || !settings().matches(captured)) throw ForegroundResolutionAborted()
                        clientFactory(peer)
                    } finally {
                        mutex.unlock()
                    }
                }.getOrNull()?.let { (client, resolved) ->
                    client.close()
                    rememberHubAddress(captured, resolved)
                } ?: false
                if (reachable) {
                    syncNow()
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                if (err is ForegroundResolutionAborted) return@onFailure
                if (source?.let { !isCurrent(it) } == true) return@onFailure
                _status.update {
                    it.copy(lastError = err.message ?: "Home sync foreground check failed")
                }
            }
        }
    }

    private fun resetBlocked(): Boolean = identityResetMutex.isLocked || settings().homeLanSyncIdentityResetPending

    private fun syncTarget(): PairedPeer? =
        if (resetBlocked()) null else if (shouldRunHub()) localHubTarget(settings().homeLanSyncDeviceId) else settings().homeLanSyncHub

    private fun localHubTarget(deviceId: String?): PairedPeer = PairedPeer(
        deviceId = deviceId.orEmpty(),
        displayName = "This PC",
        fingerprint = identity.fingerprint(),
        lastKnownHost = "127.0.0.1",
        lastKnownPort = hubPort,
        pairedAtMs = 0L,
    )

    private suspend fun openHubClient(
        target: PairedPeer,
        acquireClient: suspend (PairedPeer) -> HomeLanSyncClient = { clientFactory(it) },
    ): Result<Pair<HomeLanSyncClient, ResolveResult.Resolved>> {
        val stored = target.lastKnownHost?.takeIf { it.isNotBlank() }?.let { HubEndpoint(it, target.lastKnownPort) }
        val clients = mutableMapOf<HubEndpoint, HomeLanSyncClient>()
        var trustFailure: String? = null
        val startedAt = clock()
        val resolver = HubEndpointResolver(
            browser = {
                hubBrowser.browse().onEach { advertised ->
                    if (advertised.id == target.deviceId && CertFingerprint.parse(advertised.fp) != target.fingerprint) {
                        trustFailure = HOME_LAN_IDENTITY_CHANGED
                    }
                }
            },
            probe = { endpoint ->
                val client = clients[endpoint] ?: acquireClient(target.at(endpoint)).also { clients[endpoint] = it }
                runCatching {
                    client.probeHub().getOrThrow()
                }.map { }.onFailure { err ->
                    if (err is CancellationException) throw err
                    trustFailureOf(err)?.let { trustFailure = it }
                }
            },
            log = { PlaybackTrace.log(HOME_SYNC_TRACE_TAG, it) },
        )
        val result = try {
            resolver.resolve(stored, target.fingerprint.hex)
        } catch (err: Throwable) {
            clients.values.forEach { it.close() }
            throw err
        }
        val winner = (result as? ResolveResult.Resolved)?.endpoint
        clients.filterKeys { it != winner }.values.forEach { it.close() }
        return when (result) {
            is ResolveResult.HubNotFound -> {
                trustFailure?.let { PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "$it (${result.reason})") }
                Result.failure(IllegalStateException(trustFailure ?: result.reason))
            }
            is ResolveResult.Resolved -> {
                PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "hub ${result.endpoint} via ${result.via} in ${clock() - startedAt} ms")
                Result.success(clients.getValue(result.endpoint) to result)
            }
        }
    }

    private suspend fun rememberHubAddress(source: ResolutionSource, resolved: ResolveResult.Resolved): Boolean {
        coroutineContext.ensureActive()
        if (!isCurrent(source)) return false
        if (resolved.via != ResolvedVia.MDNS || shouldRunHub(source.role)) return settings().matches(source)
        val endpoint = resolved.endpoint
        val committedSource = source.copy(target = source.target.at(endpoint))
        val resolutionContext = coroutineContext
        var stored = false
        try {
            // Finish an accepted settings commit before cancellation can interrupt its publication.
            withContext(NonCancellable) {
                updateSettings { current ->
                    synchronized(resolutionLock) {
                        if (resolutionContext.isActive && isCurrent(source) && current.matches(source)) {
                            stored = true
                            current.copy(homeLanSyncHub = committedSource.target)
                        } else current
                    }
                }
            }
        } finally {
            reconcileTrust()
        }
        coroutineContext.ensureActive()
        val remembered = synchronized(resolutionLock) {
            stored && isCurrent(committedSource) && settings().matches(committedSource)
        }
        if (!remembered) return false
        PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "resolved hub via mDNS ${endpoint.host}")
        return true
    }

    private fun AppSettings.matches(source: ResolutionSource): Boolean =
        homeLanSyncEnabled && homeLanSyncRole == source.role && effectiveTarget() == source.target

    private fun reconcileTrust() = synchronized(resolutionLock) {
        _trust.value = if (resetBlocked()) HomeLanTrust() else settings().trust()
    }

    private fun AppSettings.effectiveTarget(): PairedPeer? =
        if (homeLanSyncIdentityResetPending || resetBlocked()) null
        else if (shouldRunHub(homeLanSyncRole)) localHubTarget(homeLanSyncDeviceId) else homeLanSyncHub

    /** An attempt that times out disappears from the PC screen without waiting for the next request. */
    private fun startPairingExpiry() {
        pairingExpiryJob?.cancel()
        pairingExpiryJob = scope.launch {
            while (isActive) {
                delay(1_000)
                pairingHost.expireDue()
            }
        }
    }

    private fun shouldRunHub(role: HomeLanSyncRole = _status.value.role): Boolean = when (role) {
        HomeLanSyncRole.HUB -> true
        HomeLanSyncRole.CLIENT -> false
        HomeLanSyncRole.AUTO -> isDesktop
    }

    private fun shouldRunClient(): Boolean = when (_status.value.role) {
        HomeLanSyncRole.CLIENT -> true
        HomeLanSyncRole.HUB -> false
        HomeLanSyncRole.AUTO -> !isDesktop
    }

    private suspend fun ensureDeviceId(): String {
        val existing = settings().homeLanSyncDeviceId
        if (existing != null) return existing
        val id = newDeviceId()
        updateSettings { it.copy(homeLanSyncDeviceId = id) }
        return id
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun newDeviceId(): String =
        "kainos-" + Base64.UrlSafe.encode(secureRandomBytes(12)).trimEnd('=')
}

internal const val HOME_SYNC_TRACE_TAG = "HomeSync"

private fun PairedPeer.at(endpoint: HubEndpoint) = copy(lastKnownHost = endpoint.host, lastKnownPort = endpoint.port)

private fun AppSettings.trust() =
    if (homeLanSyncIdentityResetPending) HomeLanTrust()
    else HomeLanTrust(homeLanSyncHub, homeLanSyncPeers, homeLanSyncRepairNeeded)

private fun PairingReply<Unit>.toResult(): Result<Unit> = when (this) {
    is PairingReply.Ok -> Result.success(Unit)
    is PairingReply.Refused -> Result.failure(IllegalStateException(reason))
}

internal fun trustFailureOf(err: Throwable): String? {
    if ((err as? ResponseException)?.response?.status == HttpStatusCode.Unauthorized) return HOME_LAN_NOT_TRUSTED
    val pinMismatch = generateSequence(err) { it.cause }.any { HUB_PIN_MISMATCH in (it.message ?: "") }
    return if (pinMismatch) HOME_LAN_IDENTITY_CHANGED else null
}

fun ByteArray.toHex(): String =
    joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
