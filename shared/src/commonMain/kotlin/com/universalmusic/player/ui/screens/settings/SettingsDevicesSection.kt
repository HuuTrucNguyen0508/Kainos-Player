package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TriStateCheckbox
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
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import com.universalmusic.player.data.sync.PendingCopyKey
import com.universalmusic.player.data.sync.SkippedCopy
import com.universalmusic.player.data.sync.key
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.ui.PlatformBackHandler

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsDevicesSection(
    preferences: SettingsPreferencesPresenter,
    syncPresenter: SettingsSyncPresenter,
    /** False while the section sits off screen on the board: the pairing code stops refreshing until it is seen. */
    visible: Boolean = true,
) {
    DisposableEffect(syncPresenter) { onDispose { syncPresenter.dispose() } }
    val settings by preferences.state.collectAsState()
    val syncUi by syncPresenter.state.collectAsState()
    val syncStatus = syncUi.status
    val syncBusy = syncUi.busy
    val syncNotice = syncUi.notice
    val pairingLink by remember(visible) { if (visible) syncPresenter.pairingLink else MutableStateFlow(null) }.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    PlatformBackHandler(enabled = scanning) { scanning = false }
    Text("Devices and sync", style = MaterialTheme.typography.titleMedium)
    val comparingCode = syncUi.incoming != null || syncUi.pairing is PairingUiState.Confirming ||
        syncUi.pairing is PairingUiState.WaitingForPc
    if (!comparingCode) {
        Text(
            "Pair your phone with a PC on the same Wi-Fi. Compare the code on both screens, then confirm on each device. YouTube and local-file hearts sync both ways, and so do Spotify hearts when both devices use the same Spotify account. Playlist pins on Home follow, with their order; album and folder pins stay on each device. Audio transfers wait for your confirmation.",
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
        is PairingUiState.Found -> {
            Text(
                if (pairing.scanned) "Is this your PC? Tap it to start pairing." else "Tap your PC to start pairing.",
                style = MaterialTheme.typography.bodyMedium,
            )
            pairing.hubs.forEach { hub ->
                OutlinedButton(onClick = { syncPresenter.pair(hub) }) {
                    Column {
                        Text(hub.name)
                        Text(hub.endpoint.authority, style = MaterialTheme.typography.bodySmall)
                        Text(hub.fp.chunked(16).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                    }
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
    val canStartPairing = platformLabel() != "Linux" && (syncUi.pairing is PairingUiState.Idle ||
        syncUi.pairing is PairingUiState.Failed || syncUi.pairing is PairingUiState.Paired)
    if (canStartPairing && scanning) {
        Text("Point the camera at the code in Kainos on your PC, under Devices and sync.")
        PairingScanWindow(
            onScanned = { raw ->
                scanning = false
                syncPresenter.scanned(raw)
            },
            onUnavailable = { reason ->
                scanning = false
                syncPresenter.scanUnavailable(reason)
            },
        )
        OutlinedButton(onClick = { scanning = false }) { Text("Cancel") }
    } else if (canStartPairing) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !syncBusy, onClick = { syncPresenter.search() }) { Text("Pair with a PC") }
            if (canScanPairingCode) {
                OutlinedButton(enabled = !syncBusy, onClick = { scanning = true }) { Text("Scan pairing code") }
            }
        }
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
    pairingLink?.takeUnless { comparingCode }?.let { link ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PairingQrCode(link)
            Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pair a phone with this code", style = MaterialTheme.typography.titleSmall)
                Text(
                    "On your phone, open Devices and sync in Kainos and tap Scan pairing code. You will still compare a 6-digit code on both screens before they pair.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                "Only sync local files that are app-hearted (by filename). YouTube hearts still sync as metadata.",
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
    // Vault files and offline copies share one list; Transfer and Skip act on the ticked rows, or on all.
    val pendingRows = syncStatus.pendingTransfers.map { CopyRow(it.key, it.label, it.sizeBytes) } +
        syncStatus.pendingCachedAudio.map { CopyRow(it.key, it.label, it.offer.sizeBytes) }
    if (pendingRows.isNotEmpty()) {
        val pendingCount = pendingRows.size
        val pendingMiB = pendingRows.sumOf { it.sizeBytes } / (1024 * 1024)
        Text(
            "$pendingCount ${if (pendingCount == 1) "file" else "files"} to copy" +
                (if (pendingMiB > 0) " ($pendingMiB MiB)" else "") +
                ". Confirm to copy missing hearted files and offline copies.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        SelectableCopies(
            rows = pendingRows,
            enabled = !syncBusy,
            shownAtFirst = 8,
            actions = listOf(
                CopyAction("Transfer", primary = true) { keys: Set<PendingCopyKey> -> syncPresenter.transferCopies(keys, confirm = true) },
                CopyAction("Skip") { keys: Set<PendingCopyKey> -> syncPresenter.transferCopies(keys, confirm = false) },
            ),
        )
    }
    if (syncStatus.skippedCopies.isNotEmpty()) {
        SkippedCopies(syncStatus.skippedCopies, enabled = !syncBusy, onUnskip = syncPresenter::unskip)
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
    listOfNotNull(syncStatus.hubAutostartError, syncStatus.lastError).distinct().forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    syncNotice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

}

/** Skipped copies stay folded away; any of them can be offered again. */
@Composable
private fun SkippedCopies(
    copies: List<SkippedCopy>,
    enabled: Boolean,
    onUnskip: (List<SkippedCopy>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Skipped (${copies.size})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
    }
    if (!expanded) return
    Text(
        "Not offered again until the file changes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectableCopies(
        rows = copies.map { CopyRow(it, it.label, it.sizeBytes) },
        enabled = enabled,
        shownAtFirst = 20,
        actions = listOf(CopyAction("Un-skip") { picked: Set<SkippedCopy> -> onUnskip(picked.toList()) }),
    )
}

internal class CopyRow<K>(val key: K, val label: String, val sizeBytes: Long)

/** A button under a [SelectableCopies] list; it acts on the ticked rows, or on every row when none is ticked. */
internal class CopyAction<K>(val verb: String, val primary: Boolean = false, val run: (Set<K>) -> Unit)

/**
 * A list of copies with a checkbox per row and a Select all box. The [actions] read "Transfer all"
 * until rows are ticked, then "Transfer 3", so one tap handles any number of files.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <K> SelectableCopies(
    rows: List<CopyRow<K>>,
    enabled: Boolean,
    shownAtFirst: Int,
    actions: List<CopyAction<K>>,
) {
    var ticked by remember { mutableStateOf(emptySet<K>()) }
    var showAll by remember { mutableStateOf(false) }
    val keys = rows.mapTo(LinkedHashSet()) { it.key }
    // Rows leave the list as copies finish or other screens skip them; a gone row is no longer ticked.
    val selected = ticked intersect keys
    if (rows.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).toggleable(
                value = selected.size == keys.size,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { ticked = if (selected.size == keys.size) emptySet() else keys },
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(
                state = when (selected.size) {
                    0 -> ToggleableState.Off
                    keys.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onClick = null,
                enabled = enabled,
            )
            Text(
                if (selected.isEmpty()) "Select all" else "${selected.size} of ${keys.size} selected",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            val label = when {
                selected.isNotEmpty() -> "${action.verb} ${selected.size}"
                rows.size == 1 -> action.verb
                else -> "${action.verb} all"
            }
            val onClick = {
                action.run(selected.ifEmpty { keys })
                ticked = emptySet()
            }
            if (action.primary) Button(enabled = enabled, onClick = onClick) { Text(label) }
            else OutlinedButton(enabled = enabled, onClick = onClick) { Text(label) }
        }
    }
    val shown = if (showAll) rows else rows.take(shownAtFirst)
    shown.forEach { row ->
        val checked = row.key in selected
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { on -> ticked = if (on) selected + row.key else selected - row.key },
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Text(
                row.label + if (row.sizeBytes > 0) " (${formatCopySize(row.sizeBytes)})" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (rows.size > shownAtFirst) {
        TextButton(onClick = { showAll = !showAll }) {
            Text(if (showAll) "Show fewer" else "Show ${rows.size - shownAtFirst} more")
        }
    }
}

private fun formatCopySize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MiB"
    else -> "${bytes / 1024} KiB"
}
