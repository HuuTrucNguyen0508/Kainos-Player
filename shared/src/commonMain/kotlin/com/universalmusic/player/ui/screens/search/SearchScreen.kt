package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.reportsTextInputFocus
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.universalmusic.player.platform.openUrl
import com.universalmusic.player.platform.encodeUrl
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
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
import com.universalmusic.player.ui.components.TrackRow

@Composable
internal fun SearchScreen(
    presenter: SearchPresenter,
    onPlayTrackInList: (List<Track>, Int, query: String) -> Unit,
    requestFocus: Boolean = false,
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
    val providersConfigured = state.providersConfigured

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize()) {
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
            placeholder = { Text("Search Spotify and YouTube") },
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
            !providersConfigured -> EmptyState(
                "Connect a provider",
                "Search uses Spotify and YouTube only. Add a Spotify Client ID and/or YouTube Data API key in Settings. Local files stay in Library.",
            )
            loading && tracks == null && query.isNotBlank() -> CircularProgressIndicator(Modifier.padding(24.dp))
            error != null -> Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(20.dp),
            )
            query.isBlank() -> EmptyState(
                "Search Spotify and YouTube",
                "Local files are not included here — use Library search for your folders.",
            )
            tracks.isNullOrEmpty() && playlists.isEmpty() -> if (loading) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else {
                EmptyState(
                    "No matches for \"${query.trim()}\"",
                    "Nothing matched on Spotify or YouTube. Try a different title or artist, or check provider status above.",
                )
            }
            else -> LazyColumn(state = listState) {
                val trackList = tracks.orEmpty()
                itemsIndexed(trackList, key = { index, track -> "track:${track.canonicalId}:$index" }) { _, track ->
                    val youtubeSource = track.sourceFor(ProviderId.YOUTUBE_MUSIC)
                    fun openYouTube() {
                        youtubeSource?.let {
                            openUrl("https://www.youtube.com/watch?v=${encodeUrl(it.providerTrackId)}")
                        }
                    }
                    TrackRow(
                        track,
                        availability = remember(track, downloads) {
                            presenter.trackAvailability(track)
                        },
                        onClick = {
                            if (youtubeSource != null && track.sources.none { it.isPlayable }) {
                                openYouTube()
                            } else {
                                val index = trackList.indexOfFirst { it.canonicalId == track.canonicalId }
                                    .coerceAtLeast(0)
                                onPlayTrackInList(trackList, index, query.trim())
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                        trailing = {
                            if (youtubeSource != null) {
                                var menuOpen by remember(track.canonicalId) { mutableStateOf(false) }
                                Row {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More actions for ${track.title}")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Open YouTube") },
                                            onClick = {
                                                menuOpen = false
                                                openYouTube()
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
                itemsIndexed(playlists, key = { index, playlist -> "playlist:${playlist.canonicalId}:$index" }) { _, playlist ->
                    TextButton(
                        onClick = {
                            val id = encodeUrl(playlist.source.providerEntityId)
                            when (playlist.source.provider) {
                                ProviderId.YOUTUBE_MUSIC ->
                                    openUrl("https://www.youtube.com/playlist?list=$id")
                                ProviderId.SPOTIFY ->
                                    openUrl("https://open.spotify.com/playlist/$id")
                                else -> Unit
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                        Text("${playlist.title} · Open on ${playlist.source.provider.displayName}")
                    }
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
