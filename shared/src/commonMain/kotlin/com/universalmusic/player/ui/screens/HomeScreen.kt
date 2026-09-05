package com.universalmusic.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.components.ProviderStatusRow
import com.universalmusic.player.ui.components.SectionHeader
import com.universalmusic.player.ui.components.TrackRow

@Composable
fun HomeScreen(
    container: AppContainer,
    onPlay: (Track) -> Unit,
    onPlayTracks: (List<Track>) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val recent by container.library.recentlyPlayed.collectAsState()
    val spotifyState by container.spotify.state.collectAsState()
    val youtubeState by container.youtube.state.collectAsState()
    val soundcloudState by container.soundcloud.state.collectAsState()
    val localState by container.local.state.collectAsState()
    val localTracks by container.local.libraryTracks.collectAsState()
    val albums = container.sample.homeAlbums
    val playlists = container.sample.homePlaylists
    val continueTrack = recent.firstOrNull() ?: localTracks.firstOrNull() ?: container.sample.allTracks.first()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 96.dp),
    ) {
        Text(
            "Good listening",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        ProviderStatusRow(
            mapOf(
                ProviderId.LOCAL to localState,
                ProviderId.SPOTIFY to spotifyState,
                ProviderId.YOUTUBE_MUSIC to youtubeState,
                ProviderId.SOUNDCLOUD to soundcloudState,
            ),
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            if (recent.isNotEmpty()) "Continue listening" else if (localTracks.isNotEmpty()) "From your library" else "Try a sample track",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        TrackRow(
            track = continueTrack,
            onClick = {
                onPlay(continueTrack)
                onOpenNowPlaying()
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (recent.isNotEmpty()) {
            SectionHeader("Recently played")
            recent.take(8).forEach { track ->
                TrackRow(track, onClick = { onPlay(track) }, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        SectionHeader("Sample albums")
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            albums.forEach { album ->
                AlbumCard(album) {
                    if (album.tracks.isNotEmpty()) onPlayTracks(album.tracks)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionHeader("Sample playlists")
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            playlists.forEach { playlist ->
                PlaylistCard(playlist) {
                    if (playlist.tracks.isNotEmpty()) onPlayTracks(playlist.tracks)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "These sample albums and playlists use SoundHelix audio. Find your own music in Library or Search.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(
        Modifier
            .width(148.dp)
            .clickable(onClick = onClick),
    ) {
        ArtworkImage(album.artwork, album.title, Modifier.height(148.dp).width(148.dp), album.title)
        Spacer(Modifier.height(8.dp))
        Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            album.artists.joinToString { it.name },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        Modifier
            .width(148.dp)
            .clickable(onClick = onClick),
    ) {
        ArtworkImage(playlist.artwork, playlist.title, Modifier.height(148.dp).width(148.dp), playlist.title)
        Spacer(Modifier.height(8.dp))
        Text(playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            playlist.description ?: playlist.ownerName.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
