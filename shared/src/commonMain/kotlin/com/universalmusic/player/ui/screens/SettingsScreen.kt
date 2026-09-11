package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.spotify.SpotifyConnectDevice
import com.universalmusic.player.data.settings.AppColorScheme
import com.universalmusic.player.data.settings.ThemeMode
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.platform.SpotifyWebPlaybackFailure
import com.universalmusic.player.platform.SpotifyWebPlaybackState
import com.universalmusic.player.platform.describeLibrespotConnectionFailure
import com.universalmusic.player.platform.defaultLocalMusicFolder
import com.universalmusic.player.platform.authenticateSpotify
import com.universalmusic.player.platform.requiresExplicitSpotifyDevice
import com.universalmusic.player.platform.ensureSpotifyConnectClientAvailable
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.supportsMusicFolderPicker
import com.universalmusic.player.ui.theme.colorSchemeFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val settings by container.settings.collectAsState()
    val spotify by container.spotify.state.collectAsState()
    val spotifyPlayback by container.spotifyWebPlayback.state.collectAsState()
    val nativeSpotify = !container.spotifyWebPlayback.requiresStreamingScope
    var spotifyDevices by remember { mutableStateOf<List<SpotifyConnectDevice>>(emptyList()) }
    var spotifyDevicesLoaded by remember { mutableStateOf(false) }
    val youtube by container.youtube.state.collectAsState()
    val local by container.local.state.collectAsState()
    val localTracks by container.local.libraryTracks.collectAsState()
    val scope = rememberCoroutineScope()
    val ready by container.ready.collectAsState()
    var spotifyClientId by remember(settings.spotifyClientId, ready) { mutableStateOf(container.config.spotifyClientId.orEmpty()) }
    var youtubeApiKey by remember(settings.youtubeDataApiKey, ready) { mutableStateOf(container.config.youtubeDataApiKey.orEmpty()) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var providerNotice by remember { mutableStateOf<String?>(null) }
    var providerBusy by remember { mutableStateOf(false) }
    val libraryLoading by container.spotifyLibraryLoading.collectAsState()
    val libraryError by container.spotifyLibraryError.collectAsState()
    fun providerAction(action: suspend () -> Unit) {
        providerBusy = true
        providerError = null
        providerNotice = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { providerError = failure.message ?: "Provider connection failed. Try again." }
            finally { providerBusy = false }
        }
    }
    val localFolders = container.effectiveLocalMusicFolders()
    val usingDefaultFolder = !settings.localMusicFoldersConfigured
    val localLibraryMessage by container.localLibraryMessage.collectAsState()
    val canPickFolders = supportsMusicFolderPicker()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Playback", style = MaterialTheme.typography.titleMedium)
        Text("Quality preference", style = MaterialTheme.typography.labelLarge)
        SourceSelectionMode.entries.filterNot { it.name.startsWith("FORCE") }.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(settings.sourceSelection == mode) {
                        scope.launch { container.updateSettings { it.copy(sourceSelection = mode) } }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.sourceSelection == mode, onClick = {
                    scope.launch { container.updateSettings { it.copy(sourceSelection = mode) } }
                })
                Text(mode.label(), modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text(
            "Gapless playback and volume normalization are not available yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingToggle(
            "Autoplay similar tracks after Search (default off)",
            settings.searchAutoplayEnabled,
        ) {
            scope.launch { container.updateSettings { current -> current.copy(searchAutoplayEnabled = it) } }
        }
        Text(
            "When on, finishing a Search result queue can append one continuation batch. " +
                "Manually queued tracks stay ahead of autoplay. Spotify radio uses /recommendations only when " +
                "this Client ID is allowed; otherwise Spotify continuation is skipped (no Liked Songs shuffle).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Providers", style = MaterialTheme.typography.titleMedium)
        ProviderAccountRow(
            name = "Local library",
            state = local,
            configured = true,
            connected = local == ProviderState.AVAILABLE,
            onConnect = container::refreshLocalLibrary,
            onDisconnect = {},
            showButtons = false,
            detail = if (canPickFolders) {
                buildString {
                    append("${localTracks.size} tracks in your music folders.")
                    if (usingDefaultFolder && defaultLocalMusicFolder().isNotBlank()) {
                        append(" Using the default Music folder until you add folders.")
                    } else if (settings.localMusicFoldersConfigured && settings.localMusicFolders.isEmpty()) {
                        append(" No folders selected — nothing is indexed from disk.")
                    }
                    localLibraryMessage?.let { append(" $it") }
                }
            } else {
                "${localTracks.size} tracks."
            },
        )
        if (canPickFolders) {
            Text("Music folders", style = MaterialTheme.typography.labelLarge)
            if (usingDefaultFolder) {
                Text(
                    "Using the default Music folder until you add or change folders.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (localFolders.isEmpty()) {
                Text(
                    "No folders selected. Local files will not appear until you add a folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            localLibraryMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            localFolders.forEach { folder ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        folder,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    OutlinedButton(
                        onClick = { container.removeLocalMusicFolder(folder) },
                    ) {
                        Text("Remove")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = container::addLocalMusicFolderFromPicker) {
                    Text("Add folder")
                }
                OutlinedButton(onClick = container::refreshLocalLibrary, enabled = local != ProviderState.LOADING) {
                    Text(if (local == ProviderState.LOADING) "Scanning…" else "Refresh")
                }
            }
            if (platformLabel() == "Android") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(settings.includeMediaStoreLibrary) {
                            container.setIncludeMediaStoreLibrary(!settings.includeMediaStoreLibrary)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = settings.includeMediaStoreLibrary,
                        onCheckedChange = { container.setIncludeMediaStoreLibrary(it) },
                    )
                    Text(
                        "Also include device MediaStore library (entire device music index). " +
                            "Turn this off when using picked folders.",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        } else {
            OutlinedButton(onClick = container::refreshLocalLibrary, enabled = local != ProviderState.LOADING) {
                Text(if (local == ProviderState.LOADING) "Scanning…" else "Refresh local library")
            }
        }
        Text("Provider setup", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = spotifyClientId,
            onValueChange = { spotifyClientId = it },
            label = { Text("Spotify Client ID") },
            singleLine = true,
            enabled = ready && !providerBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Register this Spotify redirect URI: ${container.config.spotifyRedirectUri}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = youtubeApiKey,
            onValueChange = { youtubeApiKey = it },
            label = { Text("YouTube Data API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = ready && !providerBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Enable YouTube Data API v3 in your Google Cloud project. These values are saved on this device. Empty fields use secrets.properties or environment defaults.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = ready && !providerBusy, onClick = {
            providerAction {
                container.updateSettings { it.copy(
                    spotifyClientId = spotifyClientId.trim().takeIf(String::isNotBlank),
                    youtubeDataApiKey = youtubeApiKey.trim().takeIf(String::isNotBlank),
                ) }
                providerNotice = "Provider settings saved."
            }
        }) { Text(if (providerBusy) "Working…" else "Save provider settings") }
        providerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        providerNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ProviderAccountRow(
            name = "Spotify",
            state = spotify,
            configured = container.config.hasSpotifyCredentials,
            connected = spotify == ProviderState.AVAILABLE || spotify == ProviderState.RATE_LIMITED,
            onConnect = {
                providerAction {
                    val session = container.spotify.beginLogin()
                    val redirect = authenticateSpotify(session.authorizationUrl, session.redirectScheme)
                    if (redirect != null) {
                        container.spotify.completeLogin(redirect)
                        val limited = container.spotify.state.value == ProviderState.RATE_LIMITED
                        providerNotice = if (limited) {
                            "Spotify connected, but the developer API quota is exhausted. Playback may work; library refresh should wait."
                        } else {
                            "Spotify connected."
                        }
                        if (!limited) container.refreshSpotifyLibrary()
                    } else {
                        providerNotice = "Finish signing in in your browser to connect Spotify."
                    }
                }
            },
            onDisconnect = { providerAction { container.disconnectSpotify() } },
            busy = providerBusy || !ready,
            detail = "Connect your Spotify account for search, liked songs, playlists, and playback controls. Spotify API rate limits can temporarily block these features.",
        )
        if (requiresExplicitSpotifyDevice()) {
            Text("Spotify output", style = MaterialTheme.typography.titleMedium)
            Text(
                settings.spotifyPlaybackDeviceName?.let { "Selected: $it" }
                    ?: "No device selected. Pick where Spotify should play.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Kainos cannot play Spotify audio itself on Android. It tells Spotify which Connect device to use. " +
                    "Open Spotify on this phone for phone speakers, or leave Spotify closed and pick a computer, speaker, " +
                    "or another phone that is already online. Sound only comes from the device you select.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(enabled = ready && !providerBusy, onClick = {
                providerAction {
                    if (!ensureSpotifyConnectClientAvailable()) {
                        error("Install the Spotify app to use this phone as a Connect device, or pick another online device below.")
                    }
                    providerNotice = "Spotify opened. Sign in if needed, then refresh devices and select this phone."
                }
            }) { Text("Open Spotify app") }
            OutlinedButton(
                enabled = ready && !providerBusy && (spotify == ProviderState.AVAILABLE || spotify == ProviderState.RATE_LIMITED),
                onClick = {
                    providerAction {
                        spotifyDevices = container.spotify.getConnectDevices()
                        spotifyDevicesLoaded = true
                        val selectedId = container.settings.value.spotifyPlaybackDeviceId
                        if (selectedId != null && spotifyDevices.none { it.id == selectedId }) {
                            container.updateSettings {
                                it.copy(spotifyPlaybackDeviceId = null, spotifyPlaybackDeviceName = null)
                            }
                            providerNotice = "Previous Spotify device went offline. Select another device below."
                        } else {
                            providerNotice = when {
                                spotifyDevices.isEmpty() -> "No Connect devices yet. Open Spotify somewhere, then refresh."
                                else -> "${spotifyDevices.size} Spotify device${if (spotifyDevices.size == 1) "" else "s"} found."
                            }
                        }
                    }
                },
            ) { Text("Refresh Spotify devices") }
            if (spotifyDevicesLoaded && spotifyDevices.isEmpty()) {
                Text(
                    "No Spotify devices are available. Open Spotify on this phone or another Premium device, then refresh.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            spotifyDevices.forEach { device ->
                val selected = settings.spotifyPlaybackDeviceId == device.id
                Row(
                    Modifier.fillMaxWidth().selectable(selected = selected, enabled = !providerBusy && !device.isRestricted) {
                        providerAction {
                            container.updateSettings { it.copy(
                                spotifyPlaybackDeviceId = device.id,
                                spotifyPlaybackDeviceName = device.name,
                            ) }
                            providerNotice = "Spotify will play on ${device.name}."
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = !device.isRestricted)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(device.name)
                        Text(
                            buildString {
                                append(device.type)
                                if (device.isActive) append(" · Active")
                                if (device.volumePercent == 0) append(" · Muted in Spotify")
                                if (device.isRestricted) append(" · Cannot be controlled")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (nativeSpotify) {
            OutlinedButton(enabled = ready && !providerBusy, onClick = {
                providerAction {
                    val receiver = container.spotifyWebPlayback.prepareAuthentication()
                    if (receiver != null) {
                        providerNotice =
                            "Receiver started. Complete Spotify sign-in if a browser page opens, then choose a track in Kainos."
                    } else {
                        val failure = (container.spotifyWebPlayback.state.value as? SpotifyWebPlaybackState.Failed)?.reason
                        providerError = failure?.let(::spotifyPlaybackFailureMessage)
                            ?: "Spotify receiver setup failed. Try setup again."
                    }
                }
            }) { Text("Set up in-app Spotify playback") }
            Text(
                "Spotify Premium playback runs in the background through librespot. Sign in once in your browser; later playback uses the saved login.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val failure = (spotifyPlayback as? SpotifyWebPlaybackState.Failed)?.reason
            if (failure != null) {
                Text(
                    spotifyPlaybackFailureMessage(failure),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (spotify == ProviderState.AVAILABLE || spotify == ProviderState.RATE_LIMITED) {
            var discoverPlaylistInput by remember(settings.spotifyDiscoverWeeklyPlaylistId, ready) {
                mutableStateOf(settings.spotifyDiscoverWeeklyPlaylistId.orEmpty())
            }
            OutlinedTextField(
                value = discoverPlaylistInput,
                onValueChange = { discoverPlaylistInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Discover Weekly playlist link") },
                placeholder = { Text("Paste Spotify share URL or playlist id") },
            )
            OutlinedButton(
                enabled = !providerBusy,
                onClick = {
                    scope.launch {
                        container.updateSettings {
                            it.copy(spotifyDiscoverWeeklyPlaylistId = discoverPlaylistInput.trim().ifEmpty { null })
                        }
                        container.refreshSpotifyLibrary()
                        providerNotice = "Saved Discover Weekly link and refreshed."
                    }
                },
            ) { Text("Save Discover Weekly link") }
            Text(
                "Spotify’s Web API no longer lists Discover Weekly for most developer apps. " +
                    "In Spotify: open Discover Weekly → Share → Copy link, paste it here, then save. " +
                    "Or heart Discover Weekly in Spotify so it appears in your library, then refresh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(enabled = !libraryLoading && !providerBusy, onClick = {
                scope.launch { container.refreshSpotifyLibrary() }
            }) { Text(if (libraryLoading) "Loading library…" else "Refresh Spotify library") }
            Text(
                "If Spotify reports a rate limit, wait for its retry time before refreshing again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        libraryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ProviderAccountRow(
            name = "YouTube Music",
            state = youtube,
            configured = container.config.hasYouTubeCredentials,
            connected = youtube == ProviderState.AVAILABLE,
            onConnect = {},
            onDisconnect = {},
            showButtons = false,
            detail = "Search videos and playlists via the YouTube Data API. Linux desktop resolves audio with yt-dlp into mpv when installed. Android resolves audio with NewPipe Extractor into the in-app ExoPlayer service.",
        )

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Light / dark", style = MaterialTheme.typography.labelLarge)
        ThemeMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(settings.themeMode == mode) {
                        scope.launch { container.updateSettings { it.copy(themeMode = mode) } }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.themeMode == mode, onClick = {
                    scope.launch { container.updateSettings { it.copy(themeMode = mode) } }
                })
                Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text("Color scheme", style = MaterialTheme.typography.labelLarge)
        Text(
            "Palettes from Caelestia shell schemes. Light and dark variants both update when you change mode above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppColorScheme.entries.forEach { scheme ->
            val selected = settings.colorScheme == scheme
            val previewDark = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val preview = colorSchemeFor(scheme, previewDark)
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected) {
                        scope.launch { container.updateSettings { it.copy(colorScheme = scheme) } }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = {
                    scope.launch { container.updateSettings { it.copy(colorScheme = scheme) } }
                })
                Row(
                    Modifier.padding(start = 4.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(preview.primary, preview.secondary, preview.tertiary, preview.surfaceContainer).forEach { swatch ->
                        Box(
                            Modifier
                                .size(18.dp)
                                .background(swatch, RoundedCornerShape(4.dp)),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(scheme.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        scheme.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text("Advanced", style = MaterialTheme.typography.titleMedium)
        Text("Platform: ${platformLabel()}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Metadata cache stores titles and artwork only. Hearted YouTube tracks (and Spotify hearts via a YouTube match) download audio for offline play. Spotify DRM audio is never stored. App favorites are not written back to Spotify Liked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var cacheNotice by remember { mutableStateOf<String?>(null) }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val stats = container.clearMetadataArtworkCache()
                    cacheNotice =
                        "Cleared metadata/artwork cache (${stats.entryCount} entries, ${stats.artworkFileCount} images)."
                }
            },
        ) {
            Text("Clear metadata & artwork cache")
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val stats = container.clearHeartedAudioCache()
                    cacheNotice =
                        "Cleared hearted audio cache (${stats.entryCount} files, ${stats.bytesUsed / (1024 * 1024)} MiB)."
                }
            },
        ) {
            Text("Clear hearted audio cache")
        }
        cacheNotice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProviderAccountRow(
    name: String,
    state: ProviderState,
    configured: Boolean,
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    detail: String,
    showButtons: Boolean = true,
    busy: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (connected) "●" else "○", color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Text("  $name", style = MaterialTheme.typography.titleSmall)
            Text(
                "  ${state.name.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showButtons) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = configured && !connected && !busy) { Text("Connect") }
                OutlinedButton(onClick = onDisconnect, enabled = connected && !busy) { Text("Disconnect") }
            }
        }
    }
}

private fun spotifyPlaybackFailureMessage(failure: SpotifyWebPlaybackFailure): String = when (failure) {
    SpotifyWebPlaybackFailure.LibrespotNotFound -> "Install librespot with scripts/install-librespot.sh, then try setup again."
    SpotifyWebPlaybackFailure.LibrespotAuthenticationRequired ->
        "Set up in-app Spotify playback to sign in to the receiver."
    is SpotifyWebPlaybackFailure.LibrespotExited -> {
        val detail = failure.detail.orEmpty()
        describeLibrespotConnectionFailure(detail)?.let { return it }
        when {
            detail.contains("GeneratedMessageV3", ignoreCase = true) ||
                detail.contains("protobuf", ignoreCase = true) ->
                "Spotify setup failed: a required library is missing from this build. Reinstall the app."
            detail.isNotBlank() -> detail
            else -> "The Spotify receiver stopped. Try setup again."
        }
    }
    is SpotifyWebPlaybackFailure.Message -> failure.detail
    else -> "Spotify receiver setup failed. Try setup again."
}

private fun SourceSelectionMode.label(): String = when (this) {
    SourceSelectionMode.AUTOMATIC -> "Automatic — Best available"
    SourceSelectionMode.PREFER_LOSSLESS -> "Prefer lossless"
    SourceSelectionMode.PREFER_HIGHEST_BITRATE -> "Prefer highest bitrate"
    SourceSelectionMode.PREFER_SPOTIFY -> "Prefer Spotify"
    SourceSelectionMode.PREFER_YOUTUBE_MUSIC -> "Prefer YouTube Music"
    SourceSelectionMode.FORCE_SPOTIFY -> "Spotify only"
    SourceSelectionMode.FORCE_YOUTUBE_MUSIC -> "YouTube Music only"
}
