package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackSort
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.ui.components.AlbumRow
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.TrackRow
import com.universalmusic.player.platform.openUrl
import com.universalmusic.player.platform.encodeUrl
import kotlinx.coroutines.launch

private enum class LibraryTab { Songs, Albums, Artists, Playlists }

@Composable
fun LibraryScreen(
    container: AppContainer,
    onPlayTracks: (List<Track>, Int) -> Unit,
) {
    var tab by remember { mutableStateOf(LibraryTab.Songs) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val settings by container.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val saved by container.library.savedTracks.collectAsState()
    val favorites by container.library.favoriteIds.collectAsState()
    val localTracks by container.local.libraryTracks.collectAsState()
    val localState by container.local.state.collectAsState()
    val spotifyTracks by container.spotifyTracks.collectAsState()
    val spotifyPlaylists by container.spotifyPlaylists.collectAsState()
    val spotifyLoading by container.spotifyLibraryLoading.collectAsState()
    val spotifyError by container.spotifyLibraryError.collectAsState()
    val spotifyState by container.spotify.state.collectAsState()
    val needle = query.trim().lowercase()
    val songs = remember(localTracks, saved, spotifyTracks, settings.librarySongSort, needle) {
        settings.librarySongSort.sort(
            (localTracks + saved + spotifyTracks).distinctBy(Track::canonicalId)
                .ifEmpty { container.sample.allTracks }
                .filter { needle.isEmpty() || it.matchesLibraryQuery(needle) },
        )
    }
    val localAlbums = remember(localTracks) {
        localTracks
            .filter { it.album != null }
            .groupBy { it.album!!.canonicalId }
            .map { (id, tracks) ->
                val ref = tracks.first().album!!
                Album(
                    canonicalId = id,
                    title = ref.title,
                    artists = tracks.flatMap(Track::artists).distinctBy { it.canonicalId },
                    artwork = ref.artwork,
                    year = ref.year,
                    tracks = tracks,
                    sources = listOf(ProviderEntityRef(ProviderId.LOCAL, id)),
                )
            }
            .sortedBy { it.title.lowercase() }
    }
    val filteredAlbums = remember(localAlbums, needle) {
        val base = localAlbums.ifEmpty { container.sample.homeAlbums }
        if (needle.isEmpty()) base else base.filter { it.matchesLibraryQuery(needle) }
    }
    val localArtists = remember(localTracks) {
        localTracks
            .flatMap(Track::artists)
            .distinctBy { it.canonicalId }
            .map { Artist(it.canonicalId, it.name, it.artwork, sources = listOf(ProviderEntityRef(ProviderId.LOCAL, it.canonicalId))) }
            .sortedBy { it.name.lowercase() }
    }
    val filteredArtists = remember(localArtists, needle) {
        val base = localArtists.ifEmpty { container.sample.homeArtists }
        if (needle.isEmpty()) base else base.filter { it.name.lowercase().contains(needle) }
    }
    val filteredPlaylists = remember(spotifyPlaylists, needle) {
        val base = spotifyPlaylists.ifEmpty { container.sample.homePlaylists }
        if (needle.isEmpty()) base else base.filter { it.matchesLibraryQuery(needle) }
    }

    Column(Modifier.fillMaxSize().padding(bottom = 88.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = container::refreshLocalLibrary, enabled = localState != ProviderState.LOADING) {
                Text(if (localState == ProviderState.LOADING) "Scanning…" else "Refresh files")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search library") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear library search")
                    }
                }
            },
        )
        Text(
            when {
                localState == ProviderState.LOADING -> "Scanning your music folders…"
                localState == ProviderState.UNAVAILABLE -> "Local files are unavailable. Check media permission or configured folders."
                localTracks.isEmpty() -> "No local audio found yet."
                else -> "${localTracks.size} local ${if (localTracks.size == 1) "track" else "tracks"}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (spotifyState == ProviderState.AVAILABLE) {
            OutlinedButton(onClick = { scope.launch { container.refreshSpotifyLibrary() } }, enabled = !spotifyLoading,
                modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(if (spotifyLoading) "Loading Spotify library…" else "Refresh Spotify · ${spotifyTracks.size} liked songs")
            }
        }
        spotifyError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 16.dp) {
            LibraryTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tab = item },
                    text = { Text(item.name) },
                )
            }
        }
        val showingSamples = when (tab) {
            LibraryTab.Songs -> localTracks.isEmpty() && saved.isEmpty() && spotifyTracks.isEmpty()
            LibraryTab.Albums -> localAlbums.isEmpty()
            LibraryTab.Artists -> localArtists.isEmpty()
            LibraryTab.Playlists -> spotifyPlaylists.isEmpty()
        }
        if (showingSamples && needle.isEmpty()) {
            Text(
                "Sample catalog · SoundHelix audio",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        when (tab) {
            LibraryTab.Songs -> {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = { sortMenuOpen = true }) {
                        Text("Sort: ${settings.librarySongSort.label()}")
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        TrackSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.label()) },
                                onClick = {
                                    sortMenuOpen = false
                                    scope.launch { container.updateSettings { it.copy(librarySongSort = sort) } }
                                },
                                trailingIcon = {
                                    if (settings.librarySongSort == sort) {
                                        Icon(Icons.Default.Check, contentDescription = "Selected")
                                    }
                                },
                            )
                        }
                    }
                }
                if (songs.isEmpty()) {
                    EmptyState(
                        if (needle.isEmpty()) "No songs yet" else "No songs match \"$query\"",
                        if (needle.isEmpty()) {
                            "Play something and it will land here. App favorites stay separate from Spotify Liked."
                        } else {
                            "Try another title, artist, or album name."
                        },
                    )
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(settings.librarySongSort, needle) { listState.scrollToItem(0) }
                    LazyColumn(state = listState) {
                        items(songs, key = { it.canonicalId }) { track ->
                            TrackRow(
                                track = track,
                                onClick = {
                                    val index = songs.indexOfFirst { it.canonicalId == track.canonicalId }.coerceAtLeast(0)
                                    onPlayTracks(songs, index)
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                                trailing = {
                                    Row(
                                        modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (track.canonicalId in favorites) {
                                            Icon(
                                                Icons.Default.Favorite,
                                                contentDescription = "App favorite",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        Text(
                                            track.durationMs?.takeIf { it > 0 }?.let(::formatTime) ?: "--:--",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            LibraryTab.Albums -> {
                if (filteredAlbums.isEmpty()) {
                    EmptyState(
                        if (needle.isEmpty()) "No albums" else "No albums match \"$query\"",
                        if (needle.isEmpty()) {
                            "Connect a provider or play from the sample catalog."
                        } else {
                            "Try another album or artist name."
                        },
                    )
                } else {
                    LazyColumn {
                        items(filteredAlbums, key = { it.canonicalId }) { album ->
                            AlbumRow(
                                album = album,
                                onClick = { if (album.tracks.isNotEmpty()) onPlayTracks(album.tracks, 0) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
            LibraryTab.Artists -> {
                if (filteredArtists.isEmpty()) {
                    EmptyState(
                        if (needle.isEmpty()) "No artists" else "No artists match \"$query\"",
                        if (needle.isEmpty()) {
                            "Local artists appear here after a library scan."
                        } else {
                            "Try another artist name."
                        },
                    )
                } else {
                    LazyColumn {
                        items(filteredArtists, key = { it.canonicalId }) { artist ->
                            Text(artist.name, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        }
                    }
                }
            }
            LibraryTab.Playlists -> {
                if (filteredPlaylists.isEmpty()) {
                    EmptyState(
                        if (needle.isEmpty()) "No playlists" else "No playlists match \"$query\"",
                        if (needle.isEmpty()) {
                            "Connect Spotify or browse sample playlists."
                        } else {
                            "Try another playlist name."
                        },
                    )
                } else {
                    LazyColumn {
                        items(filteredPlaylists, key = { it.canonicalId }) { playlist ->
                            Text(
                                if (playlist.source.provider == ProviderId.SPOTIFY) "${playlist.title} · Open Spotify" else playlist.title,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        if (playlist.source.provider == ProviderId.SPOTIFY) {
                                            openUrl("https://open.spotify.com/playlist/${encodeUrl(playlist.source.providerEntityId)}")
                                        } else if (playlist.tracks.isNotEmpty()) onPlayTracks(playlist.tracks, 0)
                                    }
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun TrackSort.label(): String = when (this) {
    TrackSort.NAME_ASCENDING -> "Name A to Z"
    TrackSort.NAME_DESCENDING -> "Name Z to A"
    TrackSort.DURATION_ASCENDING -> "Duration shortest first"
    TrackSort.DURATION_DESCENDING -> "Duration longest first"
}

private fun Track.matchesLibraryQuery(needle: String): Boolean =
    title.lowercase().contains(needle) ||
        artists.any { it.name.lowercase().contains(needle) } ||
        album?.title?.lowercase()?.contains(needle) == true

private fun Album.matchesLibraryQuery(needle: String): Boolean =
    title.lowercase().contains(needle) ||
        artists.any { it.name.lowercase().contains(needle) } ||
        tracks.any { it.matchesLibraryQuery(needle) }

private fun Playlist.matchesLibraryQuery(needle: String): Boolean =
    title.lowercase().contains(needle) ||
        ownerName?.lowercase()?.contains(needle) == true ||
        description?.lowercase()?.contains(needle) == true
