package com.universalmusic.player.ui.screens

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import com.universalmusic.player.ui.reportsTextInputFocus
import com.universalmusic.player.domain.matching.FuzzyMatcher
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
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
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.requiresNetworkToPlay
import com.universalmusic.player.data.playlist.playlistEntryAvailabilityLabel
import com.universalmusic.player.data.playlist.toDomainPlaylist
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
import kotlinx.coroutines.launch

private enum class LibraryTab { Songs, Albums, Artists, Playlists }

private data class LibraryArtistEntry(
    val artist: Artist,
    val tracks: List<Track>,
)

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
    var queryField by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val query = queryField.text
    val searchFocusRequester = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    // True from the first type-ahead keystroke until the field loses focus, so a burst of
    // keys typed before focus lands appends instead of restarting the search.
    var typeAheadActive by remember { mutableStateOf(false) }
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
    val kainosPlaylistRows by container.kainosPlaylists.playlists.collectAsState()
    val homePins by container.library.homePins.collectAsState()
    val spotifyLoading by container.spotifyLibraryLoading.collectAsState()
    val pinnedKeys = remember(homePins) {
        homePins.map { "${it.kind.name}|${it.targetId}" }.toSet()
    }

    val spotifyError by container.spotifyLibraryError.collectAsState()
    val spotifyState by container.spotify.state.collectAsState()
    val downloads by container.heartedAudio.downloads.collectAsState()
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
    val songSearchKeys = remember(queueSongs) { queueSongs.map { it.librarySearchKey() } }
    val songs = remember(queueSongs, songSearchKeys, needle) {
        if (needle.isEmpty()) {
            queueSongs
        } else {
            // Ranked like fzf; ties keep the chosen sort order (sortedByDescending is stable).
            queueSongs.indices
                .mapNotNull { i -> songSearchKeys[i].score(needle)?.let { i to it } }
                .sortedByDescending { it.second }
                .map { queueSongs[it.first] }
        }
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
            .flatMap { track -> track.artists.map { artist -> artist to track } }
            .groupBy { (artist, _) -> artist.canonicalId }
            .map { (id, pairs) ->
                val ref = pairs.first().first
                LibraryArtistEntry(
                    artist = Artist(
                        canonicalId = id,
                        name = ref.name,
                        artwork = ref.artwork,
                        sources = listOf(ProviderEntityRef(ProviderId.LOCAL, id)),
                    ),
                    tracks = pairs.map { it.second }.distinctBy(Track::canonicalId),
                )
            }
            .sortedBy { it.artist.name.lowercase() }
    }
    val filteredArtists = remember(localArtists, needle) {
        if (needle.isEmpty()) {
            localArtists
        } else {
            localArtists.filter { FuzzyMatcher.matches(needle, TrackNormalizer.fold(it.artist.name)) }
        }
    }
    var selectedArtistId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedArtist = remember(filteredArtists, selectedArtistId) {
        filteredArtists.firstOrNull { it.artist.canonicalId == selectedArtistId }
    }
    LaunchedEffect(tab, localOnly, favoritesOnly, needle) {
        if (tab != LibraryTab.Artists) selectedArtistId = null
    }
    val filteredKainosPlaylists = remember(kainosPlaylistRows, needle, favoritesOnly) {
        if (favoritesOnly) return@remember emptyList()
        val domain = kainosPlaylistRows.map { it.toDomainPlaylist() }
        if (needle.isEmpty()) domain
        else domain.filter { it.matchesLibraryQuery(needle) }
    }
    val filteredPlaylists = remember(spotifyPlaylists, needle, localOnly, favoritesOnly) {
        if (localOnly || favoritesOnly) return@remember emptyList()
        val filtered = if (needle.isEmpty()) spotifyPlaylists else spotifyPlaylists.filter { it.matchesLibraryQuery(needle) }
        filtered.sortedByDescending { it.isDiscoverWeekly() }
    }
    var playlistBusyId by remember { mutableStateOf<String?>(null) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var expandedKainosId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamePlaylistId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deletePlaylistId by remember { mutableStateOf<String?>(null) }
    var createDraft by remember { mutableStateOf<String?>(null) }
    var dismissSpotifyError by rememberSaveable { mutableStateOf(false) }
    val showSpotifyError = !localOnly && !favoritesOnly && spotifyError != null && !dismissSpotifyError
    val spotifyConnected = spotifyState == ProviderState.AVAILABLE || spotifyState == ProviderState.RATE_LIMITED

    val localInQueue = remember(queueSongs) { queueSongs.count { it.sources.any { s -> s.provider == ProviderId.LOCAL } } }
    val spotifyInQueue = remember(queueSongs) { queueSongs.count { it.sources.any { s -> s.provider == ProviderId.SPOTIFY } } }

    val density = LocalDensity.current
    // Collapse on the way down; expand only after the list is back at the top so upward
    // scroll moves tracks first (enterAlways stole those gestures for the chrome).
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var chromeHeightPx by remember { mutableIntStateOf(0) }
    SideEffect {
        if (chromeHeightPx > 0) {
            scrollBehavior.state.heightOffsetLimit = -chromeHeightPx.toFloat()
        }
    }
    LaunchedEffect(tab) {
        scrollBehavior.state.heightOffset = 0f
    }

    // Desktop type-to-search: keys typed with nothing focused land here (see Main.kt).
    val focusManager = LocalFocusManager.current
    val pendingTypeAhead by container.libraryTypeAhead.collectAsState()
    LaunchedEffect(pendingTypeAhead) {
        if (pendingTypeAhead.isEmpty()) return@LaunchedEffect
        val typed = container.consumeLibraryTypeAhead()
        if (typed.isEmpty()) return@LaunchedEffect
        val next = (if (typeAheadActive || searchFocused) queryField.text else "") + typed
        queryField = TextFieldValue(next, selection = TextRange(next.length))
        typeAheadActive = true
        tabName = LibraryTab.Songs.name
        scrollBehavior.state.heightOffset = 0f
        withFrameNanos { } // let the re-expanded header compose before focusing it
        runCatching { searchFocusRequester.requestFocus() }
    }

    // Skip the first run so returning to Library keeps scroll; later filter/sort changes
    // jump to top. Do not combine LaunchedEffect(filterKeys) with snapshotFlow.drop(1):
    // restarting the effect made drop(1) discard the filter change itself.
    var skipFilterScrollReset by remember { mutableStateOf(true) }
    LaunchedEffect(settings.librarySongSort, needle, localOnly, favoritesOnly) {
        if (skipFilterScrollReset) {
            skipFilterScrollReset = false
            return@LaunchedEffect
        }
        scrollBehavior.state.heightOffset = 0f
        when (tab) {
            LibraryTab.Songs -> songsListState.scrollToItem(0)
            LibraryTab.Albums -> albumsListState.scrollToItem(0)
            LibraryTab.Artists -> artistsListState.scrollToItem(0)
            LibraryTab.Playlists -> playlistsListState.scrollToItem(0)
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
                    value = queryField,
                    onValueChange = { queryField = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                        .focusRequester(searchFocusRequester)
                        .onFocusChanged {
                            searchFocused = it.isFocused
                            if (!it.isFocused) typeAheadActive = false
                        }
                        .reportsTextInputFocus()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    val top = songs.firstOrNull()
                                    if (tab != LibraryTab.Songs || top == null) return@onPreviewKeyEvent false
                                    val index = queueSongs.indexOfFirst { it.canonicalId == top.canonicalId }
                                        .coerceAtLeast(0)
                                    onPlayTracks(queueSongs, index)
                                    focusManager.clearFocus()
                                    true
                                }
                                Key.Escape -> {
                                    queryField = TextFieldValue("")
                                    focusManager.clearFocus()
                                    true
                                }
                                else -> false
                            }
                        },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Search library") },
                    // Phone keyboard: the Search key just hides the keyboard so the ranked results show.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { queryField = TextFieldValue("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear library search")
                            }
                        }
                    },
                )
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
            }
        }
        // Keep filters + tabs outside the collapsing chrome. On short lists (Favorites)
        // the header can fully hide; without this the Favorites chip is unreachable.
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
        TabRow(selectedTabIndex = tab.ordinal) {
            LibraryTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tabName = item.name },
                    text = { Text(item.name) },
                )
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
                                availability = remember(track, downloads) {
                                    container.trackAvailability(track)
                                },
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
                            val albumPinned =
                                "${HomePinKind.ALBUM.name}|${album.canonicalId}" in pinnedKeys
                            AlbumRow(
                                album = album,
                                onClick = { if (album.tracks.isNotEmpty()) onPlayTracks(album.tracks, 0) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                                trailing = {
                                    IconButton(
                                        onClick = {
                                            if (albumPinned) {
                                                container.library.unpinHome(
                                                    HomePinKind.ALBUM,
                                                    album.canonicalId,
                                                )
                                            } else {
                                                container.library.pinHome(
                                                    kind = HomePinKind.ALBUM,
                                                    targetId = album.canonicalId,
                                                    title = album.title,
                                                    subtitle = album.artists.joinToString { it.name }
                                                        .ifBlank { "Album" },
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
            LibraryTab.Artists -> {
                val artistDetail = selectedArtist
                if (artistDetail != null) {
                    val artistTracks = remember(artistDetail, settings.librarySongSort) {
                        settings.librarySongSort.sort(artistDetail.tracks)
                    }
                    if (artistTracks.isEmpty()) {
                        EmptyState(
                            "No tracks",
                            "Nothing left for ${artistDetail.artist.name} with the current filters.",
                        )
                    } else {
                        LazyColumn(
                            state = artistsListState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
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
                                        IconButton(onClick = { selectedArtistId = null }) {
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
                                    availability = remember(track, downloads) {
                                        container.trackAvailability(track)
                                    },
                                    onClick = {
                                        val index = artistTracks.indexOfFirst { it.canonicalId == track.canonicalId }
                                            .coerceAtLeast(0)
                                        onPlayTracks(artistTracks, index)
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
                } else if (filteredArtists.isEmpty()) {
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
                        items(filteredArtists, key = { it.artist.canonicalId }) { entry ->
                            Surface(
                                onClick = { selectedArtistId = entry.artist.canonicalId },
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
            LibraryTab.Playlists -> {
                val showKainos = !favoritesOnly
                val showSpotify = !localOnly && !favoritesOnly
                val totalCount = (if (showKainos) filteredKainosPlaylists.size else 0) +
                    (if (showSpotify) filteredPlaylists.size else 0)
                if (createDraft != null) {
                    AlertDialog(
                        onDismissRequest = { createDraft = null },
                        title = { Text("New Kainos playlist") },
                        text = {
                            OutlinedTextField(
                                value = createDraft!!,
                                onValueChange = { createDraft = it },
                                singleLine = true,
                                label = { Text("Name") },
                                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val name = createDraft?.trim().orEmpty()
                                    if (name.isNotBlank()) {
                                        container.kainosPlaylists.create(name)
                                    }
                                    createDraft = null
                                },
                            ) { Text("Create") }
                        },
                        dismissButton = {
                            TextButton(onClick = { createDraft = null }) { Text("Cancel") }
                        },
                    )
                }
                if (renamePlaylistId != null) {
                    AlertDialog(
                        onDismissRequest = { renamePlaylistId = null },
                        title = { Text("Rename playlist") },
                        text = {
                            OutlinedTextField(
                                value = renameDraft,
                                onValueChange = { renameDraft = it },
                                singleLine = true,
                                label = { Text("Name") },
                                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val id = renamePlaylistId
                                    if (id != null) container.kainosPlaylists.rename(id, renameDraft)
                                    renamePlaylistId = null
                                },
                            ) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = { renamePlaylistId = null }) { Text("Cancel") }
                        },
                    )
                }
                if (deletePlaylistId != null) {
                    AlertDialog(
                        onDismissRequest = { deletePlaylistId = null },
                        title = { Text("Delete playlist?") },
                        text = { Text("Removes the Kainos playlist on this device. Home sync will tombstone it for peers.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    deletePlaylistId?.let { container.kainosPlaylists.delete(it) }
                                    if (expandedKainosId == deletePlaylistId) expandedKainosId = null
                                    deletePlaylistId = null
                                },
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { deletePlaylistId = null }) { Text("Cancel") }
                        },
                    )
                }
                if (totalCount == 0 && needle.isEmpty() && !showKainos) {
                    EmptyState(
                        "Playlists hidden",
                        "Favorites only hides playlists. Turn it off to browse Kainos and Spotify playlists.",
                    )
                } else if (totalCount == 0) {
                    EmptyState(
                        if (needle.isEmpty()) {
                            when {
                                localOnly -> "No Kainos playlists yet"
                                else -> "No playlists"
                            }
                        } else {
                            "No playlists match \"$query\""
                        },
                        when {
                            needle.isNotEmpty() -> "Try another playlist name."
                            localOnly -> "Create a Kainos playlist, or turn off Local files only to see Spotify playlists."
                            else -> "Create a Kainos playlist or connect Spotify and refresh."
                        },
                    )
                    if (showKainos && needle.isEmpty()) {
                        TextButton(
                            onClick = { createDraft = "My playlist" },
                            modifier = Modifier.padding(horizontal = 24.dp),
                        ) { Text("New playlist") }
                    }
                } else {
                    LazyColumn(
                        state = playlistsListState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                    ) {
                        stickyHeader(key = "ledger") {
                            LibraryLedger(
                                countLine = countNoun(totalCount, "playlist", "playlists"),
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
                                    TextButton(onClick = { createDraft = "My playlist" }) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("New")
                                    }
                                }
                            }
                            if (filteredKainosPlaylists.isEmpty()) {
                                item(key = "kainos-empty") {
                                    Text(
                                        if (needle.isEmpty()) "No Kainos playlists yet. Save a queue or create one."
                                        else "No Kainos playlists match.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            items(filteredKainosPlaylists, key = { it.canonicalId }) { playlist ->
                                val expanded = expandedKainosId == playlist.canonicalId
                                val kainosIndex = filteredKainosPlaylists.indexOfFirst { it.canonicalId == playlist.canonicalId }
                                val kainosPinned =
                                    "${HomePinKind.KAINOS_PLAYLIST.name}|${playlist.canonicalId}" in pinnedKeys
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expandedKainosId =
                                                if (expanded) null else playlist.canonicalId
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
                                                container.library.unpinHome(
                                                    HomePinKind.KAINOS_PLAYLIST,
                                                    playlist.canonicalId,
                                                )
                                            } else {
                                                container.library.pinHome(
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
                                                val ids = filteredKainosPlaylists.map { it.canonicalId }.toMutableList()
                                                ids.removeAt(kainosIndex)
                                                ids.add(kainosIndex - 1, playlist.canonicalId)
                                                // Preserve filtered-out playlists after the visible reorder.
                                                val hidden = kainosPlaylistRows.map { it.id }
                                                    .filterNot { it in ids }
                                                container.kainosPlaylists.reorderPlaylists(ids + hidden)
                                            }
                                        },
                                        enabled = kainosIndex > 0,
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                    }
                                    IconButton(
                                        onClick = {
                                            if (kainosIndex >= 0 && kainosIndex < filteredKainosPlaylists.lastIndex) {
                                                val ids = filteredKainosPlaylists.map { it.canonicalId }.toMutableList()
                                                ids.removeAt(kainosIndex)
                                                ids.add(kainosIndex + 1, playlist.canonicalId)
                                                val hidden = kainosPlaylistRows.map { it.id }
                                                    .filterNot { it in ids }
                                                container.kainosPlaylists.reorderPlaylists(ids + hidden)
                                            }
                                        },
                                        enabled = kainosIndex >= 0 && kainosIndex < filteredKainosPlaylists.lastIndex,
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
                                            renamePlaylistId = playlist.canonicalId
                                            renameDraft = playlist.title
                                        },
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename ${playlist.title}")
                                    }
                                    IconButton(onClick = { deletePlaylistId = playlist.canonicalId }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete ${playlist.title}")
                                    }
                                }
                                if (expanded) {
                                    TextButton(
                                        onClick = {
                                            val tracks = container.player.queue.queue.value.playbackOrder()
                                                .mapNotNull { idx ->
                                                    container.player.queue.queue.value.items.getOrNull(idx)?.track
                                                }
                                            if (tracks.isNotEmpty()) {
                                                container.kainosPlaylists.addTracks(playlist.canonicalId, tracks)
                                            }
                                        },
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    ) { Text("Add current queue") }
                                    playlist.tracks.forEachIndexed { index, track ->
                                        val entryId = kainosPlaylistRows
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
                                                                container.kainosPlaylists.removeEntry(
                                                                    playlist.canonicalId,
                                                                    entryId,
                                                                )
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
                                        playlistError!!,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            if (filteredPlaylists.isEmpty()) {
                                item(key = "spotify-empty") {
                                    Text(
                                        if (spotifyConnected) "No Spotify playlists loaded. Refresh from Songs."
                                        else "Connect Spotify in Settings to load provider playlists.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            items(filteredPlaylists, key = { it.canonicalId }) { playlist ->
                                val busy = playlistBusyId == playlist.canonicalId
                                val subtitle = when {
                                    busy -> "Loading…"
                                    playlist.isDiscoverWeekly() -> "Made for you · Play in Kainos"
                                    playlist.source.provider == ProviderId.SPOTIFY -> "Spotify · Play in Kainos"
                                    else -> playlist.description ?: playlist.ownerName.orEmpty()
                                }
                                val spotifyPinned =
                                    "${HomePinKind.PROVIDER_PLAYLIST.name}|${playlist.canonicalId}" in pinnedKeys
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
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            playlist.title,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
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
                                                container.library.unpinHome(
                                                    HomePinKind.PROVIDER_PLAYLIST,
                                                    playlist.canonicalId,
                                                )
                                            } else {
                                                container.library.pinHome(
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

/** Folded title and full "title artists album" text, computed once per list for type-to-search. */
private class LibrarySearchKey(val title: String, val full: String) {
    fun score(needle: String): Int? {
        val score = FuzzyMatcher.score(needle, full) ?: return null
        // Prefer hits that land entirely in the title over ones spread across artist/album.
        return if (FuzzyMatcher.matches(needle, title)) score + TITLE_BONUS else score
    }

    private companion object {
        const val TITLE_BONUS = 50
    }
}

private fun Track.librarySearchKey(): LibrarySearchKey = LibrarySearchKey(
    title = TrackNormalizer.fold(title),
    full = TrackNormalizer.fold(
        listOfNotNull(title, artists.joinToString(" ") { it.name }, album?.title).joinToString(" "),
    ),
)

private fun Track.matchesLibraryQuery(needle: String): Boolean =
    needle.isEmpty() || librarySearchKey().score(needle) != null

private fun Album.matchesLibraryQuery(needle: String): Boolean {
    if (needle.isEmpty()) return true
    val text = TrackNormalizer.fold(listOf(title, artists.joinToString(" ") { it.name }).joinToString(" "))
    return FuzzyMatcher.matches(needle, text) || tracks.any { it.matchesLibraryQuery(needle) }
}

private fun Playlist.matchesLibraryQuery(needle: String): Boolean {
    if (needle.isEmpty()) return true
    val text = TrackNormalizer.fold(listOfNotNull(title, ownerName, description).joinToString(" "))
    return FuzzyMatcher.matches(needle, text)
}
