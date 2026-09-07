package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.universalmusic.player.data.settings.ThemeMode
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.platform.SpotifyWebPlaybackFailure
import com.universalmusic.player.platform.SpotifyWebPlaybackState
import com.universalmusic.player.platform.defaultLocalMusicFolder
import com.universalmusic.player.platform.authenticateSpotify
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.supportsMusicFolderPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val settings by container.settings.collectAsState()
    val spotify by container.spotify.state.collectAsState()
    val spotifyPlayback by container.spotifyWebPlayback.state.collectAsState()
    val nativeSpotify = !container.spotifyWebPlayback.requiresStreamingScope
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
            catch (failure: Exception) { providerError = failure.message ?: "Provider connection failed. Try again." }
            finally { providerBusy = false }
        }
    }
    val localFolders = settings.localMusicFolders.ifEmpty {
        listOfNotNull(defaultLocalMusicFolder().takeIf { it.isNotBlank() })
    }
    val usingDefaultFolder = settings.localMusicFolders.isEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .padding(bottom = 88.dp),
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

        Text("Providers", style = MaterialTheme.typography.titleMedium)
        ProviderAccountRow(
            name = "Local library",
            state = local,
            configured = true,
            connected = local == ProviderState.AVAILABLE,
            onConnect = container::refreshLocalLibrary,
            onDisconnect = {},
            showButtons = false,
            detail = if (supportsMusicFolderPicker()) {
                "${localTracks.size} tracks in your music folders."
            } else {
                "${localTracks.size} tracks. Android reads the device media library after permission is granted."
            },
        )
        if (supportsMusicFolderPicker()) {
            Text("Music folders", style = MaterialTheme.typography.labelLarge)
            if (usingDefaultFolder) {
                Text(
                    "Using the default Music folder until you add or change folders.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        enabled = !usingDefaultFolder,
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
        if (nativeSpotify) {
            OutlinedButton(enabled = ready && !providerBusy, onClick = {
                providerAction {
                    val receiver = container.spotifyWebPlayback.prepareAuthentication()
                    if (receiver != null) {
                        providerNotice = "Receiver started. Complete Spotify sign-in if a browser page opens, then choose a track in Kainos."
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
                    when (failure) {
                        SpotifyWebPlaybackFailure.LibrespotNotFound -> "Install librespot with scripts/install-librespot.sh, then try setup again."
                        SpotifyWebPlaybackFailure.LibrespotAuthenticationRequired -> "Set up in-app Spotify playback to sign in to the receiver."
                        is SpotifyWebPlaybackFailure.LibrespotExited -> failure.detail ?: "The Spotify receiver stopped. Try setup again."
                        else -> "Spotify receiver setup failed. Try setup again."
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (spotify == ProviderState.AVAILABLE || spotify == ProviderState.RATE_LIMITED) {
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
            detail = "Search videos and playlists via the YouTube Data API. On Linux desktop, playback uses yt-dlp to resolve an audio URL for in-app mpv when yt-dlp is installed. Android still opens YouTube in the browser.",
        )

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
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
        SettingToggle("Include sample catalog in search", settings.sampleCatalogEnabled) {
            scope.launch { container.updateSettings { current -> current.copy(sampleCatalogEnabled = it) } }
        }

        Text("Advanced", style = MaterialTheme.typography.titleMedium)
        Text("Platform: ${platformLabel()}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Cache is metadata-only. Protected audio streams are never stored. App favorites are not written back to Spotify Liked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

private fun SourceSelectionMode.label(): String = when (this) {
    SourceSelectionMode.AUTOMATIC -> "Automatic — Best available"
    SourceSelectionMode.PREFER_LOSSLESS -> "Prefer lossless"
    SourceSelectionMode.PREFER_HIGHEST_BITRATE -> "Prefer highest bitrate"
    SourceSelectionMode.PREFER_SPOTIFY -> "Prefer Spotify"
    SourceSelectionMode.PREFER_YOUTUBE_MUSIC -> "Prefer YouTube Music"
    SourceSelectionMode.FORCE_SPOTIFY -> "Spotify only"
    SourceSelectionMode.FORCE_YOUTUBE_MUSIC -> "YouTube Music only"
}

@Suppress("unused")
private val unusedProvider = ProviderId.SAMPLE
