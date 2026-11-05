package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.components.KainosChip
import com.universalmusic.player.ui.theme.Accents
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.runtime.saveable.listSaver
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
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

internal enum class LibraryTab { Songs, Albums, Artists, Playlists, Folders }

/** Where each visited folder was scrolled to ("" is the list of roots), so going up lands where it left. */
private val FolderScrollSaver = listSaver<MutableMap<String, Pair<Int, Int>>, String>(
    save = { positions -> positions.flatMap { (id, at) -> listOf(id, at.first.toString(), at.second.toString()) } },
    restore = { flat -> flat.chunked(3).associateTo(mutableMapOf()) { (id, index, offset) -> id to (index.toInt() to offset.toInt()) } },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(
    typeAhead: StateFlow<String>,
    consumeTypeAhead: () -> String,
    presenter: LibraryPresenter,
    onPlayTracks: (List<Track>, Int) -> Unit,
    trackActions: TrackActionsPresenter,
    onPlayOffline: (List<Track>, Int) -> Unit = onPlayTracks,
    onShuffleTracks: (List<Track>) -> Unit = { tracks -> onPlayTracks(tracks.shuffled(), 0) },
    /** Library hands the app what system back / Escape should do inside it, or null when nothing. */
    onInnerBack: ((() -> Unit)?) -> Unit = {},
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
    val foldersListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    val emitted by presenter.state.collectAsState()
    val state = remember(emitted, query) { emitted.withQuery(query) }
    SideEffect { presenter.setQuery(query) }
    // Rows keep the full tracks (hearts, playlists, history); only what reaches the player is cut to disk.
    val playSongs = if (state.readyOffline) onPlayOffline else onPlayTracks
    var selectedArtistId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedArtist = state.artists.firstOrNull { it.artist.canonicalId == selectedArtistId }
    LaunchedEffect(tab, state.localOnly, state.favoritesOnly, state.readyOffline, state.needle) {
        if (tab != LibraryTab.Artists) selectedArtistId = null
    }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    val folderTrail = remember(state.folders, folderId) { state.folders.trailTo(folderId) }
    val folderScroll = rememberSaveable(saver = FolderScrollSaver) { mutableMapOf() }
    var pendingFolderScroll by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    fun openFolder(id: String?) {
        val shownId = folderTrail?.last()?.id.orEmpty()
        folderScroll[shownId] = foldersListState.firstVisibleItemIndex to foldersListState.firstVisibleItemScrollOffset
        val goingUp = id == null || folderTrail?.any { it.id == id } == true
        pendingFolderScroll = if (goingUp) folderScroll[id.orEmpty()] ?: (0 to 0) else 0 to 0
        folderId = id
    }
    LaunchedEffect(folderId) {
        val (index, offset) = pendingFolderScroll ?: return@LaunchedEffect
        pendingFolderScroll = null
        foldersListState.scrollToItem(index, offset)
    }
    val folderUp: (() -> Unit)? = folderTrail
        ?.takeIf { tab == LibraryTab.Folders }
        ?.let { trail -> { openFolder(trail.getOrNull(trail.lastIndex - 1)?.id) } }
    DisposableEffect(folderUp != null, folderTrail?.last()?.id) {
        onInnerBack(folderUp)
        onDispose { onInnerBack(null) }
    }
    var playlistBusyId by remember { mutableStateOf<String?>(null) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var expandedKainosId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamePlaylistId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deletePlaylistId by remember { mutableStateOf<String?>(null) }
    var createDraft by remember { mutableStateOf<String?>(null) }
    var dismissSpotifyError by rememberSaveable { mutableStateOf(false) }
    val showSpotifyError = !state.localOnly && !state.favoritesOnly && !state.readyOffline &&
        state.spotifyError != null && !dismissSpotifyError

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
    LaunchedEffect(typeAhead) {
        typeAhead.collect { pending ->
            if (pending.isEmpty()) return@collect
            while (true) {
                val typed = consumeTypeAhead()
                if (typed.isEmpty()) break
                val next = (if (typeAheadActive || searchFocused) queryField.text else "") + typed
                queryField = TextFieldValue(next, selection = TextRange(next.length))
                typeAheadActive = true
                tabName = LibraryTab.Songs.name
                scrollBehavior.state.heightOffset = 0f
                // Frame callbacks run before composition. Let the field receive its new value
                // before handing it subsequent keystrokes, including any queued during that frame.
                withFrameNanos { }
                withFrameNanos { }
            }
            searchFocusRequester.requestFocus()
        }
    }

    // Skip the first run so returning to Library keeps scroll; later filter/sort changes
    // jump to top. Do not combine LaunchedEffect(filterKeys) with snapshotFlow.drop(1):
    // restarting the effect made drop(1) discard the filter change itself.
    var skipFilterScrollReset by remember { mutableStateOf(true) }
    LaunchedEffect(state.songSort, libraryNeedle(query), state.localOnly, state.favoritesOnly, state.readyOffline) {
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
            LibraryTab.Folders -> foldersListState.scrollToItem(0)
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
            onPlayTracks = playSongs,
            localState = state.localState,
            onRefreshLocal = presenter::refreshLocal,
            showSpotifyError = showSpotifyError,
            spotifyError = state.spotifyError,
            onDismissSpotifyError = { dismissSpotifyError = true },
        )
        // Keep filters + tabs outside the collapsing chrome. On short lists (Favorites)
        // the header can fully hide; without this the Favorites chip is unreachable.
        // Three chips overflow a 360dp phone; scroll them sideways rather than wrap and push the list down.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KainosChip(
                selected = state.localOnly,
                onClick = presenter::toggleLocalOnly,
                label = "Local files only",
                accent = Accents.local,
                leadingIcon = Icons.Default.SdStorage,
            )
            KainosChip(
                selected = state.favoritesOnly,
                onClick = presenter::toggleFavoritesOnly,
                label = "Favorites only",
                accent = Accents.heart,
                leadingIcon = Icons.Default.Favorite,
            )
            KainosChip(
                selected = state.readyOffline,
                onClick = presenter::toggleReadyOffline,
                label = "Ready offline",
                accent = Accents.cached,
                leadingIcon = Icons.Default.OfflinePin,
            )
        }
        val tabs = @Composable {
            LibraryTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tabName = item.name },
                    text = { Text(item.name, maxLines = 1) },
                )
            }
        }
        // Five labels do not fit a phone's width at equal shares; let them scroll there instead of wrap.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 520.dp) {
                PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp, tabs = tabs)
            } else {
                TabRow(selectedTabIndex = tab.ordinal, tabs = tabs)
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
                onPlayTracks = playSongs,
                trackActions = trackActions,
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
                trackActions = trackActions,
                trackAvailability = presenter::trackAvailability,
            )
            LibraryTab.Playlists -> LibraryPlaylistsTab(
                state = state,
                presenter = presenter,
                listState = playlistsListState,
                scope = scope,
                onPlayTracks = onPlayTracks,
                trackActions = trackActions,
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
            LibraryTab.Folders -> LibraryFoldersTab(
                state = state,
                listState = foldersListState,
                trail = folderTrail,
                onOpenFolder = ::openFolder,
                onPlayTracks = onPlayTracks,
                onShuffleTracks = onShuffleTracks,
                onPinFolder = { folder, pin ->
                    if (pin) {
                        presenter.pinHome(HomePinKind.LOCAL_FOLDER, folder.id, folder.name, "Local folder")
                    } else {
                        presenter.unpinHome(HomePinKind.LOCAL_FOLDER, folder.id)
                    }
                },
                trackActions = trackActions,
                trackAvailability = presenter::trackAvailability,
            )
        }
    }
}
