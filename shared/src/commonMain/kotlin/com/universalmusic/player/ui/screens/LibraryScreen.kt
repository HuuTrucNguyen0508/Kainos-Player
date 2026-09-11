package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.library.requiresNetworkToPlay
import com.universalmusic.player.data.spotify.isDiscoverWeekly
import com.universalmusic.player.domain.matching.TrackNormalizer
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackSort
import com.universalmusic.player.ui.components.AlbumRow
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.TrackRow
import com.universalmusic.player.ui.theme.LocalSignal
import com.universalmusic.player.ui.theme.SpotifyGreen
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private enum class LibraryTab { Songs, Albums, Artists, Playlists }

@OptIn(ExperimentalMaterial3Api::class)
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
        }
        val filtered = catalog.filter { !favoritesOnly || it.canonicalId in favorites }
        settings.librarySongSort.sort(filtered)
    }
    val songs = remember(queueSongs, needle) {
        if (needle.isEmpty()) queueSongs else queueSongs.filter { it.matchesLibraryQuery(needle) }
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
    val filteredAlbums = remember(localAlbums, needle) {
        if (needle.isEmpty()) localAlbums else localAlbums.filter { it.matchesLibraryQuery(needle) }
    }
    val localArtists = remember(localTracks, favoritesOnly, favorites) {
        localTracks
            .filter { !favoritesOnly || it.canonicalId in favorites }
            .flatMap(Track::artists)
            .distinctBy { it.canonicalId }
            .map {
                Artist(
                    it.canonicalId,
                    it.name,
                    it.artwork,
                    sources = listOf(ProviderEntityRef(ProviderId.LOCAL, it.canonicalId)),
                )
            }
            .sortedBy { it.name.lowercase() }
    }
    val filteredArtists = remember(localArtists, needle) {
        if (needle.isEmpty()) localArtists else localArtists.filter { TrackNormalizer.fold(it.name).contains(needle) }
    }
    val filteredPlaylists = remember(spotifyPlaylists, needle, localOnly, favoritesOnly) {
        if (localOnly || favoritesOnly) return@remember emptyList()
        val filtered = if (needle.isEmpty()) spotifyPlaylists else spotifyPlaylists.filter { it.matchesLibraryQuery(needle) }
        filtered.sortedByDescending { it.isDiscoverWeekly() }
    }
    var playlistBusyId by remember { mutableStateOf<String?>(null) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var dismissSpotifyError by rememberSaveable { mutableStateOf(false) }
    val showSpotifyError = !localOnly && !favoritesOnly && spotifyError != null && !dismissSpotifyError
    val spotifyConnected = spotifyState == ProviderState.AVAILABLE || spotifyState == ProviderState.RATE_LIMITED

    val localInQueue = remember(queueSongs) { queueSongs.count { it.sources.any { s -> s.provider == ProviderId.LOCAL } } }
    val spotifyInQueue = remember(queueSongs) { queueSongs.count { it.sources.any { s -> s.provider == ProviderId.SPOTIFY } } }

    val density = LocalDensity.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var chromeHeightPx by remember { mutableIntStateOf(0) }
    SideEffect {
        if (chromeHeightPx > 0) {
            scrollBehavior.state.heightOffsetLimit = -chromeHeightPx.toFloat()
        }
    }
    LaunchedEffect(tab) {
        scrollBehavior.state.heightOffset = 0f
    }

    LaunchedEffect(settings.librarySongSort, needle, localOnly, favoritesOnly) {
        snapshotFlow { listOf(settings.librarySongSort, needle, localOnly, favoritesOnly) }
            .drop(1)
            .collect {
                scrollBehavior.state.heightOffset = 0f
                when (tab) {
                    LibraryTab.Songs -> songsListState.scrollToItem(0)
                    LibraryTab.Albums -> albumsListState.scrollToItem(0)
                    LibraryTab.Artists -> artistsListState.scrollToItem(0)
                    LibraryTab.Playlists -> playlistsListState.scrollToItem(0)
                }
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        val heightOffset = scrollBehavior.state.heightOffset
        val chromeModifier = if (chromeHeightPx == 0) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .height(with(density) { (chromeHeightPx + heightOffset).coerceAtLeast(0f).toDp() })
                .clipToBounds()
        }
        Column(chromeModifier) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(unbounded = true, align = Alignment.Top)
                    .onSizeChanged { size ->
                        if (size.height > 0 && size.height != chromeHeightPx) {
                            chromeHeightPx = size.height
                            scrollBehavior.state.heightOffsetLimit = -size.height.toFloat()
                        }
                    }
                    .offset {
                        IntOffset(0, if (chromeHeightPx == 0) 0 else heightOffset.roundToInt())
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Library", style = MaterialTheme.typography.headlineMedium)
                    IconButton(
                        onClick = container::refreshLocalLibrary,
                        enabled = localState != ProviderState.LOADING,
                    ) {
                        if (localState == ProviderState.LOADING) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Rescan music folders",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
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
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp),
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
                if (showSpotifyError) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            spotifyError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { dismissSpotifyError = true }) {
                            Text("Dismiss")
                        }
                    }
                }
                TabRow(selectedTabIndex = tab.ordinal) {
                    LibraryTab.entries.forEach { item ->
                        Tab(
                            selected = tab == item,
                            onClick = { tabName = item.name },
                            text = { Text(item.name) },
                        )
                    }
                }
            }
        }
        when (tab) {
            LibraryTab.Songs -> {
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
                            else -> "Local files and Spotify likes show up here after a scan or Spotify refresh."
                        },
                    )
                } else {
                    LazyColumn(
                        state = songsListState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                    ) {
                        stickyHeader(key = "ledger") {
                            LibraryLedger(
                                countLine = songsCountLine(
                                    visible = songs.size,
                                    total = queueSongs.size,
                                    queryActive = needle.isNotEmpty(),
                                    favoritesOnly = favoritesOnly,
                                ),
                                localCount = localInQueue,
                                spotifyCount = spotifyInQueue,
                                localOnly = localOnly,
                                favoritesOnly = favoritesOnly,
                                localScanning = localState == ProviderState.LOADING,
                                spotifyConnected = spotifyConnected,
                                spotifyLoading = spotifyLoading,
                                onRefreshSpotify = {
                                    dismissSpotifyError = false
                                    scope.launch { container.refreshSpotifyLibrary() }
                                },
                                sortLabel = settings.librarySongSort.shortLabel(),
                                sortMenuOpen = sortMenuOpen,
                                onSortMenuOpen = { sortMenuOpen = true },
                                onSortMenuDismiss = { sortMenuOpen = false },
                                onSortSelected = { sort ->
                                    sortMenuOpen = false
                                    scope.launch { container.updateSettings { it.copy(librarySongSort = sort) } }
                                },
                                currentSort = settings.librarySongSort,
                                playLabel = if (favoritesOnly) "Play favorites" else "Play all",
                                onPlay = { onPlayTracks(songs, 0) },
                                showActions = true,
                            )
                        }
                        items(songs, key = { it.canonicalId }) { track ->
                            TrackRow(
                                track = track,
                                compact = true,
                                onClick = {
                                    val index = queueSongs.indexOfFirst { it.canonicalId == track.canonicalId }
                                        .coerceAtLeast(0)
                                    onPlayTracks(queueSongs, index)
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                                trailing = {
                                    LibraryTrackTrailing(
                                        favorite = track.canonicalId in favorites,
                                        track = track,
                                    )
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
                            else -> "Albums appear after a local library scan."
                        },
                    )
                } else {
                    LazyColumn(
                        state = albumsListState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                    ) {
                        stickyHeader(key = "ledger") {
                            LibraryLedger(
                                countLine = countNoun(filteredAlbums.size, "album", "albums"),
                                localCount = 0,
                                spotifyCount = 0,
                                localOnly = true,
                                favoritesOnly = favoritesOnly,
                                localScanning = false,
                                spotifyConnected = false,
                                spotifyLoading = false,
                                onRefreshSpotify = {},
                                sortLabel = "",
                                sortMenuOpen = false,
                                onSortMenuOpen = {},
                                onSortMenuDismiss = {},
                                onSortSelected = {},
                                currentSort = settings.librarySongSort,
                                playLabel = "",
                                onPlay = {},
                                showActions = false,
                            )
                        }
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
                    LazyColumn(
                        state = artistsListState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                    ) {
                        stickyHeader(key = "ledger") {
                            LibraryLedger(
                                countLine = countNoun(filteredArtists.size, "artist", "artists"),
                                localCount = 0,
                                spotifyCount = 0,
                                localOnly = true,
                                favoritesOnly = favoritesOnly,
                                localScanning = false,
                                spotifyConnected = false,
                                spotifyLoading = false,
                                onRefreshSpotify = {},
                                sortLabel = "",
                                sortMenuOpen = false,
                                onSortMenuOpen = {},
                                onSortMenuDismiss = {},
                                onSortSelected = {},
                                currentSort = settings.librarySongSort,
                                playLabel = "",
                                onPlay = {},
                                showActions = false,
                            )
                        }
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
                            else -> "Connect Spotify and refresh to load your playlists."
                        },
                    )
                } else {
                    LazyColumn(
                        state = playlistsListState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                    ) {
                        stickyHeader(key = "ledger") {
                            LibraryLedger(
                                countLine = countNoun(filteredPlaylists.size, "playlist", "playlists"),
                                localCount = 0,
                                spotifyCount = 0,
                                localOnly = true,
                                favoritesOnly = favoritesOnly,
                                localScanning = false,
                                spotifyConnected = false,
                                spotifyLoading = false,
                                onRefreshSpotify = {},
                                sortLabel = "",
                                sortMenuOpen = false,
                                onSortMenuOpen = {},
                                onSortMenuDismiss = {},
                                onSortSelected = {},
                                currentSort = settings.librarySongSort,
                                playLabel = "",
                                onPlay = {},
                                showActions = false,
                            )
                        }
                        if (playlistError != null) {
                            item(key = "playlist-error") {
                                Text(
                                    playlistError!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                            }
                        }
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

@Composable
private fun LibraryLedger(
    countLine: String,
    localCount: Int,
    spotifyCount: Int,
    localOnly: Boolean,
    favoritesOnly: Boolean,
    localScanning: Boolean,
    spotifyConnected: Boolean,
    spotifyLoading: Boolean,
    onRefreshSpotify: () -> Unit,
    sortLabel: String,
    sortMenuOpen: Boolean,
    onSortMenuOpen: () -> Unit,
    onSortMenuDismiss: () -> Unit,
    onSortSelected: (TrackSort) -> Unit,
    currentSort: TrackSort,
    playLabel: String,
    onPlay: () -> Unit,
    showActions: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        countLine,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            localScanning -> Text(
                                "Scanning your folders…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> {
                                if (localCount > 0 || localOnly || favoritesOnly) {
                                    SourceTally(LocalSignal, "$localCount local")
                                }
                                if (!localOnly && spotifyConnected) {
                                    Row(
                                        Modifier
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable(enabled = !spotifyLoading, onClick = onRefreshSpotify)
                                            .semantics { contentDescription = "Refresh Spotify library" }
                                            .padding(vertical = 2.dp, horizontal = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        SourceDot(SpotifyGreen)
                                        Text(
                                            if (spotifyLoading) "Spotify loading…" else "$spotifyCount Spotify",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (spotifyLoading) {
                                            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (showActions) {
                    Box {
                        TextButton(
                            onClick = onSortMenuOpen,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(sortLabel, style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = onSortMenuDismiss) {
                            TrackSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.label()) },
                                    onClick = { onSortSelected(sort) },
                                    trailingIcon = {
                                        if (currentSort == sort) {
                                            Icon(Icons.Default.Check, contentDescription = "Selected")
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(
                        onClick = onPlay,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(playLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun LibraryTrackTrailing(favorite: Boolean, track: Track) {
    val playable = track.playableSources().map { it.provider }.toSet()
    Row(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (favorite) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = "App favorite",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        track.sources.map { it.provider }.distinct().forEach { provider ->
            val color = when (provider) {
                ProviderId.LOCAL -> LocalSignal
                ProviderId.SPOTIFY -> SpotifyGreen
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            SourceDot(color, dimmed = provider !in playable && track.requiresNetworkToPlay())
        }
        Text(
            track.durationMs?.takeIf { it > 0 }?.let { formatTime(it) } ?: "--:--",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceTally(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SourceDot(color)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceDot(color: Color, dimmed: Boolean = false) {
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(if (dimmed) color.copy(alpha = 0.4f) else color),
    )
}

private fun songsCountLine(
    visible: Int,
    total: Int,
    queryActive: Boolean,
    favoritesOnly: Boolean,
): String = when {
    favoritesOnly && !queryActive -> countNoun(visible, "favorite", "favorites")
    queryActive -> "$visible of $total ${if (total == 1) "song" else "songs"}"
    else -> countNoun(visible, "song", "songs")
}

private fun countNoun(n: Int, singular: String, plural: String): String =
    "$n ${if (n == 1) singular else plural}"

private fun TrackSort.label(): String = when (this) {
    TrackSort.NAME_ASCENDING -> "Name A to Z"
    TrackSort.NAME_DESCENDING -> "Name Z to A"
    TrackSort.DURATION_ASCENDING -> "Duration shortest first"
    TrackSort.DURATION_DESCENDING -> "Duration longest first"
}

private fun TrackSort.shortLabel(): String = when (this) {
    TrackSort.NAME_ASCENDING -> "A to Z"
    TrackSort.NAME_DESCENDING -> "Z to A"
    TrackSort.DURATION_ASCENDING -> "Shortest"
    TrackSort.DURATION_DESCENDING -> "Longest"
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
