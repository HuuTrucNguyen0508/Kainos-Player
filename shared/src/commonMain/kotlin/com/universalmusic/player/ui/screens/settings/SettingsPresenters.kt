package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.library.folderPinDisplayName
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.spotify.SpotifyConnectDevice
import com.universalmusic.player.data.sync.HomeLanSyncController
import com.universalmusic.player.data.sync.HomeLanSyncPairing
import com.universalmusic.player.data.sync.HomeLanSyncStatus
import com.universalmusic.player.data.sync.VaultCopyDirection
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.provider.AuthSession
import com.universalmusic.player.platform.SpotifyWebPlaybackState
import com.universalmusic.player.platform.authenticateSpotify
import com.universalmusic.player.platform.ensureSpotifyConnectClientAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SettingsPreferencesPresenter(
    val state: StateFlow<AppSettings>,
    private val update: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val scope: CoroutineScope,
    private val pickVault: () -> Unit = {},
    private val clearVault: () -> Unit = {},
    private val hubAutostart: (Boolean) -> Unit = {},
) {
    fun change(transform: (AppSettings) -> AppSettings) {
        scope.launch { update(transform) }
    }

    fun pickVaultFolder() = pickVault()
    fun clearVaultFolder() = clearVault()
    fun setHubAutostart(enabled: Boolean) = hubAutostart(enabled)

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope) =
            SettingsPreferencesPresenter(container.settings, container::updateSettings, scope,
                { container.pickHomeLanVaultFolder() }, { container.setHomeLanVaultFolder(null) },
                { container.setHomeLanHubAutostartEnabled(it) })
    }
}

internal data class SettingsFoldersUiState(
    val settings: AppSettings,
    val local: ProviderState,
    val trackCount: Int,
    val message: String?,
    val folders: List<String>,
    val pinnedFolders: Set<String>,
)

internal class SettingsFoldersPresenter(
    settings: StateFlow<AppSettings>,
    local: StateFlow<ProviderState>,
    tracks: StateFlow<List<Track>>,
    message: StateFlow<String?>,
    pins: StateFlow<List<PersistedHomePin>>,
    private val folders: () -> List<String>,
    private val refreshLibrary: () -> Unit,
    private val pickFolder: () -> Unit,
    private val removeFolder: (String) -> Unit,
    private val includeMediaStore: (Boolean) -> Unit,
    private val pinFolder: (String, Boolean) -> Unit,
    scope: CoroutineScope,
) {
    val state: StateFlow<SettingsFoldersUiState> = combine(settings, local, tracks, message, pins) {
            config, provider, catalog, notice, homePins ->
        derive(config, provider, catalog, notice, homePins)
    }.stateIn(scope, SharingStarted.Eagerly, derive(settings.value, local.value, tracks.value, message.value, pins.value))

    private fun derive(settings: AppSettings, local: ProviderState, tracks: List<Track>, message: String?, pins: List<PersistedHomePin>) =
        SettingsFoldersUiState(settings, local, tracks.size, message, folders(),
            pins.filter { it.kind == HomePinKind.LOCAL_FOLDER }.map { it.targetId }.toSet())

    fun refresh() = refreshLibrary()
    fun addFolder() = pickFolder()
    fun remove(folder: String) = removeFolder(folder)
    fun setMediaStore(enabled: Boolean) = includeMediaStore(enabled)
    fun togglePin(folder: String) = pinFolder(folder, folder !in state.value.pinnedFolders)

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope) = SettingsFoldersPresenter(
            container.settings, container.local.state, container.local.libraryTracks,
            container.localLibraryMessage, container.library.homePins, container::effectiveLocalMusicFolders,
            container::refreshLocalLibrary, { container.addLocalMusicFolderFromPicker() },
            { container.removeLocalMusicFolder(it) }, { container.setIncludeMediaStoreLibrary(it) },
            { folder, pinned ->
                if (pinned) container.library.pinHome(HomePinKind.LOCAL_FOLDER, folder, folderPinDisplayName(folder), "Local folder")
                else container.library.unpinHome(HomePinKind.LOCAL_FOLDER, folder)
            }, scope,
        )
    }
}

