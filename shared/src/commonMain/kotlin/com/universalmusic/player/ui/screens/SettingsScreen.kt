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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.settings.ThemeMode
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.platform.defaultLocalMusicFolder
import com.universalmusic.player.platform.listenForOAuthRedirect
import com.universalmusic.player.platform.openUrl
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.supportsMusicFolderPicker
import com.universalmusic.player.platform.usesLocalOAuthListener
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val settings by container.settings.collectAsState()
    val spotify by container.spotify.state.collectAsState()
    val youtube by container.youtube.state.collectAsState()
    val soundcloud by container.soundcloud.state.collectAsState()
    val local by container.local.state.collectAsState()
    val localTracks by container.local.libraryTracks.collectAsState()
    val scope = rememberCoroutineScope()
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
        ProviderAccountRow(
            name = "Spotify",
            state = spotify,
            configured = container.config.hasSpotifyCredentials,
            connected = spotify == ProviderState.AVAILABLE,
            onConnect = {
                scope.launch {
                    val session = container.spotify.beginLogin()
                    openUrl(session.authorizationUrl)
                    if (usesLocalOAuthListener()) {
                        val redirect = listenForOAuthRedirect(43821)
                        container.spotify.completeLogin(redirect)
                    }
                }
            },
            onDisconnect = { scope.launch { container.spotify.logout() } },
            detail = if (!container.config.hasSpotifyCredentials) {
                "Set SPOTIFY_CLIENT_ID in secrets.properties. Official OAuth + Web API only. Playback uses Spotify Connect on a Premium device."
            } else {
                "Spotify integration is in progress. Playback requires Spotify Premium and an active device in the Spotify app."
            },
        )
        ProviderAccountRow(
            name = "YouTube Music",
            state = youtube,
            configured = container.config.hasYouTubeCredentials,
            connected = youtube == ProviderState.AVAILABLE,
            onConnect = {},
            onDisconnect = {},
            showButtons = false,
            detail = "Search and track information only. YouTube Music playback and library sync are not available.",
        )
        ProviderAccountRow(
            name = "SoundCloud",
            state = soundcloud,
            configured = container.config.hasSoundCloudCredentials,
            connected = soundcloud == ProviderState.AVAILABLE,
            onConnect = {},
            onDisconnect = {},
            showButtons = false,
            detail = "Official SoundCloud API. Requires an approved client ID in SOUNDCLOUD_CLIENT_ID. Streams play only when the API returns a progressive URL.",
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
                Button(onClick = onConnect, enabled = configured && !connected) { Text("Connect") }
                OutlinedButton(onClick = onDisconnect, enabled = connected) { Text("Disconnect") }
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
    SourceSelectionMode.PREFER_SOUNDCLOUD -> "Prefer SoundCloud"
    SourceSelectionMode.FORCE_SPOTIFY -> "Spotify only"
    SourceSelectionMode.FORCE_YOUTUBE_MUSIC -> "YouTube Music only"
    SourceSelectionMode.FORCE_SOUNDCLOUD -> "SoundCloud only"
}

@Suppress("unused")
private val unusedProvider = ProviderId.SAMPLE
