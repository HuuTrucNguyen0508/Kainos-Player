package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.reportsTextInputFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
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
    val trust = syncUi.trust
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
            "Pairing and everyday sync stay under Devices and sync. These controls remove files from the shared vault.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var tombstonePath by remember { mutableStateOf("") }
        trust.hub?.let { peer ->
            SelectionContainer {
                Text("PC certificate fingerprint: ${peer.fingerprint.hex}", style = MaterialTheme.typography.bodySmall)
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
