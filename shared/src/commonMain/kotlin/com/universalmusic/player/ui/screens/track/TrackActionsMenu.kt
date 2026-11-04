package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.TrackRow
import com.universalmusic.player.ui.reportsTextInputFocus
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette

/** A row-specific menu entry that sits under the shared actions, such as Open YouTube. */
internal data class TrackMenuExtra(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * [TrackRow] with the shared track menu: the ⋮ button, a long press, or a right click opens it.
 * Tapping the row still plays, exactly as before.
 */
@Composable
internal fun ActionTrackRow(
    track: Track,
    actions: TrackActionsPresenter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    availability: TrackAvailabilityInfo? = null,
    trailing: @Composable (() -> Unit)? = null,
    extras: List<TrackMenuExtra> = emptyList(),
) {
    var menuOpen by remember(track.canonicalId) { mutableStateOf(false) }
    TrackRow(
        track = track,
        onClick = onClick,
        modifier = modifier.onSecondaryClick { menuOpen = true },
        compact = compact,
        availability = availability,
        onLongClick = { menuOpen = true },
        trailing = {
            trailing?.invoke()
            TrackActionsMenuButton(track, actions, menuOpen, { menuOpen = it }, extras)
        },
    )
}

/** The ⋮ button and the menu it anchors. Rows that draw their own layout use this directly. */
@Composable
internal fun TrackActionsMenuButton(
    track: Track,
    actions: TrackActionsPresenter,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    extras: List<TrackMenuExtra> = emptyList(),
) {
    val favorites by actions.favorites.collectAsState()
    val playlists by actions.playlists.collectAsState()
    var page by remember(expanded) { mutableStateOf(TrackMenuPage.Actions) }
    var newPlaylistDraft by remember { mutableStateOf<String?>(null) }
    fun close() = onExpandedChange(false)
    Box {
        // 40dp keeps compact rows at their old height; the whole row also opens the menu on long press.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            IconButton(onClick = { onExpandedChange(true) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More actions for ${track.title}", modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = ::close) {
            TrackActionsMenuItems(
                track = track,
                page = page,
                favorite = track.canonicalId in favorites,
                canQueue = actions.canQueue(track),
                playlists = playlists,
                extras = extras,
                onPage = { page = it },
                onPlayNext = { close(); actions.playNext(track) },
                onAddToQueue = { close(); actions.addToQueue(track) },
                onToggleFavorite = { close(); actions.toggleFavorite(track) },
                onAddToPlaylist = { id -> close(); actions.addToPlaylist(id, track) },
                onNewPlaylist = { close(); newPlaylistDraft = "" },
                onExtra = { extra -> close(); extra.onClick() },
            )
        }
    }
    newPlaylistDraft?.let { draft ->
        NewPlaylistDialog(
            draft = draft,
            onDraft = { newPlaylistDraft = it },
            onCreate = {
                actions.createPlaylist(draft, track)
                newPlaylistDraft = null
            },
            onDismiss = { newPlaylistDraft = null },
        )
    }
}

internal enum class TrackMenuPage { Actions, Playlists }

/** Stateless menu body, shared by the dropdown and the specimen gallery. */
@Composable
internal fun TrackActionsMenuItems(
    track: Track,
    page: TrackMenuPage,
    favorite: Boolean,
    canQueue: Boolean,
    playlists: List<PersistedKainosPlaylist>,
    extras: List<TrackMenuExtra>,
    onPage: (TrackMenuPage) -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onNewPlaylist: () -> Unit,
    onExtra: (TrackMenuExtra) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (page) {
        TrackMenuPage.Actions -> {
            // Long press opens the menu away from the ⋮, so name the song it acts on.
            Text(
                track.title,
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            if (canQueue) {
                MenuEntry("Play next", Icons.AutoMirrored.Filled.PlaylistPlay, onPlayNext)
                MenuEntry("Add to queue", Icons.AutoMirrored.Filled.QueueMusic, onAddToQueue)
            }
            DropdownMenuItem(
                text = { Text(if (favorite) "Remove heart" else "Heart") },
                leadingIcon = {
                    Icon(
                        if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (favorite) {
                            LocalKainosPalette.current.accent(Accents.heart, AccentUse.Mark, MaterialTheme.colorScheme.surfaceContainer)
                        } else {
                            muted
                        },
                    )
                },
                onClick = onToggleFavorite,
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                onClick = { onPage(TrackMenuPage.Playlists) },
            )
            if (extras.isNotEmpty()) {
                HorizontalDivider()
                extras.forEach { extra -> MenuEntry(extra.label, extra.icon) { onExtra(extra) } }
            }
        }
        TrackMenuPage.Playlists -> {
            DropdownMenuItem(
                text = { Text("Add to playlist", style = MaterialTheme.typography.labelLarge, color = muted) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to song actions") },
                onClick = { onPage(TrackMenuPage.Actions) },
            )
            HorizontalDivider()
            playlists.forEach { playlist ->
                val contains = playlist.entries.any { it.track.canonicalId == track.canonicalId }
                DropdownMenuItem(
                    text = {
                        Text(
                            playlist.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp),
                        )
                    },
                    trailingIcon = if (contains) {
                        { Icon(Icons.Default.Check, contentDescription = "Already in this playlist", tint = muted) }
                    } else {
                        { Text(countNoun(playlist.entries.size, "song", "songs"), style = MaterialTheme.typography.labelSmall, color = muted) }
                    },
                    onClick = { onAddToPlaylist(playlist.id) },
                )
            }
            MenuEntry("New playlist…", Icons.Default.Add, onNewPlaylist)
        }
    }
}

@Composable
private fun MenuEntry(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun NewPlaylistDialog(
    draft: String,
    onDraft: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Kainos playlist") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
            )
        },
        confirmButton = {
            TextButton(onClick = onCreate, enabled = draft.isNotBlank()) { Text("Create and add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Right click (secondary button) on desktop and mouse-driven Android. */
@Composable
internal fun Modifier.onSecondaryClick(action: () -> Unit): Modifier {
    val currentAction by rememberUpdatedState(action)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Initial pass, so the row's own click never sees the right press.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    currentAction()
                }
            }
        }
    }
}
