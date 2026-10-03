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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.platform.defaultLocalMusicFolder
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.supportsMusicFolderPicker

@Composable
internal fun SettingsFoldersSection(presenter: SettingsFoldersPresenter) {
    val ui by presenter.state.collectAsState()
    val settings = ui.settings
    val local = ui.local
    val localFolders = ui.folders
    val localLibraryMessage = ui.message
    val pinnedFolders = ui.pinnedFolders
    val usingDefaultFolder = !settings.localMusicFoldersConfigured
    val canPickFolders = supportsMusicFolderPicker()
        Text("Music sources", style = MaterialTheme.typography.titleMedium)
        ProviderAccountRow(
            name = "Local library",
            state = local,
            configured = true,
            connected = local == ProviderState.AVAILABLE,
            onConnect = presenter::refresh,
            onDisconnect = {},
            showButtons = false,
            detail = if (canPickFolders) {
                buildString {
                    append("${ui.trackCount} tracks in your music folders.")
                    if (usingDefaultFolder && defaultLocalMusicFolder().isNotBlank()) {
                        append(" Using the default Music folder until you add folders.")
                    } else if (settings.localMusicFoldersConfigured && settings.localMusicFolders.isEmpty()) {
                        append(" No folders selected — nothing is indexed from disk.")
                    }
                    localLibraryMessage?.let { append(" $it") }
                }
            } else {
                "${ui.trackCount} tracks."
            },
        )
        if (canPickFolders) {
            Text("Music folders", style = MaterialTheme.typography.labelLarge)
            if (usingDefaultFolder) {
                Text(
                    "Using the default Music folder until you add or change folders.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (localFolders.isEmpty()) {
                Text(
                    "No folders selected. Local files will not appear until you add a folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            localLibraryMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
                Button(onClick = presenter::addFolder) {
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
