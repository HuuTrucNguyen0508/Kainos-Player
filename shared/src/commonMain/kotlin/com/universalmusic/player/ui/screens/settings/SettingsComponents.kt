package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.platform.SpotifyWebPlaybackFailure
import com.universalmusic.player.platform.describeLibrespotConnectionFailure

@Composable
internal fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun ProviderAccountRow(
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

internal fun spotifyPlaybackFailureMessage(failure: SpotifyWebPlaybackFailure): String = when (failure) {
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

internal enum class SettingsSection(val label: String) {
    Playback("Playback"),
    Appearance("Appearance"),
    Sources("Music sources"),
    Devices("Devices and sync"),
    Downloads("Downloads"),
    Advanced("Advanced"),
}

internal fun SourceSelectionMode.label(): String = when (this) {
    SourceSelectionMode.AUTOMATIC -> "Automatic — Best available"
    SourceSelectionMode.PREFER_LOSSLESS -> "Prefer lossless"
    SourceSelectionMode.PREFER_HIGHEST_BITRATE -> "Prefer highest bitrate"
    SourceSelectionMode.PREFER_SPOTIFY -> "Prefer Spotify"
    SourceSelectionMode.PREFER_YOUTUBE_MUSIC -> "Prefer YouTube Music"
    SourceSelectionMode.FORCE_SPOTIFY -> "Spotify only"
    SourceSelectionMode.FORCE_YOUTUBE_MUSIC -> "YouTube Music only"
}
