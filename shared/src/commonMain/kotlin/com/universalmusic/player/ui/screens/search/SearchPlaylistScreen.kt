package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.encodeUrl
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.components.LedgerRow
import com.universalmusic.player.ui.components.Slip
import com.universalmusic.player.ui.components.SourceKind
import com.universalmusic.player.ui.components.SourceMark
import com.universalmusic.player.ui.components.albumLightStrip
import com.universalmusic.player.ui.components.rememberAlbumLight
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens

/** Rows left below the last visible one when the next page starts loading. */
private const val PREFETCH_ROWS = 8

/**
 * A Spotify or YouTube playlist from Search, played in Kainos: its songs page in as the list
 * scrolls, Play and Shuffle queue the whole playlist, and each row has the shared track menu.
 */
@Composable
internal fun SearchPlaylistScreen(
    presenter: SearchPlaylistPresenter,
    trackActions: TrackActionsPresenter,
    availability: (Track) -> TrackAvailabilityInfo,
    downloads: Map<String, DownloadItemState>,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int, Boolean) -> Unit,
    openLink: (String) -> Unit,
) {
    val pageState = presenter.state.collectAsState()
    val state by pageState
    val playlist = state.playlist
    val listState = rememberSaveable(playlist.canonicalId, saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(presenter, listState) {
        // Counted against the loaded songs, not the laid-out rows: right after a page lands the
        // layout still shows the shorter list and would ask for another page at once.
        snapshotFlow {
            val current = pageState.value
            val lastRow = current.tracks.size + 1
            current.error == null &&
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= lastRow - PREFETCH_ROWS
        }.collect { nearEnd ->
            // A failed page waits for Try again; the emission may predate that failure.
            if (nearEnd && presenter.state.value.error == null) presenter.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        PlaylistTopBar(playlist, onBack, openLink)
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            item(key = "header") {
                PlaylistHeader(
                    state = state,
                    onPlay = { presenter.play(0, shuffle = false, onPlay) },
                    onShuffle = { presenter.play(0, shuffle = true, onPlay) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(state.tracks, key = { index, track -> "track:$index:${track.canonicalId}" }) { index, track ->
                val youtubeSource = track.sourceFor(ProviderId.YOUTUBE_MUSIC)
                fun openYouTube() {
                    youtubeSource?.let { openLink("https://www.youtube.com/watch?v=${encodeUrl(it.providerTrackId)}") }
                }
                ActionTrackRow(
                    track = track,
                    actions = trackActions,
                    availability = remember(track, downloads) { availability(track) },
                    onClick = {
                        if (youtubeSource != null && track.sources.none { it.isPlayable }) {
                            openYouTube()
                        } else {
                            presenter.play(index, shuffle = false, onPlay)
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
            item(key = "footer") {
                PlaylistFooter(state, onRetry = presenter::loadMore)
            }
        }
    }
}

@Composable
private fun PlaylistTopBar(playlist: Playlist, onBack: () -> Unit, openLink: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val provider = playlist.source.provider
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to search results")
        }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More actions for ${playlist.title}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Open on ${provider.displayName}") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                    onClick = {
                        menuOpen = false
                        playlist.webUrl()?.let(openLink)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistHeader(
    state: PlaylistPageState,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlist = state.playlist
    val palette = LocalKainosPalette.current
    val light by rememberAlbumLight(playlist.artwork?.url)
    val canPlay = state.tracks.isNotEmpty() && !state.preparingPlay
    Slip(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().albumLightStrip(light, palette.isDark).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(
                    playlist.artwork, playlist.title, Modifier.size(112.dp), playlist.title,
                    shape = RoundedCornerShape(LocalSkinTokens.current.rounding.normal),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SourceMark(
                        SourceKind.of(playlist.source.provider),
                        "${playlist.source.provider.displayName} playlist",
                        showIcon = true,
                    )
                    Text(
                        playlist.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    playlist.ownerName?.takeIf { it.isNotBlank() }?.let { owner ->
                        Text(
                            "By $owner",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    state.total?.let { total ->
                        Text(
                            songCount(total),
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

@Composable
private fun PlaylistFooter(state: PlaylistPageState, onRetry: () -> Unit) {
    val provider = state.playlist.source.provider.displayName
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
                    if (state.tracks.isEmpty()) "This playlist did not load. $error"
                    else "Showing the first ${songCount(state.tracks.size)}. The next page did not load. $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            state.complete && state.tracks.isEmpty() -> Text(
                "No songs to play. This playlist is empty, or every song in it is unavailable on $provider.",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            state.complete -> {
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

/** A playlist found by Search: tapping it opens [SearchPlaylistScreen]. */
@Composable
internal fun PlaylistResultRow(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LedgerRow(modifier = modifier, onClick = onClick) {
        ArtworkImage(
            playlist.artwork, playlist.title, Modifier.size(48.dp), playlist.title,
            shape = RoundedCornerShape(LocalSkinTokens.current.rounding.small),
        )
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append("${playlist.source.provider.displayName} playlist")
                    playlist.ownerName?.takeIf { it.isNotBlank() }?.let { append(" by $it") }
                    playlist.trackCount?.let { append(", ${songCount(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

private fun Playlist.webUrl(): String? {
    val id = encodeUrl(source.providerEntityId)
    return when (source.provider) {
        ProviderId.YOUTUBE_MUSIC -> "https://www.youtube.com/playlist?list=$id"
        ProviderId.SPOTIFY -> "https://open.spotify.com/playlist/$id"
        else -> null
    }
}
