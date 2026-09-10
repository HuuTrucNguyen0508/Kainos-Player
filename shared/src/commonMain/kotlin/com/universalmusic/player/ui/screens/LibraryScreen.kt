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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.spotify.isDiscoverWeekly
import com.universalmusic.player.domain.matching.TrackNormalizer
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
import kotlinx.coroutines.launch

private enum class LibraryTab { Songs, Albums, Artists, Playlists }

@Composable
fun LibraryScreen(
    container: AppContainer,
    onPlayTracks: (List<Track>, Int) -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(LibraryTab.Songs.name) }
    val tab = remember(tabName) {
        runCatching { LibraryTab.valueOf(tabName) }.getOrDefault(LibraryTab.Songs)
    }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val songsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val albumsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val artistsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val playlistsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val settings by container.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val saved by container.library.savedTracks.collectAsState()
    val favorites by container.library.favoriteIds.collectAsState()
    val localTracks by container.local.libraryTracks.collectAsState()
    val localState by container.local.state.collectAsState()
    val spotifyTracks by container.spotifyTracks.collectAsState()
    val spotifyPlaylists by container.spotifyPlaylists.collectAsState()
    val spotifyLoading by container.spotifyLibraryLoading.collectAsState()

    DisposableEffect(Unit) {
        onDispose { container.setTextInputFocused(false) }
    }
    val spotifyError by container.spotifyLibraryError.collectAsState()
    val spotifyState by container.spotify.state.collectAsState()
    val localOnly = settings.libraryLocalOnly
    val favoritesOnly = settings.libraryFavoritesOnly
    val needle = TrackNormalizer.fold(query.trim())
    // Chips define the playable queue; the text needle is display-only.
    val queueSongs = remember(
        localTracks,
        saved,
        spotifyTracks,
        settings.librarySongSort,
        localOnly,
        favoritesOnly,
        favorites,
    ) {
        val catalog = if (localOnly) {
            localTracks
        } else {
            (localTracks + saved + spotifyTracks).distinctBy(Track::canonicalId)
                .ifEmpty { container.sample.allTracks }
        }
        val filtered = catalog.filter { !favoritesOnly || it.canonicalId in favorites }
        settings.librarySongSort.sort(filtered)
    }
    val songs = remember(queueSongs, needle) {
        if (needle.isEmpty()) queueSongs
        else queueSongs.filter { it.matchesLibraryQuery(needle) }
    }
    val localAlbums = remember(localTracks, favoritesOnly, favorites) {
        localTracks
            .filter { it.album != null }
            .filter { !favoritesOnly || it.canonicalId in favorites }
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
    val filteredAlbums = remember(localAlbums, needle, localOnly, favoritesOnly) {
        val base = if (localOnly || favoritesOnly) {
            localAlbums
        } else {
            localAlbums.ifEmpty { container.sample.homeAlbums }
        }
        if (needle.isEmpty()) base else base.filter { it.matchesLibraryQuery(needle) }
    }
    val localArtists = remember(localTracks, favoritesOnly, favorites) {
        localTracks
            .filter { !favoritesOnly || it.canonicalId in favorites }
            .flatMap(Track::artists)
            .distinctBy { it.canonicalId }
            .map { Artist(it.canonicalId, it.name, it.artwork, sources = listOf(ProviderEntityRef(ProviderId.LOCAL, it.canonicalId))) }
            .sortedBy { it.name.lowercase() }
    }
    val filteredArtists = remember(localArtists, needle, localOnly, favoritesOnly) {
        val base = if (localOnly || favoritesOnly) {
            localArtists
        } else {
            localArtists.ifEmpty { container.sample.homeArtists }
        }
        if (needle.isEmpty()) base else base.filter { TrackNormalizer.fold(it.name).contains(needle) }
    }
    val filteredPlaylists = remember(spotifyPlaylists, needle, localOnly, favoritesOnly) {
        if (localOnly || favoritesOnly) return@remember emptyList()
        val base = spotifyPlaylists.ifEmpty { container.sample.homePlaylists }
        val filtered = if (needle.isEmpty()) base else base.filter { it.matchesLibraryQuery(needle) }
        filtered.sortedByDescending { it.isDiscoverWeekly() }
    }
    var playlistBusyId by remember { mutableStateOf<String?>(null) }
    var playlistError by remember { mutableStateOf<String?>(null) }

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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onFocusChanged { container.setTextInputFocused(it.isFocused) },
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = localOnly,
                onClick = {
                    scope.launch {
                        container.updateSettings { it.copy(libraryLocalOnly = !it.libraryLocalOnly) }
                    }
                },
                label = { Text("Local files only") },
                leadingIcon = if (localOnly) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
            FilterChip(
                selected = favoritesOnly,
                onClick = {
                    scope.launch {
                        container.updateSettings { it.copy(libraryFavoritesOnly = !it.libraryFavoritesOnly) }
                    }
                },
                label = { Text("Favorites only") },
                leadingIcon = if (favoritesOnly) {
                    {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
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
        if (spotifyState == ProviderState.AVAILABLE && !localOnly && !favoritesOnly) {
            OutlinedButton(onClick = { scope.launch { container.refreshSpotifyLibrary() } }, enabled = !spotifyLoading,
                modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(if (spotifyLoading) "Loading Spotify library…" else "Refresh Spotify · ${spotifyTracks.size} liked songs")
            }
        }
        if (!localOnly && !favoritesOnly) {
            spotifyError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 16.dp) {
            LibraryTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tabName = item.name },
                    text = { Text(item.name) },
                )
            }
        }
        val showingSamples = !localOnly && !favoritesOnly && when (tab) {
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
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
                    if (songs.isNotEmpty()) {
                        OutlinedButton(onClick = { onPlayTracks(songs, 0) }) {
                            Text(if (favoritesOnly) "Play favorites" else "Play all")
                        }
                    }
                }
                if (songs.isEmpty()) {
                    EmptyState(
                        if (needle.isEmpty()) {
                            if (favoritesOnly) "No favorites yet" else "No songs yet"
                        } else {
                            "No songs match \"$query\""
                        },
                        when {
                            needle.isNotEmpty() -> "Try another title, artist, or album name."
                            favoritesOnly -> "Heart tracks from Now Playing. App favorites stay separate from Spotify Liked."
                            localOnly -> "Scan your music folders from Settings, or turn off Local files only."
                            else -> "Play something and it will land here. App favorites stay separate from Spotify Liked."
                        },
                    )
                } else {
                    LaunchedEffect(settings.librarySongSort, needle, localOnly, favoritesOnly) {
                        songsListState.scrollToItem(0)
                    }
                    LazyColumn(state = songsListState) {
                        items(songs, key = { it.canonicalId }) { track ->
                            TrackRow(
                                track = track,
                                onClick = {
                                    val index = queueSongs.indexOfFirst { it.canonicalId == track.canonicalId }
                                        .coerceAtLeast(0)
                                    onPlayTracks(queueSongs, index)
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
                        when {
                            needle.isNotEmpty() -> "Try another album or artist name."
                            favoritesOnly -> "Heart tracks to see their albums here."
                            localOnly -> "Local albums appear after a library scan."
                            else -> "Connect a provider or play from the sample catalog."
                        },
                    )
                } else {
                    LaunchedEffect(needle, localOnly, favoritesOnly) {
                        albumsListState.scrollToItem(0)
                    }
                    LazyColumn(state = albumsListState) {
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
                        when {
                            needle.isNotEmpty() -> "Try another artist name."
                            favoritesOnly -> "Heart tracks to see their artists here."
                            else -> "Local artists appear here after a library scan."
                        },
                    )
                } else {
                    LaunchedEffect(needle, localOnly, favoritesOnly) {
                        artistsListState.scrollToItem(0)
                    }
                    LazyColumn(state = artistsListState) {
                        items(filteredArtists, key = { it.canonicalId }) { artist ->
                            Text(artist.name, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        }
                    }
                }
            }
            LibraryTab.Playlists -> {
                if (filteredPlaylists.isEmpty()) {
                    EmptyState(
                        if (needle.isEmpty()) {
                            when {
                                localOnly -> "No local playlists"
                                favoritesOnly -> "Playlists hidden"
                                else -> "No playlists"
                            }
                        } else {
                            "No playlists match \"$query\""
                        },
                        when {
                            needle.isNotEmpty() -> "Try another playlist name."
                            localOnly -> "Turn off Local files only to see Spotify playlists."
                            favoritesOnly -> "Favorites only hides playlists. Turn it off to browse Spotify playlists."
                            else -> "Connect Spotify or browse sample playlists."
                        },
                    )
                } else {
                    playlistError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    LaunchedEffect(needle, localOnly, favoritesOnly) {
                        playlistsListState.scrollToItem(0)
                    }
                    LazyColumn(state = playlistsListState) {
                        items(filteredPlaylists, key = { it.canonicalId }) { playlist ->
                            val busy = playlistBusyId == playlist.canonicalId
                            val subtitle = when {
                                busy -> "Loading…"
                                playlist.isDiscoverWeekly() -> "Made for you · Play in Kainos"
                                playlist.source.provider == ProviderId.SPOTIFY -> "Play in Kainos"
                                else -> playlist.description ?: playlist.ownerName.orEmpty()
                            }
                            Text(
                                playlist.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) {
                                        scope.launch {
                                            playlistError = null
                                            if (playlist.source.provider == ProviderId.SPOTIFY) {
                                                playlistBusyId = playlist.canonicalId
                                                try {
                                                    val tracks = container.loadSpotifyPlaylistTracks(
                                                        playlist.source.providerEntityId,
                                                    )
                                                    if (tracks.isEmpty()) {
                                                        playlistError = "No playable tracks in \"${playlist.title}\"."
                                                    } else {
                                                        onPlayTracks(tracks, 0)
                                                    }
                                                } catch (failure: Exception) {
                                                    playlistError = failure.message
                                                        ?: "Could not load \"${playlist.title}\"."
                                                } finally {
                                                    playlistBusyId = null
                                                }
                                            } else if (playlist.tracks.isNotEmpty()) {
                                                onPlayTracks(playlist.tracks, 0)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (playlist.isDiscoverWeekly()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
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

private fun Track.matchesLibraryQuery(needle: String): Boolean {
    if (needle.isEmpty()) return true
    return TrackNormalizer.fold(title).contains(needle) ||
        artists.any { TrackNormalizer.fold(it.name).contains(needle) } ||
        album?.title?.let { TrackNormalizer.fold(it).contains(needle) } == true
}

private fun Album.matchesLibraryQuery(needle: String): Boolean {
    if (needle.isEmpty()) return true
    return TrackNormalizer.fold(title).contains(needle) ||
        artists.any { TrackNormalizer.fold(it.name).contains(needle) } ||
        tracks.any { it.matchesLibraryQuery(needle) }
}

private fun Playlist.matchesLibraryQuery(needle: String): Boolean {
    if (needle.isEmpty()) return true
    return TrackNormalizer.fold(title).contains(needle) ||
        ownerName?.let { TrackNormalizer.fold(it).contains(needle) } == true ||
        description?.let { TrackNormalizer.fold(it).contains(needle) } == true
}
