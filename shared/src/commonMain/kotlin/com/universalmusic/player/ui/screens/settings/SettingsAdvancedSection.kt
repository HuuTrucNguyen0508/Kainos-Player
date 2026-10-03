package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.reportsTextInputFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.platform.platformLabel

@Composable
internal fun SettingsAdvancedSection(presenter: SettingsAdvancedPresenter, syncPresenter: SettingsSyncPresenter) {
    val syncUi by syncPresenter.state.collectAsState()
    val syncStatus = syncUi.status
    val syncPairing = syncUi.pairing
    val syncBusy = syncUi.busy
    val syncNotice = syncUi.notice
    val ui by presenter.state.collectAsState()
    val cacheNotice = ui.cacheNotice
    val traceInfo = ui.traceInfo
    val traceNotice = ui.traceNotice
        Text("Advanced", style = MaterialTheme.typography.titleMedium)
        Text("Platform: ${platformLabel()}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Metadata cache stores titles and artwork only. Hearted YouTube tracks (and Spotify hearts via a YouTube match) download audio for offline play. Spotify DRM audio is never stored. App favorites are not written back to Spotify Liked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                presenter.clearMetadataCache()
            },
        ) {
            Text("Clear metadata & artwork cache")
        }
        OutlinedButton(
            onClick = {
                presenter.clearAudioCache()
            },
        ) {
            Text("Clear hearted audio cache")
        }
        cacheNotice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text("Playback log", style = MaterialTheme.typography.titleMedium)
        Text(
            "Records transport decisions, Media3 pause reasons, which controller sent a command, and Spotify receiver events. Share it after a track paused on its own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${traceInfo.location} (${traceInfo.sizeBytes / 1024} KiB)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (traceInfo.canShare) {
                OutlinedButton(
                    onClick = {
                        presenter.shareLog()
                    },
                ) {
                    Text("Share playback log")
                }
            }
            OutlinedButton(
                onClick = {
                    presenter.clearLog()
                },
            ) {
                Text("Clear")
            }
        }
        traceNotice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text("Home sync diagnostics", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pairing and everyday sync stay under Devices and sync. These fields are for repair and vault cleanup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var tombstonePath by remember { mutableStateOf("") }
        syncPairing?.sharedSecretHex?.let { secret ->
            SelectionContainer {
                Text(
                    "Shared secret:\n$secret",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "This device id: ${syncPairing?.deviceId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            syncPairing?.hubCertSha256Hex?.let { pin ->
                Text(
                    "Certificate fingerprint: $pin",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } ?: Text(
            "No home sync pairing on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (platformLabel() != "Linux") {
            var clientHost by remember { mutableStateOf(syncPairing?.hubHost.orEmpty()) }
            var clientSecret by remember { mutableStateOf("") }
            var clientPeerId by remember { mutableStateOf(syncPairing?.peerDeviceId.orEmpty()) }
            var clientPin by remember { mutableStateOf("") }
            var clientCert by remember { mutableStateOf(syncPairing?.hubCertSha256Hex.orEmpty()) }
            OutlinedTextField(
                value = clientHost,
                onValueChange = { clientHost = it },
                label = { Text("PC LAN IP / hostname") },
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                singleLine = true,
            )
            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it },
                label = { Text("Shared secret from PC") },
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                singleLine = true,
            )
            OutlinedTextField(
                value = clientPin,
                onValueChange = { clientPin = it },
                label = { Text("Pairing PIN from PC") },
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                singleLine = true,
            )
            OutlinedTextField(
                value = clientCert,
                onValueChange = { clientCert = it },
                label = { Text("Hub cert pin (64 hex from URI cert=)") },
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                singleLine = true,
            )
            OutlinedTextField(
                value = clientPeerId,
                onValueChange = { clientPeerId = it },
                label = { Text("PC device id (optional)") },
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                singleLine = true,
            )
            Button(
                enabled = !syncBusy &&
                    clientHost.isNotBlank() &&
                    clientSecret.isNotBlank() &&
                    clientCert.trim().length == 64,
                onClick = {
                    syncPresenter.pairManually(clientHost, clientSecret, clientPeerId, clientPin, clientCert)
                },
            ) {
                Text("Save phone pairing (manual)")
            }
        }
        OutlinedTextField(
            value = tombstonePath,
            onValueChange = { tombstonePath = it },
            label = { Text("File to remove (path inside the vault)") },
            modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
            singleLine = true,
        )
        OutlinedButton(
            enabled = !syncBusy && tombstonePath.isNotBlank(),
            onClick = {
                syncPresenter.removeVaultFile(tombstonePath)
                tombstonePath = ""
            },
        ) {
            Text("Remove file from shared vault")
        }
        Text(
            "Removes the file from the vault on this device and asks the other device to delete its copy the next time you sync.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        syncNotice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

}
