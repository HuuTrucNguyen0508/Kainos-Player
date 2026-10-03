package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.TrackRow

@Composable
internal fun ColumnScope.LibraryArtistsTab(
    state: LibraryUiState,
    listState: LazyListState,
    selectedArtist: LibraryArtistEntry?,
    onSelectedArtistId: (String?) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    trackAvailability: (Track) -> TrackAvailabilityInfo,
) {
    val artistDetail = selectedArtist
    if (artistDetail != null) {
        val artistTracks = remember(artistDetail, state.songSort) {
            state.songSort.sort(artistDetail.tracks)
        }
        if (artistTracks.isEmpty()) {
            EmptyState(
                "No tracks",
                "Nothing left for ${artistDetail.artist.name} with the current filters.",
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                stickyHeader(key = "artist-detail") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { onSelectedArtistId(null) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to artists",
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    artistDetail.artist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    countNoun(artistTracks.size, "track", "tracks"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalButton(onClick = { onPlayTracks(artistTracks, 0) }) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Play all")
                            }
                        }
                        HorizontalDivider()
                    }
                }
                items(artistTracks, key = { it.canonicalId }) { track ->
                    TrackRow(
                        track = track,
                        compact = true,
                        availability = remember(track, state.downloads) {
                            trackAvailability(track)
                        },
                        onClick = {
                            val index = artistTracks.indexOfFirst { it.canonicalId == track.canonicalId }
                                .coerceAtLeast(0)
                            onPlayTracks(artistTracks, index)
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
    } else if (state.artists.isEmpty()) {
        EmptyState(
            if (state.needle.isEmpty()) "No artists" else "No artists match \"${state.query}\"",
            when {
                state.needle.isNotEmpty() -> "Try another artist name."
                state.favoritesOnly -> "Heart tracks to see their artists here."
                else -> "Local artists appear here after a library scan."
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
                    countLine = countNoun(state.artists.size, "artist", "artists"),
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
            items(state.artists, key = { it.artist.canonicalId }) { entry ->
                Surface(
                    onClick = { onSelectedArtistId(entry.artist.canonicalId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    color = Color.Transparent,
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.artist.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                countNoun(entry.tracks.size, "track", "tracks"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
