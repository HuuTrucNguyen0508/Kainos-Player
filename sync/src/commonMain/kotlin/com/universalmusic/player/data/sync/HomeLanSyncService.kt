package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.PlaylistsSyncDocument
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.platform.generateHomeLanHubCertPin
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.secureRandomBytes
import com.universalmusic.player.platform.withVaultSyncForeground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
    private val hubFactory: (
        HomeLanSyncPairing,
        suspend (HeartsSyncDocument) -> HeartsSyncDocument,
        suspend (PlaylistsSyncDocument) -> PlaylistsSyncDocument,
        HomeLanVaultStore?,
        () -> Set<String>?,
    ) -> HomeLanSyncHub,
    private val clientFactory: (HomeLanSyncPairing) -> HomeLanSyncClient,
    private val vaultStore: HomeLanVaultStore,
    private val refreshLocalLibrary: suspend () -> Unit,
    private val rematchLocalHearts: suspend () -> Int = { 0 },
    private val rematchPlaylistLocals: suspend () -> Int = { 0 },
    private val detectedLanHost: () -> String? = { null },
    private val clock: () -> Long = ::currentTimeMillis,
    private val isDesktop: Boolean = platformLabel() == "Linux",
) : HomeLanSyncController {
    private val _status = MutableStateFlow(HomeLanSyncStatus())
    override val status: StateFlow<HomeLanSyncStatus> = _status.asStateFlow()

    private val _pairing = MutableStateFlow<HomeLanSyncPairing?>(null)
    override val pairing: StateFlow<HomeLanSyncPairing?> = _pairing.asStateFlow()

    private var hub: HomeLanSyncHub? = null
    private val mutex = Mutex()
    private var idleWatchJob: Job? = null

    fun hydrateFromSettings(current: AppSettings) {
        _pairing.value = current.homeLanSyncPairing
        val needsTlsRepair = current.homeLanSyncEnabled &&
            current.homeLanSyncPairing != null &&
            current.homeLanSyncPairing?.hubCertSha256Hex.isNullOrBlank() &&
            (current.homeLanSyncRole == HomeLanSyncRole.CLIENT ||
                (current.homeLanSyncRole == HomeLanSyncRole.AUTO && !isDesktop))
        _status.update {
            it.copy(
                enabled = current.homeLanSyncEnabled,
                role = current.homeLanSyncRole,
                lastSyncAtMs = current.homeLanSyncLastAtMs,
                lastError = when {
                    needsTlsRepair ->
                        "Re-pair with kainos-homesync:2 URI from the PC (TLS cert pin required)"
                    else -> current.homeLanSyncLastError
                },
                lastDetail = current.homeLanSyncLastDetail,
            )
        }
    }

    override suspend fun enable(enabled: Boolean) {
        updateSettings { it.copy(homeLanSyncEnabled = enabled) }
        _status.update { it.copy(enabled = enabled) }
        if (enabled) startHubIfNeeded() else stopHub()
    }

    override suspend fun beginHubPairing(): HomeLanSyncPairing {
        val deviceId = settings().homeLanSyncDeviceId ?: newDeviceId().also { id ->
            updateSettings { it.copy(homeLanSyncDeviceId = id) }
        }
        val secret = secureRandomBytes(32).toHex()
        val pin = (100000..999999).random().toString()
        var pairing = HomeLanSyncPairing(
            deviceId = deviceId,
            sharedSecretHex = secret,
            hubPort = HOME_LAN_SYNC_DEFAULT_PORT,
            pairingPin = pin,
            hubHost = detectedLanHost(),
            encryptPayloads = true,
        )
        val certPin = generateHomeLanHubCertPin(pairing)
            ?: error("Hub TLS certificate generation is only available on desktop")
        pairing = pairing.copy(hubCertSha256Hex = certPin)
        updateSettings {
            it.copy(
                homeLanSyncEnabled = true,
                homeLanSyncRole = HomeLanSyncRole.HUB,
                homeLanSyncPairing = pairing,
                homeLanSyncDeviceId = deviceId,
            )
        }
        _pairing.value = pairing
        _status.update { it.copy(enabled = true, role = HomeLanSyncRole.HUB) }
        // Always restart: a listening hub still serves the previous cert/secret.
        stopHub()
        startHubIfNeeded()
        return pairing
    }

    override suspend fun completeClientPairing(
        hubHost: String,
        hubPort: Int,
        sharedSecretHex: String,
        peerDeviceId: String,
        pairingPin: String,
        hubCertSha256Hex: String,
    ) {
        val cert = hubCertSha256Hex.trim().lowercase()
        require(cert.matches(Regex("[0-9a-f]{64}"))) {
            "Hub certificate pin required (64 hex chars). Prefer Pair from URI after Start hub pairing on the PC."
        }
        val deviceId = settings().homeLanSyncDeviceId ?: newDeviceId().also { id ->
            updateSettings { it.copy(homeLanSyncDeviceId = id) }
        }
        val pairing = HomeLanSyncPairing(
            deviceId = deviceId,
            peerDeviceId = peerDeviceId.ifBlank { null },
            sharedSecretHex = sharedSecretHex.trim(),
            hubHost = hubHost.trim(),
            hubPort = hubPort,
            pairingPin = pairingPin.trim().ifBlank { null },
            hubCertSha256Hex = cert,
            encryptPayloads = true,
        )
        updateSettings {
            it.copy(
                homeLanSyncEnabled = true,
                homeLanSyncRole = HomeLanSyncRole.CLIENT,
                homeLanSyncPairing = pairing,
                homeLanSyncDeviceId = deviceId,
            )
        }
        _pairing.value = pairing
        _status.update { it.copy(enabled = true, role = HomeLanSyncRole.CLIENT, lastError = null) }
        stopHub()
    }

    override suspend fun completeClientPairingFromUri(uri: String) {
        val parsed = parseHomeLanPairingUri(uri)
            ?: error("Not a valid kainos-homesync:2 pairing URI (must include cert= pin)")
        completeClientPairing(
            hubHost = parsed.hubHost.orEmpty(),
            hubPort = parsed.hubPort,
            sharedSecretHex = parsed.sharedSecretHex,
            peerDeviceId = parsed.peerDeviceId.orEmpty(),
            pairingPin = parsed.pairingPin.orEmpty(),
            hubCertSha256Hex = parsed.hubCertSha256Hex.orEmpty(),
        )
    }

    override suspend fun unpair() {
        stopHub()
        updateSettings {
            it.copy(
                homeLanSyncEnabled = false,
                homeLanSyncPairing = null,
                homeLanSyncLastError = null,
                homeLanSyncLastDetail = null,
            )
        }
        _pairing.value = null
        _status.update {
            HomeLanSyncStatus(enabled = false, role = settings().homeLanSyncRole)
        }
    }

    override fun pairingUri(): String? {
        val pairing = _pairing.value ?: return null
        if (!shouldRunHub()) return null
        if (pairing.hubCertSha256Hex.isNullOrBlank()) return null
        val host = pairing.hubHost?.takeIf { it.isNotBlank() } ?: detectedLanHost()
        return pairing.toPairingUri(host)
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
            val pairing = _pairing.value
                ?: return@withLock Result.failure(IllegalStateException("Not paired"))
            if (!_status.value.enabled) {
                return@withLock Result.failure(IllegalStateException("Home sync is disabled"))
            }
            if (pairing.hubCertSha256Hex.isNullOrBlank() && shouldRunClient()) {
                val msg = "Re-pair with kainos-homesync:2 URI from the PC (TLS cert pin required)"
                _status.update { it.copy(lastError = msg) }
                return@withLock Result.failure(IllegalStateException(msg))
            }
            // Hub Sync now also talks to the local HTTPS endpoint as a client.
            if (pairing.hubCertSha256Hex.isNullOrBlank()) {
                val msg = "Re-pair with kainos-homesync:2 URI (TLS cert pin required)"
                _status.update { it.copy(lastError = msg) }
                return@withLock Result.failure(IllegalStateException(msg))
            }
            runCatching {
                ensureDeviceId()
                withVaultSyncForeground("Home library sync") {
                    clientFactory(pairing).use { client ->
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
                                deviceId = pairing.deviceId,
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
            }.onFailure { err ->
                val message = err.message ?: err.toString()
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
        val pairing = _pairing.value
            ?: return Result.failure(IllegalStateException("Not paired"))
        val path = relPath.normalizeVaultRelPath()
        val transfer = _status.value.pendingTransfers.firstOrNull {
            it.relPath == path && it.direction == direction
        } ?: return Result.failure(IllegalStateException("No pending transfer for $path"))
        runCatching {
            require(vaultStore.isConfigured()) { "Vault not configured" }
            withVaultSyncForeground("Vault transfer") {
                clientFactory(pairing).use { client ->
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
            val pairing = _pairing.value ?: error("Not paired")
            require(vaultStore.isConfigured()) { "Vault not configured" }
            val size = vaultStore.localSize(path) ?: error("Local file missing for $path")
            val transfer = PendingVaultTransfer(path, VaultCopyDirection.TO_REMOTE, sizeBytes = size)
            withVaultSyncForeground("Vault conflict") {
                clientFactory(pairing).use { client ->
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
            val pairing = _pairing.value ?: error("Not paired")
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
                    clientFactory(pairing).use { client ->
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

    override suspend fun startHubIfNeeded() {
        if (!shouldRunHub()) return
        val pairing = _pairing.value ?: return
        if (!_status.value.enabled) return
        mutex.withLock {
            if (hub?.isListening == true) {
                // Already up for this process; callers that rotate pairing must stopHub() first.
                return
            }
            hub?.stop()
            runCatching {
                val created = hubFactory(
                    pairing,
                    { remote -> library.mergeAndPersistSyncState(remote) },
                    { remote -> playlists.mergeAndPersistSyncState(remote) },
                    vaultStore.takeIf { it.isConfigured() },
                    {
                        if (settings().homeLanSyncVaultHeartsOnly) {
                            heartedLocalAudioFileNames(library)
                        } else {
                            null
                        }
                    },
                )
                created.start()
                hub = created
                _status.update {
                    it.copy(hubListening = true, lastError = null, lastDetail = "Hub listening on ${pairing.hubPort}")
                }
                startIdleWatch()
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
        idleWatchJob?.cancel()
        idleWatchJob = null
        mutex.withLock {
            hub?.stop()
            hub = null
            _status.update { it.copy(hubListening = false) }
        }
    }

    fun onAppForeground() {
        scope.launch {
            runCatching {
                if (!_status.value.enabled) return@runCatching
                startHubIfNeeded()
                if (!shouldRunClient()) return@runCatching
                val pairing = _pairing.value ?: return@runCatching
                if (pairing.hubCertSha256Hex.isNullOrBlank()) {
                    _status.update {
                        it.copy(
                            lastError =
                                "Re-pair with kainos-homesync:2 URI from the PC (TLS cert pin required)",
                        )
                    }
                    return@runCatching
                }
                val probe = clientFactory(pairing).use { it.probeHub() }
                if (probe.isSuccess) {
                    syncNow()
                }
            }.onFailure { err ->
                _status.update {
                    it.copy(lastError = err.message ?: "Home sync foreground check failed")
                }
            }
        }
    }

    private fun startIdleWatch() {
        idleWatchJob?.cancel()
        idleWatchJob = scope.launch {
            while (isActive) {
                delay(60_000)
                val last = hub?.lastActivityAtMs() ?: continue
                if (clock() - last >= HOME_LAN_HUB_IDLE_TIMEOUT_MS) {
                    stopHub()
                    break
                }
            }
        }
    }

    private fun shouldRunHub(): Boolean = when (_status.value.role) {
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

fun ByteArray.toHex(): String =
    joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
