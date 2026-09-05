package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.AlbumRow
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.SectionHeader
import com.universalmusic.player.ui.components.TrackRow

private enum class LibraryTab { Songs, Albums, Artists, Playlists }

@Composable
fun LibraryScreen(
    container: AppContainer,
    onPlay: (Track) -> Unit,
    onPlayTracks: (List<Track>) -> Unit,
) {
    var tab by remember { mutableStateOf(LibraryTab.Songs) }
    val saved by container.library.savedTracks.collectAsState()
    val favorites by container.library.favoriteIds.collectAsState()
    val songs = saved.ifEmpty { container.sample.allTracks }

    Column(Modifier.fillMaxSize().padding(bottom = 88.dp)) {
        SectionHeader("Library")
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 16.dp) {
            LibraryTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tab = item },
                    text = { Text(item.name) },
                )
            }
        }
        when (tab) {
            LibraryTab.Songs -> {
                if (songs.isEmpty()) {
                    EmptyState("No songs yet", "Play something and it will land here. App favorites stay separate from Spotify Liked.")
                } else {
                    LazyColumn {
                        items(songs, key = { it.canonicalId }) { track ->
                            TrackRow(
                                track = track,
                                onClick = { onPlay(track) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                                trailing = {
                                    if (track.canonicalId in favorites) {
                                        Text("App favorite", modifier = Modifier.padding(end = 8.dp))
                                    }
                                },
                            )
                        }
                    }
                }
            }
            LibraryTab.Albums -> {
                val albums = container.sample.homeAlbums
                if (albums.isEmpty()) {
                    EmptyState("No albums", "Connect a provider or play from the sample catalog.")
                } else {
                    LazyColumn {
                        items(albums, key = { it.canonicalId }) { album ->
                            AlbumRow(
                                album = album,
                                onClick = { if (album.tracks.isNotEmpty()) onPlayTracks(album.tracks) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
            LibraryTab.Artists -> {
                LazyColumn {
                    items(container.sample.homeArtists, key = { it.canonicalId }) { artist ->
                        Text(artist.name, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    }
                }
            }
            LibraryTab.Playlists -> {
                LazyColumn {
                    items(container.sample.homePlaylists, key = { it.canonicalId }) { playlist ->
                        Text(
                            playlist.title,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
