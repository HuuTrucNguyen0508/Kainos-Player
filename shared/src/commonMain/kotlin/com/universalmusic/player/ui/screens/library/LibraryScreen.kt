package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.domain.model.Track

internal enum class LibraryTab { Songs, Albums, Artists, Playlists }

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
    val scope = rememberCoroutineScope()
    val presenter = remember(container) { LibraryPresenter.from(container, scope) }
    val emitted by presenter.state.collectAsState()
    val state = emitted.withQuery(query)
    SideEffect { presenter.setQuery(query) }
    var selectedArtistId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedArtist = state.artists.firstOrNull { it.artist.canonicalId == selectedArtistId }
    LaunchedEffect(tab, state.localOnly, state.favoritesOnly, state.needle) {
        if (tab != LibraryTab.Artists) selectedArtistId = null
    }
    var playlistBusyId by remember { mutableStateOf<String?>(null) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var expandedKainosId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamePlaylistId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deletePlaylistId by remember { mutableStateOf<String?>(null) }
    var createDraft by remember { mutableStateOf<String?>(null) }
    var dismissSpotifyError by rememberSaveable { mutableStateOf(false) }
    val showSpotifyError = !state.localOnly && !state.favoritesOnly && state.spotifyError != null && !dismissSpotifyError

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
    LaunchedEffect(state.songSort, libraryNeedle(query), state.localOnly, state.favoritesOnly) {
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
        LibraryCollapsingHeader(
            scrollBehavior = scrollBehavior,
            chromeHeightPx = chromeHeightPx,
            onChromeHeightPx = { chromeHeightPx = it },
            queryField = queryField,
            onQueryField = { queryField = it },
            searchFocusRequester = searchFocusRequester,
            onSearchFocusChanged = { focused ->
                searchFocused = focused
                if (!focused) typeAheadActive = false
            },
            tab = tab,
            songs = state.songs,
            queueSongs = state.queueSongs,
            onPlayTracks = onPlayTracks,
            localState = state.localState,
            onRefreshLocal = presenter::refreshLocal,
            showSpotifyError = showSpotifyError,
            spotifyError = state.spotifyError,
            onDismissSpotifyError = { dismissSpotifyError = true },
        )
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
                selected = state.localOnly,
                onClick = presenter::toggleLocalOnly,
                label = { Text("Local files only") },
                leadingIcon = if (state.localOnly) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
            FilterChip(
                selected = state.favoritesOnly,
                onClick = presenter::toggleFavoritesOnly,
                label = { Text("Favorites only") },
                leadingIcon = if (state.favoritesOnly) {
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
            LibraryTab.Songs -> LibrarySongsTab(
                state = state,
                listState = songsListState,
                sortMenuOpen = sortMenuOpen,
                onSortMenuOpen = { sortMenuOpen = true },
                onSortMenuDismiss = { sortMenuOpen = false },
                onSortSelected = { sort ->
                    sortMenuOpen = false
                    presenter.setSort(sort)
                },
                onPlayTracks = onPlayTracks,
                onRefreshSpotify = {
                    dismissSpotifyError = false
                    presenter.refreshSpotify()
                },
                trackAvailability = presenter::trackAvailability,
            )
            LibraryTab.Albums -> LibraryAlbumsTab(
                state = state,
                listState = albumsListState,
                presenter = presenter,
                onPlayTracks = onPlayTracks,
            )
            LibraryTab.Artists -> LibraryArtistsTab(
                state = state,
                listState = artistsListState,
                selectedArtist = selectedArtist,
                onSelectedArtistId = { selectedArtistId = it },
                onPlayTracks = onPlayTracks,
                trackAvailability = presenter::trackAvailability,
            )
            LibraryTab.Playlists -> LibraryPlaylistsTab(
                state = state,
                presenter = presenter,
                listState = playlistsListState,
                scope = scope,
                onPlayTracks = onPlayTracks,
                playlistBusyId = playlistBusyId,
                onPlaylistBusyId = { playlistBusyId = it },
                playlistError = playlistError,
                onPlaylistError = { playlistError = it },
                expandedKainosId = expandedKainosId,
                onExpandedKainosId = { expandedKainosId = it },
                renamePlaylistId = renamePlaylistId,
                onRenamePlaylistId = { renamePlaylistId = it },
                renameDraft = renameDraft,
                onRenameDraft = { renameDraft = it },
                deletePlaylistId = deletePlaylistId,
                onDeletePlaylistId = { deletePlaylistId = it },
                createDraft = createDraft,
                onCreateDraft = { createDraft = it },
            )
        }
    }
}
