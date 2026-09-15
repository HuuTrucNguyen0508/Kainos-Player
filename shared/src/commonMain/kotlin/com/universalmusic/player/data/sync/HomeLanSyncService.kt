package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
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
 * Coordinates home-LAN hearts + vault sync. Platform wires hub/client/vault factories.
 */
class HomeLanSyncService(
    private val scope: CoroutineScope,
    private val settings: () -> AppSettings,
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val library: LibraryRepository,
    private val hubFactory: (
        HomeLanSyncPairing,
        suspend (HeartsSyncDocument) -> HeartsSyncDocument,
        HomeLanVaultStore?,
        () -> Set<String>?,
    ) -> HomeLanSyncHub,
    private val clientFactory: (HomeLanSyncPairing) -> HomeLanSyncClient,
    private val vaultStore: HomeLanVaultStore,
    private val refreshLocalLibrary: suspend () -> Unit,
    private val rematchLocalHearts: suspend () -> Int = { 0 },
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
            current.homeLanSyncPairing.hubCertSha256Hex.isNullOrBlank() &&
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

    override suspend fun syncNow(): Result<String> = mutex.withLock {
        val pairing = _pairing.value
            ?: return Result.failure(IllegalStateException("Not paired"))
        if (!_status.value.enabled) {
            return Result.failure(IllegalStateException("Home sync is disabled"))
        }
        if (shouldRunClient() && pairing.hubCertSha256Hex.isNullOrBlank()) {
            val msg = "Re-pair with kainos-homesync:2 URI from the PC (TLS cert pin required)"
            _status.update { it.copy(lastError = msg) }
            return Result.failure(IllegalStateException(msg))
        }
        return runCatching {
            ensureDeviceId()
            withVaultSyncForeground("Home library sync") {
                val client = clientFactory(pairing)
                val remoteHearts = client.syncHearts().getOrThrow()
                library.mergeAndPersistSyncState(remoteHearts)

                var vaultDetail = "vault skipped (not configured)"
                var conflicts = emptyList<VaultConflict>()
                if (vaultStore.isConfigured()) {
                    val heartsOnly = settings().homeLanSyncVaultHeartsOnly
                    val heartedNames = heartedLocalAudioFileNames(library)
                    val result = transferVaultFiles(
                        deviceId = pairing.deviceId,
                        store = vaultStore,
                        client = client,
                        heartsOnly = heartsOnly,
                        heartedBasenamesLower = heartedNames,
                    ) { detail, transferred, total ->
                        _status.update {
                            it.copy(
                                vaultProgress = detail,
                                bytesTransferred = transferred,
                                bytesTotal = total,
                            )
                        }
                    }
                    conflicts = result.conflicts
                    vaultDetail =
                        "vault ↓${result.downloaded} ↑${result.uploaded} tombs=${result.tombstonesApplied}" +
                            if (heartsOnly) " (hearted only, ${heartedNames.size} names)" else ""
                    refreshLocalLibrary()
                    val rematched = rematchLocalHearts()
                    if (rematched > 0) {
                        vaultDetail += ", rematched $rematched local hearts"
                    }
                }

                val detail = "Hearts (${remoteHearts.ops.size} peer ops); $vaultDetail"
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
                        bytesTransferred = 0,
                        bytesTotal = 0,
                    )
                }
                if (conflicts.isNotEmpty()) {
                    "$detail; ${conflicts.size} conflict(s) skipped"
                } else {
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

    override suspend fun tombstoneVaultPath(relPath: String): Result<String> = runCatching {
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

    override suspend fun resolveConflictKeepLocal(relPath: String): Result<String> = runCatching {
        val path = relPath.normalizeVaultRelPath()
        val pairing = _pairing.value ?: error("Not paired")
        require(vaultStore.isConfigured()) { "Vault not configured" }
        val entry = vaultStore.buildIndex(pairing.deviceId).entries.firstOrNull {
            it.relPath == path
        } ?: error("Local file missing for $path")
        val client = clientFactory(pairing)
        var offset = 0L
        while (offset < entry.sizeBytes) {
            val end = minOf(offset + VAULT_BLOB_CHUNK_BYTES - 1, entry.sizeBytes - 1)
            val chunk = vaultStore.readRange(path, offset, end)
            client.uploadBlob(path, offset, entry.sizeBytes, chunk).getOrThrow()
            offset += chunk.size.coerceAtLeast(1)
            if (chunk.isEmpty()) break
        }
        _status.update { it.copy(pendingConflicts = it.pendingConflicts.filterNot { c -> c.relPath == path }) }
        "Kept local $path"
    }

    override suspend fun resolveConflictKeepRemote(relPath: String): Result<String> = runCatching {
        val path = relPath.normalizeVaultRelPath()
        val pairing = _pairing.value ?: error("Not paired")
        require(vaultStore.isConfigured()) { "Vault not configured" }
        val conflict = _status.value.pendingConflicts.firstOrNull { it.relPath == path }
            ?: error("No pending conflict for $path")
        val remote = conflict.remote
        val client = clientFactory(pairing)
        var offset = 0L
        while (offset < remote.sizeBytes) {
            val chunkLen = minOf(VAULT_BLOB_CHUNK_BYTES.toLong(), remote.sizeBytes - offset).toInt()
            val chunk = client.downloadBlob(path, offset, chunkLen).getOrThrow()
            vaultStore.writeRange(path, offset, chunk, remote.sizeBytes)
            offset += chunk.size.coerceAtLeast(1)
            if (chunk.isEmpty()) break
        }
        refreshLocalLibrary()
        _status.update { it.copy(pendingConflicts = it.pendingConflicts.filterNot { c -> c.relPath == path }) }
        "Kept remote $path"
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
                val probe = clientFactory(pairing).probeHub()
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
