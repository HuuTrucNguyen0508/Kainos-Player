package com.universalmusic.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.playlist.PlaylistEntryAvailability
import com.universalmusic.player.data.playlist.playlistEntryAvailability
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.encodeUrl
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.components.IconTile
import com.universalmusic.player.ui.components.LocalTrackTable
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.components.Slip
import com.universalmusic.player.ui.components.SourceKind
import com.universalmusic.player.ui.components.SourceMark
import com.universalmusic.player.ui.components.albumLightStrip
import com.universalmusic.player.ui.components.namesAlbums
import com.universalmusic.player.ui.components.rememberAlbumLight
import com.universalmusic.player.ui.components.trackTableColumns
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import kotlin.math.roundToInt

/** Rows left below the last visible one when the next page starts loading. */
private const val PREFETCH_ROWS = 8

/**
 * The top page of [stack], each page under its own saved state so going back lands where it was.
 * [rootBackLabel] names what Back returns to from the first page ("Back to Library").
 */
@Composable
internal fun CollectionHost(
    stack: CollectionStack,
    rootBackLabel: String,
    trackActions: TrackActionsPresenter,
    availability: (Track) -> TrackAvailabilityInfo,
    downloads: Map<String, DownloadItemState>,
    onPlay: (List<Track>, Int, Boolean) -> Unit,
    openLink: (String) -> Unit,
    listWidth: Dp = 0.dp,
) {
    val pages by stack.stack.collectAsState()
    val page = pages.lastOrNull() ?: return
    val previous = pages.getOrNull(pages.lastIndex - 1)
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider(page.id) {
        CompositionLocalProvider(LocalCollectionKey provides page.ref.key) {
        CollectionScreen(
            presenter = page.presenter,
            trackActions = trackActions,
            availability = availability,
            downloads = downloads,
            onBack = { stack.back() },
            backLabel = previous?.let { "Back to ${it.ref.title}" } ?: rootBackLabel,
            onPlay = onPlay,
            onOpen = stack::push,
            openLink = openLink,
            listWidth = listWidth,
        )
        }
    }
}

/**
 * A tab with the pages it opened over it. The tab and the pages keep separate saved state in
 * [holder], so closing the last page shows the tab exactly as it was left, scroll included.
 * [enabled] false always shows the tab (Search places its pages itself).
 */
@Composable
internal fun PagesOverTab(
    stack: CollectionStack,
    holder: SaveableStateHolder,
    tabKey: String,
    rootBackLabel: String,
    trackActions: TrackActionsPresenter,
    availability: (Track) -> TrackAvailabilityInfo,
    downloads: Map<String, DownloadItemState>,
    onPlay: (List<Track>, Int, Boolean) -> Unit,
    openLink: (String) -> Unit,
    enabled: Boolean = true,
    tab: @Composable () -> Unit,
) {
    val pages by stack.stack.collectAsState()
    if (enabled && pages.isNotEmpty()) {
        holder.SaveableStateProvider("$tabKey:pages") {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CollectionHost(stack, rootBackLabel, trackActions, availability, downloads, onPlay, openLink, maxWidth)
            }
        }
    } else {
        holder.SaveableStateProvider(tabKey) { tab() }
    }
}

/**
 * An album, artist or playlist played in Kainos: Play and Shuffle queue it all, each row has the
 * shared track menu, and a Kainos playlist's rows can be dragged or removed with undo.
 */
