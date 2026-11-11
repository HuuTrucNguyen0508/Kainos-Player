package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.reportsTextInputFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.ui.components.LocalTrackTable
import com.universalmusic.player.ui.components.TrackTableHeader
import com.universalmusic.player.ui.components.namesAlbums
import com.universalmusic.player.ui.components.trackTableColumns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.universalmusic.player.platform.openUrl
import com.universalmusic.player.platform.encodeUrl
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderSearchStatus
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.ProviderStatusRow

@Composable
internal fun SearchScreen(
    presenter: SearchPresenter,
    onPlayTrackInList: (List<Track>, Int) -> Unit,
    onPlayPlaylist: (List<Track>, Int, Boolean) -> Unit,
    trackActions: TrackActionsPresenter,
    /** Album, artist and playlist pages opened from Search, over the results or beside them. */
    pages: CollectionStack,
    requestFocus: Boolean = false,
    onOpenSettings: (SettingsTarget) -> Unit = {},
    openLink: (String) -> Unit = ::openUrl,
) {
    val state by presenter.state.collectAsState()
    val query = state.query
    val loading = state.loading
    val result = state.result
    val error = state.error
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val focusRequester = remember { FocusRequester() }
    val spotify = state.spotify
    val youtube = state.youtube
    val downloads = state.downloads
    val openPages by pages.stack.collectAsState()

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    val playlists = state.playlists
    val albums = state.albums
    val artists = state.artists
    val tracks = state.tracks
    val showingResults = state.searchable.isNotEmpty() && error == null && query.isNotBlank() &&
        !(loading && tracks == null) && !(tracks.isNullOrEmpty() && !state.hasCollections)
    val playlistsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val showAlbums = remember(tracks) { tracks.orEmpty().namesAlbums() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val open = openPages.isNotEmpty()
        val split = searchSplit(width, showingResults, !tracks.isNullOrEmpty(), state.hasCollections, open)
        val collectionPage = @Composable { pageWidth: Dp ->
            CollectionHost(
                stack = pages,
                rootBackLabel = "Back to search results",
                trackActions = trackActions,
                availability = presenter::trackAvailability,
                downloads = downloads,
                onPlay = onPlayPlaylist,
                openLink = openLink,
                listWidth = pageWidth,
            )
        }
        // The results keep their scroll while a page is open: their list state is held above this branch.
        if (open && !split) {
            collectionPage(width)
        } else Column(Modifier.fillMaxSize()) {
            val statuses = result?.providerStatuses?.mapValues { it.value.state }
                ?: mapOf(
                    ProviderId.SPOTIFY to spotify,
                    ProviderId.YOUTUBE_MUSIC to youtube,
                )
            val counts = result?.providerStatuses?.values?.joinToString("   ") { statusLine(it) }
            val searchField = @Composable { modifier: Modifier ->
                OutlinedTextField(
                    value = query,
                    onValueChange = { value ->
                        presenter.setQuery(value)
                        // Beside the results, an open page belongs to the old search; a new one closes it.
                        if (split && value != query) pages.clear()
                    },
                    modifier = modifier
                        .focusRequester(focusRequester)
                        .reportsTextInputFocus(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    placeholder = { Text(state.searchHint) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { presenter.setQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                )
            }
            if (width >= SearchCompactHeaderMinWidth) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    searchField(Modifier.width(SearchCompactFieldWidth))
                    ProviderStatusRow(statuses)
                    counts?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (loading && result != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else {
                searchField(Modifier.fillMaxWidth().padding(16.dp))
                if (loading && result != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ProviderStatusRow(statuses, Modifier.padding(horizontal = 20.dp).fillMaxWidth())
                counts?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            result?.providerStatuses?.values?.filter { it.message != null }?.forEach { status ->
                Text(
                    "${status.provider.displayName}: ${status.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            val trackList = tracks.orEmpty()
            val heading = state.searchable.joinToString(" and ") { it.title }
            val trackItems: LazyListScope.() -> Unit = {
                item(key = "results-heading") {
                    SectionRule(heading, Modifier.padding(horizontal = 20.dp))
                }
                searchTrackRows(trackList, presenter, downloads, trackActions, onPlayTrackInList, openLink)
            }
            val collectionItems: LazyListScope.(Boolean) -> Unit = { first ->
                var firstSection = first
                fun LazyListScope.heading(key: String, title: String) {
                    val top = !firstSection
                    firstSection = false
                    item(key = key) {
                        SectionRule(
                            title,
                            Modifier.padding(horizontal = 20.dp).then(if (top) Modifier.padding(top = 12.dp) else Modifier),
                        )
                    }
                }
                if (artists.isNotEmpty()) heading("artists-heading", "Artists")
                itemsIndexed(artists, key = { index, artist -> "artist:${artist.canonicalId}:$index" }) { _, artist ->
                    ArtistResultRow(artist, onClick = { pages.openFresh(artist.page()) }, modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (albums.isNotEmpty()) heading("albums-heading", "Albums")
                itemsIndexed(albums, key = { index, album -> "album:${album.canonicalId}:$index" }) { _, album ->
                    AlbumResultRow(album, onClick = { pages.openFresh(album.page()) }, modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (playlists.isNotEmpty()) heading("playlists-heading", "Playlists")
                itemsIndexed(playlists, key = { index, playlist -> "playlist:${playlist.canonicalId}:$index" }) { _, playlist ->
                    PlaylistResultRow(
                        playlist = playlist,
                        onClick = { pages.openFresh(CollectionRef.ProviderPlaylist(playlist)) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            val statusContent: @Composable () -> Unit = {
                when {
                    state.searchable.isEmpty() -> Column {
                        EmptyState(
                            "Search needs Spotify or YouTube",
                            "Search covers those two services. Your local files have their own search in Library.",
                        )
                        Row(
                            Modifier.padding(horizontal = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { onOpenSettings(SettingsTarget.YouTubeKey) }) { Text("Set up YouTube") }
                            OutlinedButton(onClick = {
                                onOpenSettings(
                                    if (spotify == ProviderState.NOT_CONFIGURED) SettingsTarget.SpotifyClientId else SettingsTarget.SpotifySignIn,
                                )
                            }) { Text("Set up Spotify") }
                        }
                    }
                    loading && tracks == null && query.isNotBlank() -> CircularProgressIndicator(Modifier.padding(24.dp))
                    error != null -> Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp),
                    )
                    query.isBlank() -> EmptyState(
                        state.searchHint,
                        "Your local files are not included here. Library has its own search for those.",
                    )
                    tracks.isNullOrEmpty() && !state.hasCollections -> if (loading) {
                        CircularProgressIndicator(Modifier.padding(24.dp))
                    } else {
                        EmptyState(
                            "No matches for \"${query.trim()}\"",
                            "Nothing matched on ${state.searchable.joinToString(" or ") { it.title }}. Try a different title or artist.",
                        )
                    }
                }
            }
            if (!showingResults) {
                if (width >= SearchCompactHeaderMinWidth) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally) { statusContent() }
                    }
                } else statusContent()
            } else when {
                split -> Row(Modifier.weight(1f).fillMaxWidth()) {
                    val tracksWidth = (width - ContentPaneSeamWidth) * SearchTracksShare
                    val otherWidth = width - ContentPaneSeamWidth - tracksWidth
                    CompositionLocalProvider(LocalTrackTable provides searchTable(tracksWidth, showAlbums)) {
                        LazyColumn(state = listState, modifier = Modifier.weight(SearchTracksShare).fillMaxHeight()) {
                            trackItems()
                        }
                    }
                    ContentPaneSeam()
                    Box(Modifier.weight(1f - SearchTracksShare).fillMaxHeight()) {
                        if (open) {
                            collectionPage(otherWidth)
                        } else {
                            LazyColumn(state = playlistsListState, modifier = Modifier.fillMaxSize()) {
                                collectionItems(true)
                            }
                        }
                    }
                }
                else -> CompositionLocalProvider(LocalTrackTable provides searchTable(minOf(width, SingleTrackTableMaxWidth), showAlbums)) {
                    LazyColumn(state = listState, modifier = Modifier.widthIn(max = SingleTrackTableMaxWidth)) {
                        trackItems()
                        collectionItems(false)
                    }
                }
            }
        }
    }
}

/** Search's rows end in the ⋮ menu alone, so the table's trailing slot is that wide and shows no time. */
internal fun searchTable(width: Dp, showAlbums: Boolean) = trackTableColumns(width, showAlbums, trailingWidth = 40.dp)

private fun LazyListScope.searchTrackRows(
    trackList: List<Track>,
    presenter: SearchPresenter,
    downloads: Map<String, DownloadItemState>,
    trackActions: TrackActionsPresenter,
    onPlayTrackInList: (List<Track>, Int) -> Unit,
    openLink: (String) -> Unit,
) {
    trackTableHeaderItem()
    itemsIndexed(trackList, key = { index, track -> "track:${track.canonicalId}:$index" }) { _, track ->
        val youtubeSource = track.sourceFor(ProviderId.YOUTUBE_MUSIC)
        fun openYouTube() {
            youtubeSource?.let {
                openLink("https://www.youtube.com/watch?v=${encodeUrl(it.providerTrackId)}")
            }
        }
        ActionTrackRow(
            track = track,
            actions = trackActions,
            availability = remember(track, downloads) {
                presenter.trackAvailability(track)
            },
            onClick = {
                if (youtubeSource != null && track.sources.none { it.isPlayable }) {
                    openYouTube()
                } else {
                    val index = trackList.indexOfFirst { it.canonicalId == track.canonicalId }
                        .coerceAtLeast(0)
                    onPlayTrackInList(trackList, index)
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp),
            extras = if (youtubeSource != null) {
                listOf(TrackMenuExtra("Open YouTube", Icons.AutoMirrored.Filled.OpenInNew, ::openYouTube))
            } else {
                emptyList()
            },
        )
    }
}

/** Column labels under the results heading when the rows are a table. */
internal fun LazyListScope.trackTableHeaderItem() {
    item(key = "results-columns") {
        LocalTrackTable.current?.let { TrackTableHeader(it, Modifier.padding(horizontal = 8.dp)) }
    }
}

private fun statusLine(status: ProviderSearchStatus): String {
    val suffix = when (status.state) {
        ProviderState.AVAILABLE -> "${status.resultCount}"
        ProviderState.UNAVAILABLE -> "unavailable"
        ProviderState.AUTH_REQUIRED -> "sign in"
        ProviderState.RATE_LIMITED -> "limited"
        ProviderState.NOT_CONFIGURED -> "not configured"
        ProviderState.LOADING -> "…"
    }
    return "${status.provider.displayName} $suffix"
}
