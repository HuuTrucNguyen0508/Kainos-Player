package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackSort
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.TrackRow

@Composable
internal fun ColumnScope.LibrarySongsTab(
    state: LibraryUiState,
    listState: LazyListState,
    sortMenuOpen: Boolean,
    onSortMenuOpen: () -> Unit,
    onSortMenuDismiss: () -> Unit,
    onSortSelected: (TrackSort) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onRefreshSpotify: () -> Unit,
    trackAvailability: (Track) -> TrackAvailabilityInfo,
) {
    val songs = state.songs
    val queueSongs = state.queueSongs
    if (songs.isEmpty()) {
        EmptyState(
            if (state.needle.isEmpty()) {
                if (state.favoritesOnly) "No favorites yet" else "No songs yet"
            } else {
                "No songs match \"${state.query}\""
            },
            when {
                state.needle.isNotEmpty() -> "Try another title, artist, or album name."
                state.favoritesOnly -> "Heart tracks from Now Playing. App favorites stay separate from Spotify Liked."
                state.localOnly -> "Scan your music folders from Settings, or turn off Local files only."
                else -> "Local files and Spotify likes show up here after a scan or Spotify refresh."
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
                    countLine = songsCountLine(
                        visible = songs.size,
                        total = queueSongs.size,
                        queryActive = state.needle.isNotEmpty(),
                        favoritesOnly = state.favoritesOnly,
                    ),
                    localCount = state.localCount,
                    spotifyCount = state.spotifyCount,
                    localOnly = state.localOnly,
                    favoritesOnly = state.favoritesOnly,
                    localScanning = state.localState == ProviderState.LOADING,
                    spotifyConnected = state.spotifyConnected,
                    spotifyLoading = state.spotifyLoading,
                    onRefreshSpotify = onRefreshSpotify,
                    sortLabel = state.songSort.shortLabel(),
                    sortMenuOpen = sortMenuOpen,
                    onSortMenuOpen = onSortMenuOpen,
                    onSortMenuDismiss = onSortMenuDismiss,
                    onSortSelected = onSortSelected,
                    currentSort = state.songSort,
                    // Search only filters what is shown; chips define the queue, same as row taps and Enter.
                    playLabel = when {
                        state.needle.isNotEmpty() -> "Play all ${queueSongs.size}"
                        state.favoritesOnly -> "Play favorites"
                        else -> "Play all"
                    },
                    onPlay = { onPlayTracks(queueSongs, 0) },
                    showActions = true,
                )
            }
            items(songs, key = { it.canonicalId }) { track ->
                TrackRow(
                    track = track,
                    compact = true,
                    availability = remember(track, state.downloads) {
                        trackAvailability(track)
                    },
                    onClick = {
                        val index = queueSongs.indexOfFirst { it.canonicalId == track.canonicalId }
                            .coerceAtLeast(0)
                        onPlayTracks(queueSongs, index)
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    trailing = {
                        LibraryTrackTrailing(
                            favorite = track.canonicalId in state.favorites,
                            track = track,
                        )
                    },
                )
            }
        }
    }
}
