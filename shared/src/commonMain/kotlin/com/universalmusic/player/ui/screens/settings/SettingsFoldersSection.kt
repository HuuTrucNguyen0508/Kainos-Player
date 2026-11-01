package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.supportsMusicFolderPicker

@Composable
internal fun SettingsFoldersSection(
    presenter: SettingsFoldersPresenter,
    readiness: SourceReadiness,
    onRecheck: () -> Unit,
) {
    val ui by presenter.state.collectAsState()
    val settings = ui.settings
    val local = ui.local
    val localFolders = ui.folders
    val localLibraryMessage = ui.message
    val pinnedFolders = ui.pinnedFolders
    val canPickFolders = supportsMusicFolderPicker()
    val addFolderFocus = remember { FocusRequester() }
    SectionRule(
        MusicSource.Local.title,
        accent = Accents.local,
        trailing = { SourceStatusMark(readiness) },
    )
    SettingsSpot(SettingsTarget.MusicFolders, focus = addFolderFocus) {
        SetupNote(readiness.detail)
        if (canPickFolders) {
            localLibraryMessage?.takeIf { local == ProviderState.UNAVAILABLE || ui.trackCount > 0 }?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (local == ProviderState.UNAVAILABLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            localFolders.forEach { folder ->
                val folderPinned = folder in pinnedFolders
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
                    IconButton(
                        onClick = {
                            presenter.togglePin(folder)
                        },
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = if (folderPinned) {
                                "Unpin folder from Home"
                            } else {
                                "Pin folder to Home"
                            },
                            tint = if (folderPinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { presenter.remove(folder) },
                    ) {
                        Text("Remove")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = presenter::addFolder, modifier = Modifier.focusRequester(addFolderFocus)) {
                    Text("Add folder")
                }
                OutlinedButton(onClick = presenter::refresh, enabled = local != ProviderState.LOADING) {
                    Text(if (local == ProviderState.LOADING) "Scanning…" else "Refresh")
                }
            }
            if (platformLabel() == "Android") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(settings.includeMediaStoreLibrary) {
                            presenter.setMediaStore(!settings.includeMediaStoreLibrary)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = settings.includeMediaStoreLibrary,
                        onCheckedChange = { presenter.setMediaStore(it) },
                    )
                    Text(
                        "Also include device MediaStore library (entire device music index). " +
                            "Turn this off when using picked folders.",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        } else {
            OutlinedButton(onClick = presenter::refresh, enabled = local != ProviderState.LOADING) {
                Text(if (local == ProviderState.LOADING) "Scanning…" else "Refresh local library")
            }
        }
    }
    val needs = readiness.needs
    if (SourceNeed.AudioPlayer in needs) {
        val recheckFocus = remember { FocusRequester() }
        SettingsSpot(SettingsTarget.AudioPlayer, focus = recheckFocus) {
            Text("Install mpv", style = MaterialTheme.typography.titleSmall)
            SetupNote(
                "Kainos plays local files and YouTube through mpv. Install it with your package manager, " +
                    "for example sudo pacman -S mpv or sudo apt install mpv.",
            )
            OutlinedButton(onClick = onRecheck, modifier = Modifier.focusRequester(recheckFocus)) { Text("Check again") }
        }
    }
}
