package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.platform.platformLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsDevicesSection(preferences: SettingsPreferencesPresenter, syncPresenter: SettingsSyncPresenter) {
    DisposableEffect(syncPresenter) { onDispose { syncPresenter.dispose() } }
    val settings by preferences.state.collectAsState()
    val syncUi by syncPresenter.state.collectAsState()
    val syncStatus = syncUi.status
    val syncBusy = syncUi.busy
    val syncNotice = syncUi.notice
    Text("Devices and sync", style = MaterialTheme.typography.titleMedium)
    val comparingCode = syncUi.incoming != null || syncUi.pairing is PairingUiState.Confirming ||
        syncUi.pairing is PairingUiState.WaitingForPc
    if (!comparingCode) {
        Text(
            "Pair your phone with a PC on the same Wi-Fi. Compare the code on both screens, then confirm on each device. YouTube and local-file hearts sync both ways. Audio transfers wait for your confirmation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        if (syncUi.trust.repairNeeded) {
            Text("Pair again to keep syncing", color = MaterialTheme.colorScheme.error)
        }
    }
    syncUi.incoming?.let { incoming ->
        Text("${incoming.peerName} wants to pair", style = MaterialTheme.typography.titleMedium)
        incoming.code?.let { code ->
            Text(code.digits, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text("Confirm only if your phone shows the same code.")
        } ?: Text("Waiting for the phone's code.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !syncBusy && incoming.code != null && !incoming.pcConfirmed,
                onClick = { syncPresenter.confirmIncoming(incoming.attemptId) }) {
                Text(if (incoming.pcConfirmed) "Confirmed" else "Confirm")
            }
            OutlinedButton(enabled = !syncBusy,
                onClick = { syncPresenter.rejectIncoming(incoming.attemptId) }) { Text("Reject") }
        }
    }

    when (val pairing = syncUi.pairing) {
        PairingUiState.Idle -> Unit
        PairingUiState.Searching -> Text("Searching for PCs on your Wi-Fi…")
        is PairingUiState.Found -> pairing.hubs.forEach { hub ->
            OutlinedButton(onClick = { syncPresenter.pair(hub) }) {
                Column {
                    Text(hub.name)
                    Text(hub.fp.chunked(16).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is PairingUiState.Confirming -> {
            Text(pairing.hub.name, style = MaterialTheme.typography.titleMedium)
            Text(pairing.code.digits, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text("Does this code match the one on your PC?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { syncPresenter.confirm() }) { Text("Confirm") }
                OutlinedButton(onClick = { syncPresenter.cancelPairing() }) { Text("Cancel") }
            }
        }
        is PairingUiState.WaitingForPc -> {
            Text(pairing.code.digits, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text("Confirm on ${pairing.hub.name} to finish pairing.")
            OutlinedButton(onClick = { syncPresenter.cancelPairing() }) { Text("Cancel") }
        }
        is PairingUiState.Paired -> Text("Paired with ${pairing.peer.displayName}", color = MaterialTheme.colorScheme.primary)
        is PairingUiState.Failed -> Text(pairing.reason, color = MaterialTheme.colorScheme.error)
    }
    if (syncUi.pairing is PairingUiState.Searching || syncUi.pairing is PairingUiState.Found) {
        OutlinedButton(onClick = { syncPresenter.cancelPairing() }) { Text("Cancel") }
    }
    if (platformLabel() != "Linux" && (syncUi.pairing is PairingUiState.Idle ||
            syncUi.pairing is PairingUiState.Failed || syncUi.pairing is PairingUiState.Paired)) {
        Button(enabled = !syncBusy, onClick = { syncPresenter.search() }) { Text("Pair with a PC") }
    }
    (listOfNotNull(syncUi.trust.hub) + syncUi.trust.peers).forEach { peer ->
        Text(peer.displayName, style = MaterialTheme.typography.titleSmall)
        OutlinedButton(enabled = !syncBusy, onClick = { syncPresenter.removeDevice(peer.deviceId) }) {
            Text("Remove device")
        }
    }
    if (syncStatus.hubListening) {
        Text("This PC is ready for pairing and sync", color = MaterialTheme.colorScheme.primary)
    }
    Button(
        enabled = !syncBusy && syncStatus.enabled &&
            (syncUi.trust.hub != null || syncStatus.hubListening),
        onClick = { syncPresenter.syncNow() },
    ) { Text("Sync now") }
    var confirmReset by remember { mutableStateOf(false) }
    val resetLabel = if (platformLabel() == "Linux") "Reset this PC's identity" else "Reset this phone's identity"
    OutlinedButton(enabled = !syncBusy, onClick = { confirmReset = true }) { Text(resetLabel) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(resetLabel) },
            text = { Text("This device will forget all paired devices. Pair again on both screens to keep syncing.") },
            confirmButton = { TextButton(onClick = {
                confirmReset = false
                syncPresenter.resetIdentity()
            }) { Text("Reset identity") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    Text("Vault folder", style = MaterialTheme.typography.bodyLarge)
    Text(
        settings.homeLanSyncVaultFolder?.ifBlank { null } ?: "Not set. Pick a writable music folder.",
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Start hub at login", modifier = Modifier.weight(1f))
            Switch(checked = settings.homeLanSyncHubAutostart, onCheckedChange = { preferences.setHubAutostart(it) })
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
            "Transfers (${syncStatus.pendingTransfers.size}) confirm to copy missing hearted files",
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            "Conflicts (${syncStatus.pendingConflicts.size}) same path, different content",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        syncStatus.pendingConflicts.take(5).forEach { conflict ->
            Text(conflict.relPath, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
