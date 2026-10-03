package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.universalmusic.player.ui.reportsTextInputFocus

@Composable
internal fun LibraryPlaylistDialogs(
    createDraft: String?,
    onCreateDraft: (String?) -> Unit,
    renamePlaylistId: String?,
    renameDraft: String,
    onRenameDraft: (String) -> Unit,
    onRenamePlaylistId: (String?) -> Unit,
    deletePlaylistId: String?,
    expandedKainosId: String?,
    onExpandedKainosId: (String?) -> Unit,
    onDeletePlaylistId: (String?) -> Unit,
    presenter: LibraryPresenter,
) {
    if (createDraft != null) {
        AlertDialog(
            onDismissRequest = { onCreateDraft(null) },
            title = { Text("New Kainos playlist") },
            text = {
                OutlinedTextField(
                    value = createDraft,
                    onValueChange = { onCreateDraft(it) },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createDraft.trim()
                        if (name.isNotBlank()) {
                            presenter.createPlaylist(name)
                        }
                        onCreateDraft(null)
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { onCreateDraft(null) }) { Text("Cancel") }
            },
        )
    }
    if (renamePlaylistId != null) {
        AlertDialog(
            onDismissRequest = { onRenamePlaylistId(null) },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = onRenameDraft,
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        presenter.renamePlaylist(renamePlaylistId, renameDraft)
                        onRenamePlaylistId(null)
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { onRenamePlaylistId(null) }) { Text("Cancel") }
            },
        )
    }
    if (deletePlaylistId != null) {
        AlertDialog(
            onDismissRequest = { onDeletePlaylistId(null) },
            title = { Text("Delete playlist?") },
            text = { Text("Removes the Kainos playlist on this device. Home sync will tombstone it for peers.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        presenter.deletePlaylist(deletePlaylistId)
                        if (expandedKainosId == deletePlaylistId) onExpandedKainosId(null)
                        onDeletePlaylistId(null)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { onDeletePlaylistId(null) }) { Text("Cancel") }
            },
        )
    }
}
