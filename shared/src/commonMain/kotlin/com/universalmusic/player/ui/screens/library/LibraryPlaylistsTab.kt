package com.universalmusic.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.playlist.playlistEntryAvailabilityLabel
import com.universalmusic.player.data.spotify.isDiscoverWeekly
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.TrackRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ColumnScope.LibraryPlaylistsTab(
    state: LibraryUiState,
    presenter: LibraryPresenter,
    listState: LazyListState,
    scope: CoroutineScope,
    onPlayTracks: (List<Track>, Int) -> Unit,
    playlistBusyId: String?,
    onPlaylistBusyId: (String?) -> Unit,
    playlistError: String?,
    onPlaylistError: (String?) -> Unit,
    expandedKainosId: String?,
    onExpandedKainosId: (String?) -> Unit,
    renamePlaylistId: String?,
    onRenamePlaylistId: (String?) -> Unit,
    renameDraft: String,
    onRenameDraft: (String) -> Unit,
    deletePlaylistId: String?,
    onDeletePlaylistId: (String?) -> Unit,
    createDraft: String?,
    onCreateDraft: (String?) -> Unit,
) {
    val showKainos = !state.favoritesOnly
    val showSpotify = !state.localOnly && !state.favoritesOnly
    val totalCount = (if (showKainos) state.kainosPlaylists.size else 0) +
        (if (showSpotify) state.spotifyPlaylists.size else 0)
    LibraryPlaylistDialogs(
        createDraft = createDraft,
        onCreateDraft = onCreateDraft,
        renamePlaylistId = renamePlaylistId,
        renameDraft = renameDraft,
        onRenameDraft = onRenameDraft,
        onRenamePlaylistId = onRenamePlaylistId,
        deletePlaylistId = deletePlaylistId,
        expandedKainosId = expandedKainosId,
        onExpandedKainosId = onExpandedKainosId,
        onDeletePlaylistId = onDeletePlaylistId,
        presenter = presenter,
    )
    if (totalCount == 0 && state.needle.isEmpty() && !showKainos) {
        EmptyState(
            "Playlists hidden",
            "Favorites only hides playlists. Turn it off to browse Kainos and Spotify playlists.",
        )
    } else if (totalCount == 0) {
        EmptyState(
            if (state.needle.isEmpty()) {
                when {
                    state.localOnly -> "No Kainos playlists yet"
                    else -> "No playlists"
                }
            } else {
                "No playlists match \"${state.query}\""
            },
            when {
                state.needle.isNotEmpty() -> "Try another playlist name."
                state.localOnly -> "Create a Kainos playlist, or turn off Local files only to see Spotify playlists."
                else -> "Create a Kainos playlist or connect Spotify and refresh."
            },
        )
        if (showKainos && state.needle.isEmpty()) {
            TextButton(
                onClick = { onCreateDraft("My playlist") },
                modifier = Modifier.padding(horizontal = 24.dp),
            ) { Text("New playlist") }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            stickyHeader(key = "ledger") {
                LibraryLedger(
                    countLine = countNoun(totalCount, "playlist", "playlists"),
                    localCount = 0,
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
            }
            if (showKainos) {
                item(key = "kainos-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Kainos playlists",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = { onCreateDraft("My playlist") }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("New")
                        }
                    }
                }
                if (state.kainosPlaylists.isEmpty()) {
                    item(key = "kainos-empty") {
                        Text(
                            if (state.needle.isEmpty()) "No Kainos playlists yet. Save a queue or create one."
                            else "No Kainos playlists match.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }
                items(state.kainosPlaylists, key = { it.canonicalId }) { playlist ->
                    val expanded = expandedKainosId == playlist.canonicalId
                    val kainosIndex = state.kainosPlaylists.indexOfFirst { it.canonicalId == playlist.canonicalId }
                    val kainosPinned =
                        "${HomePinKind.KAINOS_PLAYLIST.name}|${playlist.canonicalId}" in state.pinnedKeys
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onExpandedKainosId(if (expanded) null else playlist.canonicalId)
                            }
                            .padding(start = 24.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(playlist.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                playlist.description ?: "Kainos playlist",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(
                            onClick = {
                                if (kainosPinned) {
                                    presenter.unpinHome(HomePinKind.KAINOS_PLAYLIST, playlist.canonicalId)
                                } else {
                                    presenter.pinHome(
                                        kind = HomePinKind.KAINOS_PLAYLIST,
                                        targetId = playlist.canonicalId,
                                        title = playlist.title,
                                        subtitle = "Kainos playlist",
                                        artworkUrl = playlist.artwork?.url,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = if (kainosPinned) {
                                    "Unpin ${playlist.title} from Home"
                                } else {
                                    "Pin ${playlist.title} to Home"
                                },
                                tint = if (kainosPinned) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(
                            onClick = {
                                if (kainosIndex > 0) {
                                    val ids = state.kainosPlaylists.map { it.canonicalId }.toMutableList()
                                    ids.removeAt(kainosIndex)
                                    ids.add(kainosIndex - 1, playlist.canonicalId)
                                    // Preserve filtered-out playlists after the visible reorder.
                                    val hidden = state.kainosRows.map { it.id }.filterNot { it in ids }
                                    presenter.reorderPlaylists(ids + hidden)
                                }
                            },
                            enabled = kainosIndex > 0,
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(
                            onClick = {
                                if (kainosIndex >= 0 && kainosIndex < state.kainosPlaylists.lastIndex) {
                                    val ids = state.kainosPlaylists.map { it.canonicalId }.toMutableList()
                                    ids.removeAt(kainosIndex)
                                    ids.add(kainosIndex + 1, playlist.canonicalId)
                                    val hidden = state.kainosRows.map { it.id }.filterNot { it in ids }
                                    presenter.reorderPlaylists(ids + hidden)
                                }
                            },
                            enabled = kainosIndex >= 0 && kainosIndex < state.kainosPlaylists.lastIndex,
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                        }
                        IconButton(
                            onClick = {
                                if (playlist.tracks.isNotEmpty()) {
                                    onPlayTracks(playlist.tracks, 0)
                                }
                            },
                            enabled = playlist.tracks.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${playlist.title}")
                        }
                        IconButton(
                            onClick = {
                                onRenamePlaylistId(playlist.canonicalId)
                                onRenameDraft(playlist.title)
                            },
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename ${playlist.title}")
                        }
                        IconButton(onClick = { onDeletePlaylistId(playlist.canonicalId) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${playlist.title}")
                        }
                    }
                    if (expanded) {
                        TextButton(
                            onClick = {
                                val tracks = presenter.currentQueueTracks()
                                if (tracks.isNotEmpty()) {
                                    presenter.addTracks(playlist.canonicalId, tracks)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) { Text("Add current queue") }
                        playlist.tracks.forEachIndexed { index, track ->
                            val entryId = state.kainosRows
                                .firstOrNull { it.id == playlist.canonicalId }
                                ?.entries?.getOrNull(index)?.entryId
                            TrackRow(
                                track = track,
                                onClick = { onPlayTracks(playlist.tracks, index) },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            playlistEntryAvailabilityLabel(track),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(end = 4.dp),
                                        )
                                        if (entryId != null) {
                                            IconButton(
                                                onClick = {
                                                    presenter.removeEntry(playlist.canonicalId, entryId)
                                                },
                                            ) {
                                                Icon(
                                                    Icons.Default.Clear,
                                                    contentDescription = "Remove ${track.title}",
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        if (playlist.tracks.isEmpty()) {
                            Text(
                                "Empty playlist. Save a queue or add the current queue.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            if (showSpotify) {
                item(key = "spotify-header") {
                    Text(
                        "Spotify playlists",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
                if (playlistError != null) {
                    item(key = "playlist-error") {
                        Text(
                            playlistError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                if (state.spotifyPlaylists.isEmpty()) {
                    item(key = "spotify-empty") {
                        Text(
                            if (state.spotifyConnected) "No Spotify playlists loaded. Refresh from Songs."
                            else "Connect Spotify in Settings to load provider playlists.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }
                items(state.spotifyPlaylists, key = { it.canonicalId }) { playlist ->
                    val busy = playlistBusyId == playlist.canonicalId
                    val subtitle = when {
                        busy -> "Loading…"
                        playlist.isDiscoverWeekly() -> "Made for you · Play in Kainos"
                        playlist.source.provider == ProviderId.SPOTIFY -> "Spotify · Play in Kainos"
                        else -> playlist.description ?: playlist.ownerName.orEmpty()
                    }
                    val spotifyPinned =
                        "${HomePinKind.PROVIDER_PLAYLIST.name}|${playlist.canonicalId}" in state.pinnedKeys
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable(enabled = !busy) {
                                    scope.launch {
                                        onPlaylistError(null)
                                        if (playlist.source.provider == ProviderId.SPOTIFY) {
                                            onPlaylistBusyId(playlist.canonicalId)
                                            try {
                                                val tracks = presenter.loadSpotifyPlaylistTracks(
                                                    playlist.source.providerEntityId,
                                                )
                                                if (tracks.isEmpty()) {
                                                    onPlaylistError("No playable tracks in \"${playlist.title}\".")
                                                } else {
                                                    onPlayTracks(tracks, 0)
                                                }
                                            } catch (failure: Exception) {
                                                onPlaylistError(
                                                    failure.message ?: "Could not load \"${playlist.title}\".",
                                                )
                                            } finally {
                                                onPlaylistBusyId(null)
                                            }
                                        } else if (playlist.tracks.isNotEmpty()) {
                                            onPlayTracks(playlist.tracks, 0)
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(playlist.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (playlist.isDiscoverWeekly()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(
                            onClick = {
                                if (spotifyPinned) {
                                    presenter.unpinHome(HomePinKind.PROVIDER_PLAYLIST, playlist.canonicalId)
                                } else {
                                    presenter.pinHome(
                                        kind = HomePinKind.PROVIDER_PLAYLIST,
                                        targetId = playlist.canonicalId,
                                        title = playlist.title,
                                        subtitle = "Spotify playlist",
                                        artworkUrl = playlist.artwork?.url,
                                        providerEntityId = playlist.source.providerEntityId,
                                        provider = playlist.source.provider.name,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = if (spotifyPinned) {
                                    "Unpin ${playlist.title} from Home"
                                } else {
                                    "Pin ${playlist.title} to Home"
                                },
                                tint = if (spotifyPinned) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
