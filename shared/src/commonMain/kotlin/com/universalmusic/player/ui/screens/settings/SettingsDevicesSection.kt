package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.reportsTextInputFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.universalmusic.player.platform.platformLabel

@Composable
internal fun SettingsDevicesSection(preferences: SettingsPreferencesPresenter, syncPresenter: SettingsSyncPresenter) {
    val settings by preferences.state.collectAsState()
    val syncUi by syncPresenter.state.collectAsState()
    val syncStatus = syncUi.status
    val syncPairing = syncUi.pairing
    val syncBusy = syncUi.busy
    val syncNotice = syncUi.notice
        Text("Devices and sync", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. On the computer, start hub pairing and copy the pairing link. 2. On the phone, paste that link. 3. Choose Sync now. Spotify hearts stay on the Spotify account. YouTube and local-file hearts sync both ways. Missing audio waits for you to confirm a transfer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val clipboard = LocalClipboardManager.current
        var clientPairingUri by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enable home sync", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = syncStatus.enabled,
                onCheckedChange = { enabled ->
                    syncPresenter.enable(enabled)
                },
            )
        }
        Text("Vault folder", style = MaterialTheme.typography.bodyLarge)
        Text(
            settings.homeLanSyncVaultFolder?.ifBlank { null } ?: "Not set — pick a writable music folder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Vault: hearted tracks only", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Only sync local files that are app-hearted (by filename). Spotify/YouTube hearts still sync as metadata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.homeLanSyncVaultHeartsOnly,
                onCheckedChange = { enabled ->
                    preferences.change { it.copy(homeLanSyncVaultHeartsOnly = enabled) }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { preferences.pickVaultFolder() }) {
                Text("Pick vault folder")
            }
            if (!settings.homeLanSyncVaultFolder.isNullOrBlank()) {
                OutlinedButton(onClick = { preferences.clearVaultFolder() }) {
                    Text("Clear vault")
                }
            }
        }
        if (platformLabel() == "Linux") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Start hub at login", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Writes ~/.config/autostart for kainos-player --hub-only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.homeLanSyncHubAutostart,
                    onCheckedChange = { preferences.setHubAutostart(it) },
                )
            }
            Button(
                enabled = !syncBusy,
                onClick = {
                    syncPresenter.beginPairing { clipboard.setText(AnnotatedString(it)) }
                },
            ) {
                Text(if (syncPairing == null) "Start hub pairing" else "Rotate hub pairing")
            }
            if (syncStatus.hubListening) {
                Text(
                    "HTTPS hub listening on port ${syncPairing?.hubPort ?: 43822}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            syncPairing?.pairingPin?.takeIf { it.isNotBlank() }?.let { pin ->
                Text("Pairing code", style = MaterialTheme.typography.titleSmall)
                Text(pin, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "On the phone, open Settings → Devices and sync and paste the pairing link.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (syncPairing?.hubCertSha256Hex.isNullOrBlank() && syncPairing != null) {
                    Text(
                        "This pairing predates HTTPS. Press Rotate hub pairing, then paste the new URI on the phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    val uri = syncUi.pairingUri
                    if (uri != null) {
                        OutlinedTextField(
                            value = uri,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Pairing URI") },
                            modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                            singleLine = false,
                        )
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(uri))
                                syncPresenter.setNotice("Pairing URI copied")
                            },
                        ) {
                            Text("Copy pairing URI")
                        }
                    }
                }
        } else {
            if (syncPairing != null && syncPairing?.hubCertSha256Hex.isNullOrBlank()) {
                Text(
                    "Saved pairing has no TLS cert pin. Paste a new kainos-homesync:2 URI from the PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = clientPairingUri,
                onValueChange = { clientPairingUri = it },
                label = { Text("Paste pairing URI from PC") },
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                singleLine = false,
            )
            Button(
                enabled = !syncBusy && clientPairingUri.isNotBlank(),
                onClick = {
                    syncPresenter.pair(clientPairingUri)
                },
            ) {
                Text("Pair from URI")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !syncBusy && syncStatus.enabled && syncPairing != null,
                onClick = {
                    syncPresenter.syncNow()
                },
            ) {
                Text("Sync now")
            }
            OutlinedButton(
                enabled = !syncBusy && syncPairing != null,
                onClick = {
                    syncPresenter.unpair()
                },
            ) {
                Text("Unpair")
            }
        }
        syncStatus.vaultProgress?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (syncStatus.bytesTotal > 0) {
                Text(
                    "${syncStatus.bytesTransferred / (1024 * 1024)} / ${syncStatus.bytesTotal / (1024 * 1024)} MiB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (syncStatus.pendingTransfers.isNotEmpty()) {
            Text(
                "Transfers (${syncStatus.pendingTransfers.size}) — confirm to copy missing hearted files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            syncStatus.pendingTransfers.take(8).forEach { transfer ->
                Text(
                    transfer.label + if (transfer.sizeBytes > 0) {
                        " (${transfer.sizeBytes / 1024} KiB)"
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !syncBusy,
                        onClick = {
                                    syncPresenter.transfer(transfer.relPath, transfer.direction, true)
                        },
                    ) { Text("Transfer") }
                    OutlinedButton(
                        enabled = !syncBusy,
                        onClick = {
                                    syncPresenter.transfer(transfer.relPath, transfer.direction, false)
                        },
                    ) { Text("Skip") }
                }
            }
        }
        if (syncStatus.pendingConflicts.isNotEmpty()) {
            Text(
                "Conflicts (${syncStatus.pendingConflicts.size}) — same path, different content",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            syncStatus.pendingConflicts.take(5).forEach { conflict ->
                Text(conflict.relPath, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !syncBusy,
                        onClick = {
                                    syncPresenter.resolve(conflict.relPath, true)
                        },
                    ) { Text("Keep local") }
                    OutlinedButton(
                        enabled = !syncBusy,
                        onClick = {
                                    syncPresenter.resolve(conflict.relPath, false)
                        },
                    ) { Text("Keep remote") }
                }
            }
        }
        syncStatus.lastDetail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        syncStatus.lastError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        syncNotice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

}
