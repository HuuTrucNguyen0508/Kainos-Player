package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.spotify.SpotifyConnectDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
internal fun NowPlayingSpotifyOutput(
    visible: Boolean,
    scope: CoroutineScope,
    settings: StateFlow<AppSettings>,
    scheme: ColorScheme,
    getConnectDevices: suspend () -> List<SpotifyConnectDevice>,
    updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
) {
    var spotifyDevices by remember { mutableStateOf<List<SpotifyConnectDevice>>(emptyList()) }
    var spotifyDevicesLoaded by remember { mutableStateOf(false) }
    var spotifyDeviceBusy by remember { mutableStateOf(false) }
    var spotifyDeviceNotice by remember { mutableStateOf<String?>(null) }
    val current by settings.collectAsState()
    if (!visible) return

    Spacer(Modifier.height(16.dp))
    Text("Spotify output", style = MaterialTheme.typography.titleSmall)
    Text(
        current.spotifyPlaybackDeviceName?.let { "Playing through: $it" }
            ?: "Pick a Connect device. Sound comes from that device, not from Kainos.",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
    OutlinedButton(
        enabled = !spotifyDeviceBusy,
        onClick = {
            spotifyDeviceBusy = true
            spotifyDeviceNotice = null
            scope.launch {
                try {
                    spotifyDevices = getConnectDevices()
                    spotifyDevicesLoaded = true
                    val selectedId = settings.value.spotifyPlaybackDeviceId
                    if (selectedId != null && spotifyDevices.none { it.id == selectedId }) {
                        updateSettings {
                            it.copy(
                                spotifyPlaybackDeviceId = null,
                                spotifyPlaybackDeviceName = null,
                            )
                        }
                        spotifyDeviceNotice = "Previous device went offline. Select another below."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    spotifyDeviceNotice = failure.message ?: "Could not load Spotify devices."
                } finally {
                    spotifyDeviceBusy = false
                }
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(if (spotifyDeviceBusy) "Refreshing…" else "Refresh devices")
    }
    if (spotifyDevicesLoaded && spotifyDevices.isEmpty()) {
        Text(
            "No devices online. Open Spotify on this phone or another Premium device, then refresh.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    spotifyDevices.forEach { device ->
        val selected = current.spotifyPlaybackDeviceId == device.id
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = !spotifyDeviceBusy && !device.isRestricted,
                ) {
                    spotifyDeviceBusy = true
                    spotifyDeviceNotice = null
                    scope.launch {
                        try {
                            updateSettings {
                                it.copy(
                                    spotifyPlaybackDeviceId = device.id,
                                    spotifyPlaybackDeviceName = device.name,
                                )
                            }
                            spotifyDeviceNotice = "Spotify will play on ${device.name}. Press play again."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            spotifyDeviceNotice = failure.message ?: "Could not save device."
                        } finally {
                            spotifyDeviceBusy = false
                        }
                    }
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = !device.isRestricted,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(device.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(device.type)
                        if (device.isActive) append(" · Active")
                        if (device.volumePercent == 0) append(" · Muted")
                        if (device.isRestricted) append(" · Restricted")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
    spotifyDeviceNotice?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