internal data class SettingsConnectUiState(
    val settings: AppSettings,
    val config: AppConfig,
    val ready: Boolean,
    val spotify: ProviderState,
    val youtube: ProviderState,
    val playback: SpotifyWebPlaybackState,
    val libraryLoading: Boolean,
    val libraryError: String?,
    val action: ProviderActionState,
)

internal data class ProviderActionState(
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val devices: List<SpotifyConnectDevice> = emptyList(),
    val devicesLoaded: Boolean = false,
)

internal class SettingsConnectPresenter(
    private val settings: StateFlow<AppSettings>,
    private val ready: StateFlow<Boolean>,
    private val spotify: StateFlow<ProviderState>,
    private val youtube: StateFlow<ProviderState>,
    private val playback: StateFlow<SpotifyWebPlaybackState>,
    private val libraryLoading: StateFlow<Boolean>,
    private val libraryError: StateFlow<String?>,
    private val config: () -> AppConfig,
    val nativeSpotify: Boolean,
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val beginLogin: suspend () -> AuthSession,
    private val completeLogin: suspend (String) -> Unit,
    private val disconnectAccount: suspend () -> Unit,
    private val loadDevices: suspend () -> List<SpotifyConnectDevice>,
    private val prepareReceiver: suspend () -> Boolean,
    private val refresh: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    private val action = MutableStateFlow(ProviderActionState())
    private fun base() = SettingsConnectUiState(settings.value, config(), ready.value, spotify.value,
        youtube.value, playback.value, libraryLoading.value, libraryError.value, action.value)
    val state: StateFlow<SettingsConnectUiState> = combine(
        combine(settings, ready, spotify, youtube, playback) { prefs, loaded, sp, yt, player ->
            SettingsConnectUiState(prefs, config(), loaded, sp, yt, player, false, null, action.value)
        },
        libraryLoading, libraryError, action,
    ) { base, loading, error, operation -> base.copy(libraryLoading = loading, libraryError = error, action = operation) }
        .stateIn(scope, SharingStarted.Eagerly, base())

    private fun perform(operation: suspend () -> String?) {
        if (action.value.busy || !ready.value) return
        action.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            try {
                val notice = operation()
                action.update { it.copy(notice = notice) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                action.update { it.copy(error = failure.message ?: "Provider connection failed. Try again.") }
            } finally {
                action.update { it.copy(busy = false) }
            }
        }
    }

    fun saveCredentials(clientId: String, apiKey: String) = perform {
        updateSettings { it.copy(spotifyClientId = clientId.trim().takeIf(String::isNotBlank),
            youtubeDataApiKey = apiKey.trim().takeIf(String::isNotBlank)) }
        "Provider settings saved."
    }

    fun connect() = perform {
        val login = beginLogin()
        val redirect = authenticateSpotify(login.authorizationUrl, login.redirectScheme)
        if (redirect == null) "Finish signing in in your browser to connect Spotify."
        else {
            completeLogin(redirect)
            if (spotify.value == ProviderState.RATE_LIMITED)
                "Spotify connected, but the developer API quota is exhausted. Playback may work; library refresh should wait."
            else { refresh(); "Spotify connected." }
        }
    }

    fun disconnect() = perform { disconnectAccount(); null }
    fun openSpotify() = perform {
        check(ensureSpotifyConnectClientAvailable()) {
            "Install the Spotify app to use this phone as a Connect device, or pick another online device below."
        }
        "Spotify opened. Sign in if needed, then refresh devices and select this phone."
    }

    fun refreshDevices() = perform {
        val devices = loadDevices()
        action.update { it.copy(devices = devices, devicesLoaded = true) }
        val selected = settings.value.spotifyPlaybackDeviceId
        if (selected != null && devices.none { it.id == selected }) {
            updateSettings { it.copy(spotifyPlaybackDeviceId = null, spotifyPlaybackDeviceName = null) }
            "Previous Spotify device went offline. Select another device below."
        } else if (devices.isEmpty()) "No Connect devices yet. Open Spotify somewhere, then refresh."
        else "${devices.size} Spotify device${if (devices.size == 1) "" else "s"} found."
    }

    fun selectDevice(device: SpotifyConnectDevice) = perform {
        updateSettings { it.copy(spotifyPlaybackDeviceId = device.id, spotifyPlaybackDeviceName = device.name) }
        "Spotify will play on ${device.name}."
    }

    fun setupReceiver() = perform {
        check(prepareReceiver()) {
            (playback.value as? SpotifyWebPlaybackState.Failed)?.reason?.let(::spotifyPlaybackFailureMessage)
                ?: "Spotify receiver setup failed. Try setup again."
        }
        "Receiver started. Complete Spotify sign-in if a browser page opens, then choose a track in Kainos."
    }

    fun saveDiscoverWeekly(link: String) = perform {
        updateSettings { it.copy(spotifyDiscoverWeeklyPlaylistId = link.trim().ifEmpty { null }) }
        refresh()
        "Saved Discover Weekly link and refreshed."
    }

    fun refreshLibrary() = perform { refresh(); null }

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope) = SettingsConnectPresenter(
            container.settings, container.ready, container.spotify.state, container.youtube.state,
            container.spotifyWebPlayback.state, container.spotifyLibraryLoading, container.spotifyLibraryError,
            { container.config }, !container.spotifyWebPlayback.requiresStreamingScope, container::updateSettings,
            container.spotify::beginLogin, container.spotify::completeLogin, container::disconnectSpotify,
            container.spotify::getConnectDevices,
            { container.spotifyWebPlayback.prepareAuthentication() != null }, container::refreshSpotifyLibrary, scope,
        )
    }
}

