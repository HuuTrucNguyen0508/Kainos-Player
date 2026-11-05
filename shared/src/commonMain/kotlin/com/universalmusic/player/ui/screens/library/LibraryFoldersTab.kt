package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.LedgerRow
import com.universalmusic.player.ui.components.SourceKind
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.Skin

/**
 * Configured music folders as a file tree. [trail] is the path from a root to the open folder,
 * or null for the list of roots. Play and Shuffle take a folder with everything below it; a
 * tapped song plays the files of the folder it sits in. Search only narrows what is shown.
 */
@Composable
internal fun ColumnScope.LibraryFoldersTab(
    state: LibraryUiState,
    listState: LazyListState,
    trail: List<LibraryFolder>?,
    onOpenFolder: (String?) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onShuffleTracks: (List<Track>) -> Unit,
    onPinFolder: (LibraryFolder, Boolean) -> Unit,
    trackActions: TrackActionsPresenter,
    trackAvailability: (Track) -> TrackAvailabilityInfo,
) {
    val folder = trail?.last()
    if (folder == null && state.folders.isEmpty()) {
        EmptyState("No music folders", "Add a music folder in Settings to browse it here.")
        return
    }
    val matchingIds = remember(state.songs, state.needle) {
        if (state.needle.isEmpty()) emptySet() else state.songs.mapTo(HashSet()) { it.canonicalId }
    }
    val (folders, tracks) = remember(folder, state.folders, state.needle, matchingIds) {
        folder?.visibleFor(state.needle, matchingIds) ?: (state.folders.matching(state.needle, matchingIds) to emptyList())
    }
    val pinned = { item: LibraryFolder -> "${HomePinKind.LOCAL_FOLDER.name}|${item.id}" in state.pinnedKeys }
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        stickyHeader(key = "folder-header") {
            if (trail == null || folder == null) {
                LibraryLedger(
                    countLine = countNoun(state.folders.size, "music folder", "music folders"),
                    localCount = state.folders.flatMap { it.allTracks }.distinctBy { it.canonicalId }.size,
                    spotifyCount = 0,
                    localOnly = true,
                    favoritesOnly = state.favoritesOnly,
                    localScanning = false,
                    spotifyConnected = false,
                    spotifyLoading = false,
                    onRefreshSpotify = {},
                    sortLabel = "",
                    sortMenuOpen = false,
                    onSortMenuOpen = {},
                    onSortMenuDismiss = {},
                    onSortSelected = {},
                    currentSort = state.songSort,
                    playLabel = "",
                    onPlay = {},
                    showActions = false,
                )
            } else {
                FolderHeader(
                    trail = trail,
                    pinned = pinned(folder),
                    onOpenFolder = onOpenFolder,
                    onPlay = { onPlayTracks(folder.allTracks, 0) },
                    onShuffle = { onShuffleTracks(folder.allTracks) },
                    onPin = { onPinFolder(folder, !pinned(folder)) },
                )
            }
        }
        items(folders, key = { "folder:${it.id}" }) { item ->
            FolderRow(
                folder = item,
                pinned = pinned(item),
                onOpen = { onOpenFolder(item.id) },
                onPlay = { onPlayTracks(item.allTracks, 0) },
                onShuffle = { onShuffleTracks(item.allTracks) },
                onPin = { onPinFolder(item, !pinned(item)) },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        items(tracks, key = { "track:${it.canonicalId}" }) { track ->
            ActionTrackRow(
                track = track,
                actions = trackActions,
                compact = true,
                availability = remember(track, state.downloads) { trackAvailability(track) },
                onClick = {
                    // The folder as it is, not the search hits: search narrows the view, never the queue.
                    val files = folder?.tracks.orEmpty()
                    onPlayTracks(files, files.indexOfFirst { it.canonicalId == track.canonicalId }.coerceAtLeast(0))
                },
                modifier = Modifier.padding(horizontal = 8.dp),
                trailing = {
                    LibraryTrackTrailing(favorite = track.canonicalId in state.favorites, track = track)
                },
            )
        }
        if (folders.isEmpty() && tracks.isEmpty()) {
            item(key = "folder-empty") {
                Text(
                    when {
                        state.needle.isNotEmpty() -> "Nothing here matches \"${state.query}\"."
                        state.favoritesOnly -> "No favorites here. Turn off Favorites only to see every file."
                        folder != null -> "No music in this folder yet. Rescan from the top of Library after adding files."
                        else -> "No music folders match."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** The open folder's path in the skin's display face; earlier folders on the path open on tap. */
@Composable
private fun FolderHeader(
    trail: List<LibraryFolder>,
    pinned: Boolean,
    onOpenFolder: (String?) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPin: () -> Unit,
) {
    val folder = trail.last()
    val tokens = LocalSkinTokens.current
    val folio = tokens.skin == Skin.FOLIO
    val pathStyle = MaterialTheme.typography.titleMedium.copy(
        fontFamily = tokens.display,
        fontStyle = if (folio) FontStyle.Italic else FontStyle.Normal,
    )
    val parentName = trail.getOrNull(trail.lastIndex - 1)?.name
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onOpenFolder(trail.getOrNull(trail.lastIndex - 1)?.id) }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = parentName?.let { "Up to $it" } ?: "Back to music folders",
                )
            }
            // Reverse scrolling starts at the end, so a deep path shows the open folder first.
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trail.forEachIndexed { index, crumb ->
                    if (index > 0) {
                        Text("/", style = pathStyle, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                    if (index == trail.lastIndex) {
                        Text(crumb.name, style = pathStyle, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
                    } else {
                        Text(
                            crumb.name,
                            style = pathStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(tokens.rounding.small))
                                .clickable(onClickLabel = "Open ${crumb.name}") { onOpenFolder(crumb.id) }
                                .minimumInteractiveComponentSize()
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            PinButton(name = folder.name, pinned = pinned, onClick = onPin)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                folderSummary(folder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val playable = folder.allTracks.isNotEmpty()
            FilledTonalButton(onClick = onPlay, enabled = playable) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play")
            }
            OutlinedButton(onClick = onShuffle, enabled = playable) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Shuffle")
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun FolderRow(
    folder: LibraryFolder,
    pinned: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val playable = folder.allTracks.isNotEmpty()
    LedgerRow(modifier = modifier, onClick = onOpen) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(tokens.rounding.small))
                .background(palette.accentContainer(SourceKind.Folder.slot)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SourceKind.Folder.icon, contentDescription = null, tint = palette.accent(SourceKind.Folder.slot, AccentUse.Mark))
        }
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                folderSummary(folder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onPlay, enabled = playable) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${folder.name}")
        }
        IconButton(onClick = onShuffle, enabled = playable) {
            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle ${folder.name}")
        }
        PinButton(name = folder.name, pinned = pinned, onClick = onPin)
    }
}

@Composable
private fun PinButton(name: String, pinned: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Default.Star,
            contentDescription = if (pinned) "Unpin $name from Home" else "Pin $name to Home",
            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun folderSummary(folder: LibraryFolder): String {
    val songs = folder.allTracks.size
    val tracks = if (songs == 0) "no tracks" else countNoun(songs, "track", "tracks")
    return if (folder.folders.isEmpty()) {
        tracks.replaceFirstChar { it.uppercase() }
    } else {
        "${countNoun(folder.folders.size, "folder", "folders")}, $tracks"
    }
}
