package com.universalmusic.player.ui.screens

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderSearchStatus
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.UnifiedSearchResult
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.ProviderStatusRow
import com.universalmusic.player.ui.components.TrackRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Survives leaving Search for Now Playing or another tab. Held above the screen
 * so results and scroll are still here when the screen returns to composition.
 */
class SearchUiState {
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
    var result by mutableStateOf<UnifiedSearchResult?>(null)
    var error by mutableStateOf<String?>(null)
    var listIndex by mutableStateOf(0)
    var listOffset by mutableStateOf(0)
}

@Composable
fun SearchScreen(
    container: AppContainer,
    onPlayTrackInList: (List<Track>, Int, query: String) -> Unit,
    state: SearchUiState,
    requestFocus: Boolean = false,
) {
    val query = state.query
    val loading = state.loading
    val result = state.result
    val error = state.error
    val listState = remember(state) {
        LazyListState(
            firstVisibleItemIndex = state.listIndex,
            firstVisibleItemScrollOffset = state.listOffset,
        )
    }
    val focusRequester = remember { FocusRequester() }
    val settings by container.settings.collectAsState()
    val spotify by container.spotify.state.collectAsState()
    val youtube by container.youtube.state.collectAsState()
    val providersConfigured = spotify != ProviderState.NOT_CONFIGURED ||
        youtube != ProviderState.NOT_CONFIGURED

    DisposableEffect(Unit) {
        onDispose { container.setTextInputFocused(false) }
    }

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                state.listIndex = index
                state.listOffset = offset
            }
    }

    LaunchedEffect(query, settings.spotifyClientId, settings.youtubeDataApiKey) {
        val value = query.trim()
        state.error = null
        if (value.isBlank()) {
            state.result = null
            state.loading = false
            return@LaunchedEffect
        }
        if (!providersConfigured) {
            state.result = null
            state.loading = false
            return@LaunchedEffect
        }

        state.loading = true
        delay(220)
        try {
            state.result = withContext(Dispatchers.IO) {
                container.unifiedSearch().search(value)
            }
            state.loading = false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            state.error = failure.message ?: "Search failed. Try again."
            state.loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { state.query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { container.setTextInputFocused(it.isFocused) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            placeholder = { Text("Search Spotify and YouTube") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { state.query = "" }) {
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
        val playlists = result?.playlists.orEmpty()
            .filter { it.source.provider in listOf(ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC) }
            .filter { it.canonicalId.isNotBlank() && it.source.providerEntityId.isNotBlank() }
            .distinctBy { it.canonicalId }
        val tracks = result?.tracks
            ?.filter { it.canonicalId.isNotBlank() }
            ?.filter { track ->
                track.sources.any {
                    it.provider == ProviderId.SPOTIFY || it.provider == ProviderId.YOUTUBE_MUSIC
                }
            }
            ?.distinctBy { it.canonicalId }
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
