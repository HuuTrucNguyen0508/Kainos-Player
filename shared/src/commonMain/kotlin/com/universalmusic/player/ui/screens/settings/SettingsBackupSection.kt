package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.backup.BackupRestoreCounts
import com.universalmusic.player.ui.components.LedgerRow
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette

@Composable
internal fun SettingsBackupSection(presenter: SettingsBackupPresenter) {
    val ui by presenter.state.collectAsState()
    val stage = ui.stage
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionRule("Backup file", accent = SettingsSection.Backup.accent)
        SetupNote(
            "One file with your hearts, Kainos playlists, Home pins for playlists, and your appearance, playback and " +
                "Library preferences. It never holds sign-ins, keys, pairing, folder locations or audio.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = presenter::exportBackup, enabled = !ui.busy) {
                Text(if (stage == BackupStage.Exporting) "Exporting…" else "Export backup")
            }
            OutlinedButton(onClick = presenter::chooseFile, enabled = !ui.busy) {
                Text(if (stage == BackupStage.Reading) "Reading…" else "Restore from file")
            }
        }
        ui.notice?.let {
            Text(
                it,
                Modifier.testTag("backup-notice"),
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (stage) {
            is BackupStage.Review -> BackupManifest(
                title = "Restoring this file adds",
                subtitle = "Backup made ${utcDate(stage.file.createdAtMs)}. Nothing on this device is removed.",
                counts = stage.counts,
                restored = false,
                onConfirm = presenter::confirmRestore,
                onDismiss = presenter::dismiss,
            )
            BackupStage.Restoring -> Text("Restoring…", style = MaterialTheme.typography.bodyMedium)
            is BackupStage.Restored -> BackupManifest(
                title = "Restored",
                subtitle = "Nothing on this device was removed.",
                counts = stage.counts,
                restored = true,
                onConfirm = presenter::dismiss,
                onDismiss = null,
            )
            else -> Unit
        }
    }
}

/** What a restore adds, one ledger row per kind, before and after it runs. */
@Composable
private fun BackupManifest(
    title: String,
    subtitle: String,
    counts: BackupRestoreCounts,
    restored: Boolean,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    val requester = remember { BringIntoViewRequester() }
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(title) {
        requester.bringIntoView()
        runCatching { confirmFocus.requestFocus() }
    }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(requester).testTag("backup-manifest"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        SetupNote(subtitle)
        val playlistParts = listOf(
            counts.playlistsAdded to "new",
            counts.playlistsUpdated to "updated",
            counts.playlistsRestored to "brought back",
        ).filter { it.first > 0 }
        val kept = counts.playlistsKept.takeIf { it > 0 }?.let { "$it already up to date" }
        ManifestRow(
            Accents.heart, "Hearts",
            amount = counts.heartsAdded, amountLabel = if (restored) "added" else "new",
            detail = counts.heartsAlready.takeIf { it > 0 }?.let { "$it already hearted" },
        )
        ManifestRow(
            Accents.playlist, "Kainos playlists",
            amount = playlistParts.sumOf { it.first },
            amountLabel = if (playlistParts.size > 1) "changes" else playlistParts.singleOrNull()?.second ?: "new",
            detail = (if (playlistParts.size > 1) playlistParts.map { "${it.first} ${it.second}" } else emptyList())
                .plus(listOfNotNull(kept)).joinToString(", ").ifEmpty { null },
        )
        ManifestRow(
            Accents.home, "Home pins",
            amount = counts.pinsAdded, amountLabel = if (restored) "added" else "new",
            detail = counts.pinsAlready.takeIf { it > 0 }?.let { "$it already pinned" },
        )
        ManifestRow(
            AccentSlot.Gray, "Preferences",
            amount = counts.preferencesChanged, amountLabel = "changed",
            detail = null,
        )
        if (counts.spotifySkipped > 0) {
            SetupNote(
                "${countOf(counts.spotifySkipped, "Spotify heart or pin", "Spotify hearts and pins")} " +
                    "${if (restored) "were" else "will be"} left out: they belong to a Spotify account this device is not signed in to.",
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (restored) {
                OutlinedButton(onClick = onConfirm, modifier = Modifier.focusRequester(confirmFocus)) { Text("Done") }
            } else {
                Button(
                    onClick = onConfirm,
                    enabled = counts.changes > 0,
                    modifier = Modifier.focusRequester(confirmFocus),
                ) { Text(if (counts.changes > 0) "Restore" else "Nothing new to restore") }
                onDismiss?.let { OutlinedButton(onClick = it) { Text("Cancel") } }
            }
        }
    }
}

@Composable
private fun ManifestRow(accent: AccentSlot, label: String, amount: Int, amountLabel: String, detail: String?) {
    val palette = LocalKainosPalette.current
    val surface = MaterialTheme.colorScheme.surface
    LedgerRow {
        Box(Modifier.size(10.dp).clip(CircleShape).background(palette.accent(accent, AccentUse.Mark, surface)))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "$amount",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (amount > 0) palette.accent(accent, AccentUse.Text, surface)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(amountLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End)
            }
        }
    }
}