@Composable
internal fun CollectionScreen(
    presenter: CollectionPresenter,
    trackActions: TrackActionsPresenter,
    availability: (Track) -> TrackAvailabilityInfo,
    downloads: Map<String, DownloadItemState>,
    onBack: () -> Unit,
    backLabel: String,
    onPlay: (List<Track>, Int, Boolean) -> Unit,
    onOpen: (CollectionRef) -> Unit,
    openLink: (String) -> Unit,
    /** The width this page has; wide pages lay their rows out as a table. */
    listWidth: Dp = 0.dp,
) {
    val pageState = presenter.state.collectAsState()
    val state by pageState
    val header = state.header
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(presenter, listState) {
        // Counted against the loaded songs, not the laid-out rows: right after a page lands the
        // layout still shows the shorter list and would ask for another page at once. Emitting the
        // cursor lets a page that added few or no songs ask for the next one while still near the end.
        snapshotFlow {
            val current = pageState.value
            val lastRow = current.tracks.size + 1
            val nearEnd = current.error == null &&
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= lastRow - PREFETCH_ROWS
            current.cursor.takeIf { nearEnd }
        }.collect { cursor ->
            // A failed page waits for Try again; the emission may predate that failure.
            if (cursor != null && presenter.state.value.error == null) presenter.loadMore()
        }
    }

    val showAlbums = remember(state.tracks) { state.tracks.namesAlbums() }
    val discs = remember(state.tracks, header.kind) {
        if (header.kind == CollectionKind.Album) state.tracks.mapNotNull { it.discNumber }.distinct().size else 0
    }
    val tableWidth = minOf(listWidth, SingleTrackTableMaxWidth)
    val table = trackTableColumns(tableWidth, showAlbums, trailingWidth = if (state.editable) 120.dp else 40.dp)
    Column(Modifier.widthIn(max = SingleTrackTableMaxWidth).fillMaxSize()) {
        CollectionTopBar(state, backLabel, onBack, openLink, presenter)
        state.removed?.let { removed ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Removed ${removed.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = presenter::undoRemove) { Text("Undo") }
            }
        }
        CompositionLocalProvider(LocalTrackTable provides table) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                item(key = "header") {
                    CollectionHeaderSlip(
                        state = state,
                        onPlay = { presenter.play(0, shuffle = false, onPlay) },
                        onShuffle = { presenter.play(0, shuffle = true, onPlay) },
                        onOpen = onOpen,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                state.notice?.let { notice ->
                    item(key = "notice") {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
                if (state.albums.isNotEmpty()) {
                    item(key = "albums") {
                        AlbumShelf(state.albums, onOpen)
                    }
                }
                if (header.kind == CollectionKind.Artist && state.tracks.isNotEmpty()) {
                    item(key = "songs-heading") {
                        SectionRule(
                            if (header.source == SourceKind.Spotify) "Popular songs" else "Songs",
                            Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
                itemsIndexed(
                    state.tracks,
                    key = { index, track -> state.entryIds?.getOrNull(index) ?: "track:$index:${track.canonicalId}" },
                ) { index, track ->
                    val disc = track.discNumber
                    if (discs > 1 && disc != null && disc != state.tracks.getOrNull(index - 1)?.discNumber) {
                        SectionRule("Disc $disc", Modifier.padding(horizontal = 20.dp).padding(top = if (index == 0) 0.dp else 8.dp))
                    }
                    CollectionTrackRow(
                        track = track,
                        index = index,
                        state = state,
                        presenter = presenter,
                        trackActions = trackActions,
                        availability = remember(track, downloads) { availability(track) },
                        onPlay = onPlay,
                        openLink = openLink,
                        modifier = if (state.editable) Modifier.animateItem() else Modifier,
                    )
                }
                item(key = "footer") {
                    CollectionFooter(state, onRetry = presenter::loadMore)
                }
            }
        }
    }
}

@Composable
private fun CollectionTrackRow(
    track: Track,
    index: Int,
    state: CollectionState,
    presenter: CollectionPresenter,
    trackActions: TrackActionsPresenter,
    availability: TrackAvailabilityInfo,
    onPlay: (List<Track>, Int, Boolean) -> Unit,
    openLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val youtubeSource = track.sourceFor(ProviderId.YOUTUBE_MUSIC)
    fun openYouTube() {
        youtubeSource?.let { openLink("https://www.youtube.com/watch?v=${encodeUrl(it.providerTrackId)}") }
    }
    // The row keeps its pointer handler while it moves, so read the position it has now.
    val currentIndex by rememberUpdatedState(index)
    val density = LocalDensity.current
    var rowHeightPx by remember { mutableFloatStateOf(with(density) { 64.dp.toPx() }) }
    val extras = buildList {
        if (youtubeSource != null) add(TrackMenuExtra("Open YouTube", Icons.AutoMirrored.Filled.OpenInNew, ::openYouTube))
        if (state.editable) {
            if (index > 0) add(TrackMenuExtra("Move up", Icons.Default.KeyboardArrowUp) { presenter.move(currentIndex, -1) })
            if (index < state.tracks.lastIndex) add(TrackMenuExtra("Move down", Icons.Default.KeyboardArrowDown) { presenter.move(currentIndex, 1) })
            add(TrackMenuExtra("Remove from playlist", Icons.Default.Clear) { presenter.remove(currentIndex) })
        }
    }
    val editControls: (@Composable () -> Unit)? = if (!state.editable) null else {
        { PlaylistEditControls(track, { currentIndex }, { rowHeightPx }, presenter) }
    }
    ActionTrackRow(
        track = track,
        actions = trackActions,
        availability = availability,
        onClick = {
            if (youtubeSource != null && track.sources.none { it.isPlayable }) {
                openYouTube()
            } else {
                presenter.play(currentIndex, shuffle = false, onPlay)
            }
        },
        modifier = modifier
            .padding(horizontal = 8.dp)
            .onSizeChanged { if (it.height > 0) rowHeightPx = it.height.toFloat() },
        extras = extras,
        trailing = editControls,
    )
}

/** A drag handle that moves the row a whole row at a time, and Remove. */
@Composable
private fun PlaylistEditControls(track: Track, index: () -> Int, rowHeightPx: () -> Float, presenter: CollectionPresenter) {
    var dragDy by remember { mutableFloatStateOf(0f) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A synced entry whose file is not on this device stays listed, and says so.
            if (playlistEntryAvailability(track) == PlaylistEntryAvailability.MISSING_LOCAL) {
                Text(
                    "Missing file",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .pointerInput(track.canonicalId) {
                        detectDragGestures(
                            onDragEnd = { dragDy = 0f },
                            onDragCancel = { dragDy = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragDy += amount.y
                            // The row swaps once it is dragged past half of its neighbour.
                            val height = rowHeightPx()
                            val steps = (dragDy / height).roundToInt()
                            if (steps != 0 && presenter.move(index(), steps)) dragDy -= steps * height
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder ${track.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = { presenter.remove(index()) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Clear, contentDescription = "Remove ${track.title} from playlist", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CollectionTopBar(
    state: CollectionState,
    backLabel: String,
    onBack: () -> Unit,
    openLink: (String) -> Unit,
    presenter: CollectionPresenter,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val header = state.header
    val webUrl = header.webUrl
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
        }
        Spacer(Modifier.weight(1f))
        if (webUrl != null || state.editable) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More actions for ${header.title}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (webUrl != null) {
                        DropdownMenuItem(
                            text = { Text("Open on ${header.source.serviceName()}") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                            onClick = {
                                menuOpen = false
                                openLink(webUrl)
                            },
                        )
                    }
                    if (state.editable) {
                        DropdownMenuItem(
                            text = { Text("Add the current queue") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            onClick = {
                                menuOpen = false
                                presenter.addCurrentQueue()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun SourceKind.serviceName(): String = when (this) {
    SourceKind.Spotify -> "Spotify"
    SourceKind.YouTube -> "YouTube"
    else -> "the web"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionHeaderSlip(
    state: CollectionState,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpen: (CollectionRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val header = state.header
    val palette = LocalKainosPalette.current
    val light by rememberAlbumLight(header.artwork?.url)
    val canPlay = state.tracks.isNotEmpty() && !state.preparingPlay
    val artShape = if (header.kind == CollectionKind.Artist) CircleShape else RoundedCornerShape(LocalSkinTokens.current.rounding.normal)
    Slip(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().albumLightStrip(light, palette.isDark).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(header.artwork, header.title, Modifier.size(112.dp), header.title, shape = artShape)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SourceMark(header.source, header.sourceLabel, showIcon = true)
                    Text(
                        header.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    header.byline?.let { byline ->
                        val link = header.bylineRef
                        Text(
                            byline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (link != null) Modifier.clickable(onClickLabel = "Go to $byline") { onOpen(link) } else Modifier,
                        )
                    }
                    collectionFacts(state)?.let { facts ->
                        Text(
                            facts,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, enabled = canPlay) {
                    if (state.preparingPlay) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading every song", maxLines = 1)
                    } else {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play", maxLines = 1)
                    }
                }
                FilledTonalButton(onClick = onShuffle, enabled = canPlay) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Shuffle", maxLines = 1)
                }
            }
        }
    }
}

/** "2019, 12 songs, 48 min" for an album; songs and albums for an artist; songs for a playlist. */
internal fun collectionFacts(state: CollectionState): String? {
    val header = state.header
    // A provider playlist counts every entry it reports; the footer says how many cannot show.
    val songs = when {
        header.kind == CollectionKind.Playlist && !state.editable ->
            state.total ?: header.trackCount ?: state.tracks.size.takeIf { state.complete }
        state.complete -> state.tracks.size
        else -> state.total ?: header.trackCount
    }
    val parts = buildList {
        if (header.kind == CollectionKind.Album) header.year?.let { add(it.toString()) }
        songs?.let { add(songCount(it)) }
        if (header.kind == CollectionKind.Artist && state.albums.isNotEmpty()) {
            add(if (state.albums.size == 1) "1 album" else "${state.albums.size} albums")
        }
        if (header.kind != CollectionKind.Artist) state.totalDurationMs?.let { add(lengthLabel(it)) }
    }
    return parts.joinToString(", ").ifBlank { null }
}

/** "48 min", or "1 hr 12 min" past the hour. */
internal fun lengthLabel(durationMs: Long): String {
    val minutes = ((durationMs + 30_000) / 60_000).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} hr ${minutes % 60} min"
}

/** An artist's albums as tiles; each opens its album page. */
@Composable
private fun AlbumShelf(albums: List<Album>, onOpen: (CollectionRef) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SectionRule("Albums", Modifier.padding(horizontal = 20.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { it.canonicalId }) { album ->
                val source = if (album.canonicalId.startsWith("spotify-album:")) SourceKind.Spotify else SourceKind.Local
                IconTile(
                    title = album.title,
                    subtitle = album.year?.toString() ?: album.tracks.size.takeIf { it > 0 }?.let(::songCount),
                    accent = source.slot,
                    onClick = { onOpen(album.page()) },
                ) { art ->
                    ArtworkImage(
                        album.artwork, album.title, art, album.title,
                        shape = RoundedCornerShape(LocalSkinTokens.current.rounding.normal),
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionFooter(state: CollectionState, onRetry: () -> Unit) {
    val header = state.header
    val provider = header.source.serviceName()
    val noun = when (header.kind) {
        CollectionKind.Album -> "album"
        CollectionKind.Artist -> "artist page"
        CollectionKind.Playlist -> "playlist"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val error = state.error
        when {
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    if (state.tracks.isEmpty()) "Loading songs from $provider" else "Loading more songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
            error != null -> {
                Text(
                    if (state.tracks.isEmpty()) "This $noun did not load. $error"
                    else "Showing the first ${songCount(state.tracks.size)}. The next page did not load. $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            // A notice already says why the page is empty.
            state.complete && state.tracks.isEmpty() && state.notice == null && state.albums.isEmpty() -> Text(
                when {
                    state.editable -> "This playlist is empty. Add songs from any song's menu, or Add the current queue."
                    header.source == SourceKind.Spotify || header.source == SourceKind.YouTube ->
                        "No songs to play. This $noun is empty, or every song in it is unavailable on $provider."
                    else -> "No songs to play."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            state.complete && header.kind == CollectionKind.Playlist && !state.editable -> {
                val hidden = (state.total ?: 0) - state.tracks.size
                if (hidden > 0) {
                    Text(
                        "${songCount(hidden)} in this playlist ${if (hidden == 1) "is" else "are"} unavailable on $provider and not shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
        }
    }
}

internal fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"
