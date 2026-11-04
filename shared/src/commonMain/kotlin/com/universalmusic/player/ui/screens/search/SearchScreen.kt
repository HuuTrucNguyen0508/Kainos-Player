package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.reportsTextInputFocus
import androidx.compose.foundation.layout.Arrangement
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
    val openPlaylist by presenter.playlist.collectAsState()

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    // The results keep their scroll while a playlist is open: their list state is held above this branch.
    openPlaylist?.let { page ->
        SearchPlaylistScreen(
            presenter = page,
            trackActions = trackActions,
            availability = presenter::trackAvailability,
            downloads = downloads,
            onBack = { presenter.closePlaylist() },
            onPlay = onPlayPlaylist,
            openLink = openLink,
        )
    } ?: Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = presenter::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
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
        val statuses = result?.providerStatuses?.mapValues { it.value.state }
            ?: mapOf(
                ProviderId.SPOTIFY to spotify,
                ProviderId.YOUTUBE_MUSIC to youtube,
            )
        if (loading && result != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        ProviderStatusRow(statuses, Modifier.padding(horizontal = 20.dp))
        result?.let { current ->
            val counts = current.providerStatuses.values.joinToString("   ") { statusLine(it) }
            Text(
                counts,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        result?.providerStatuses?.values?.filter { it.message != null }?.forEach { status ->
            Text(
                "${status.provider.displayName}: ${status.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        val playlists = state.playlists
        val tracks = state.tracks
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
            tracks.isNullOrEmpty() && playlists.isEmpty() -> if (loading) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else {
                EmptyState(
                    "No matches for \"${query.trim()}\"",
                    "Nothing matched on ${state.searchable.joinToString(" or ") { it.title }}. Try a different title or artist.",
                )
            }
            else -> LazyColumn(state = listState) {
                val trackList = tracks.orEmpty()
                item(key = "results-heading") {
                    SectionRule(state.searchable.joinToString(" and ") { it.title }, Modifier.padding(horizontal = 20.dp))
                }
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
                if (playlists.isNotEmpty()) {
                    item(key = "playlists-heading") {
                        SectionRule("Playlists", Modifier.padding(horizontal = 20.dp).padding(top = 12.dp))
                    }
                }
                itemsIndexed(playlists, key = { index, playlist -> "playlist:${playlist.canonicalId}:$index" }) { _, playlist ->
                    PlaylistResultRow(
                        playlist = playlist,
                        onClick = { presenter.openPlaylist(playlist) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
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