internal data class SettingsSyncUiState(
    val status: HomeLanSyncStatus,
    val pairing: HomeLanSyncPairing?,
    val pairingUri: String?,
    val busy: Boolean = false,
    val notice: String? = null,
)

internal class SettingsSyncPresenter(
    private val controller: HomeLanSyncController,
    private val scope: CoroutineScope,
) {
    private val operation = MutableStateFlow(false to (null as String?))
    val state: StateFlow<SettingsSyncUiState> = combine(controller.status, controller.pairing, operation) { status, pairing, op ->
        SettingsSyncUiState(status, pairing, controller.pairingUri(), op.first, op.second)
    }.stateIn(scope, SharingStarted.Eagerly,
        SettingsSyncUiState(controller.status.value, controller.pairing.value, controller.pairingUri()))

    fun setNotice(message: String) { operation.value = operation.value.first to message }

    fun run(operation: suspend () -> String) {
        if (this.operation.value.first) return
        this.operation.value = true to null
        scope.launch {
            try { setNotice(operation()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { setNotice(failure.message ?: "Home sync failed. Try again.") }
            finally { this@SettingsSyncPresenter.operation.value = false to this@SettingsSyncPresenter.operation.value.second }
        }
    }

    fun enable(enabled: Boolean) = run { controller.enable(enabled); "Home sync ${if (enabled) "enabled" else "disabled"}." }
    fun beginPairing(copyUri: (String) -> Unit) = run {
        val pairing = controller.beginHubPairing()
        val uri = controller.pairingUri()
        if (uri != null) {
            copyUri(uri)
            "Hub on port ${pairing.hubPort}. PIN ${pairing.pairingPin}. Pairing URI copied to clipboard."
        } else "Hub on port ${pairing.hubPort}. PIN ${pairing.pairingPin}. URI unavailable. Rotate pairing again."
    }
    fun pair(uri: String) = run { controller.completeClientPairingFromUri(uri); "Paired from URI" }
    fun syncNow() = run { controller.syncNow().getOrThrow() }
    fun unpair() = run { controller.unpair(); "Unpaired" }
    fun transfer(path: String, direction: VaultCopyDirection, confirm: Boolean) = run {
        (if (confirm) controller.confirmVaultTransfer(path, direction)
        else controller.dismissVaultTransfer(path, direction)).getOrThrow()
    }
    fun resolve(path: String, keepLocal: Boolean) = run {
        (if (keepLocal) controller.resolveConflictKeepLocal(path)
        else controller.resolveConflictKeepRemote(path)).getOrThrow()
    }
    fun removeVaultFile(path: String) = run { controller.tombstoneVaultPath(path.trim()).getOrThrow() }
    fun pairManually(host: String, secret: String, peer: String, pin: String, cert: String) = run {
        controller.completeClientPairing(host.trim(), controller.pairing.value?.hubPort ?: 43822,
            secret.trim(), peer.trim(), pin.trim(), cert.trim())
        "Paired with $host"
    }

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope) = SettingsSyncPresenter(container.homeLanSync, scope)
    }
}
