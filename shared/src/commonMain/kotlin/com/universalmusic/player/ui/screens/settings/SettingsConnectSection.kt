package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.reportsTextInputFocus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.platform.SpotifyWebPlaybackState
import com.universalmusic.player.platform.requiresExplicitSpotifyDevice

@Composable
internal fun SettingsConnectSection(presenter: SettingsConnectPresenter) {
    val ui by presenter.state.collectAsState()
    val settings = ui.settings
    val ready = ui.ready
    val spotify = ui.spotify
    val youtube = ui.youtube
    val spotifyPlayback = ui.playback
    val nativeSpotify = presenter.nativeSpotify
    val spotifyDevices = ui.action.devices
    val spotifyDevicesLoaded = ui.action.devicesLoaded
    val providerBusy = ui.action.busy
    val providerError = ui.action.error
    val providerNotice = ui.action.notice
    val libraryLoading = ui.libraryLoading
    val libraryError = ui.libraryError
    var spotifyClientId by remember(settings.spotifyClientId, ready) { mutableStateOf(ui.config.spotifyClientId.orEmpty()) }
    var youtubeApiKey by remember(settings.youtubeDataApiKey, ready) { mutableStateOf(ui.config.youtubeDataApiKey.orEmpty()) }
        Text("Provider setup", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = spotifyClientId,
            onValueChange = { spotifyClientId = it },
            label = { Text("Spotify Client ID") },
            singleLine = true,
            enabled = ready && !providerBusy,
            modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
        )
        Text("Register this Spotify redirect URI: ${ui.config.spotifyRedirectUri}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = youtubeApiKey,
            onValueChange = { youtubeApiKey = it },
            label = { Text("YouTube Data API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = ready && !providerBusy,
            modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
        )
        Text("Enable YouTube Data API v3 in your Google Cloud project. These values are saved on this device. Empty fields use secrets.properties or environment defaults.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = ready && !providerBusy, onClick = {
            presenter.saveCredentials(spotifyClientId, youtubeApiKey)
        }) { Text(if (providerBusy) "Working…" else "Save provider settings") }
        providerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        providerNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ProviderAccountRow(
            name = "Spotify",
            state = spotify,
            configured = ui.config.hasSpotifyCredentials,
            connected = spotify == ProviderState.AVAILABLE || spotify == ProviderState.RATE_LIMITED,
            onConnect = {
                presenter.connect()
            },
            onDisconnect = { presenter.disconnect() },
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
                presenter.openSpotify()
            }) { Text("Open Spotify app") }
            OutlinedButton(
                enabled = ready && !providerBusy && (spotify == ProviderState.AVAILABLE || spotify == ProviderState.RATE_LIMITED),
                onClick = {
                    presenter.refreshDevices()
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
                        presenter.selectDevice(device)
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
                presenter.setupReceiver()
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
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                singleLine = true,
                label = { Text("Discover Weekly playlist link") },
                placeholder = { Text("Paste Spotify share URL or playlist id") },
            )
            OutlinedButton(
                enabled = !providerBusy,
                onClick = {
                    presenter.saveDiscoverWeekly(discoverPlaylistInput)
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
                presenter.refreshLibrary()
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
            configured = ui.config.hasYouTubeCredentials,
            connected = youtube == ProviderState.AVAILABLE,
            onConnect = {},
            onDisconnect = {},
            showButtons = false,
            detail = "Search videos and playlists via the YouTube Data API. Linux desktop resolves audio with yt-dlp into mpv when installed. Android resolves audio with NewPipe Extractor into the in-app ExoPlayer service.",
        )


}
