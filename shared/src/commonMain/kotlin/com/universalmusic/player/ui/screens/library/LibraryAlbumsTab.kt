package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.AlbumRow
import com.universalmusic.player.ui.components.EmptyState

@Composable
internal fun ColumnScope.LibraryAlbumsTab(
    state: LibraryUiState,
    listState: LazyListState,
    presenter: LibraryPresenter,
    onPlayTracks: (List<Track>, Int) -> Unit,
) {
    val albums = state.albums
    if (albums.isEmpty()) {
        EmptyState(
            if (state.needle.isEmpty()) "No albums" else "No albums match \"${state.query}\"",
            when {
                state.needle.isNotEmpty() -> "Try another album or artist name."
                state.favoritesOnly -> "Heart tracks to see their albums here."
                state.localOnly -> "Local albums appear after a library scan."
                else -> "Albums appear after a local library scan."
            },
        )
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            stickyHeader(key = "ledger") {
                LibraryLedger(
                    countLine = countNoun(albums.size, "album", "albums"),
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
            items(albums, key = { it.canonicalId }) { album ->
                val albumPinned = "${HomePinKind.ALBUM.name}|${album.canonicalId}" in state.pinnedKeys
                AlbumRow(
                    album = album,
                    onClick = { if (album.tracks.isNotEmpty()) onPlayTracks(album.tracks, 0) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    trailing = {
                        IconButton(
                            onClick = {
                                if (albumPinned) {
                                    presenter.unpinHome(HomePinKind.ALBUM, album.canonicalId)
                                } else {
                                    presenter.pinHome(
                                        kind = HomePinKind.ALBUM,
                                        targetId = album.canonicalId,
                                        title = album.title,
                                        subtitle = album.artists.joinToString { it.name }.ifBlank { "Album" },
                                        artworkUrl = album.artwork?.url,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = if (albumPinned) {
                                    "Unpin ${album.title} from Home"
                                } else {
                                    "Pin ${album.title} to Home"
                                },
                                tint = if (albumPinned) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}
