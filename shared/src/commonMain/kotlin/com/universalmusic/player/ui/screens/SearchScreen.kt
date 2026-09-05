package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    container: AppContainer,
    onPlay: (Track) -> Unit,
    requestFocus: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UnifiedSearchResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val settings by container.settings.collectAsState()
    val spotify by container.spotify.state.collectAsState()
    val youtube by container.youtube.state.collectAsState()
    val soundcloud by container.soundcloud.state.collectAsState()
    val local by container.local.state.collectAsState()
    val localTracks by container.local.libraryTracks.collectAsState()
    val sample by container.sample.state.collectAsState()
    val sampleIncluded = container.providersForSearch(settings.sampleCatalogEnabled)
        .any { it.providerId == ProviderId.SAMPLE }
    val hasLocalTracks = localTracks.isNotEmpty()

    LaunchedEffect(requestFocus) {
        if (requestFocus) focusRequester.requestFocus()
    }

    LaunchedEffect(query) {
        val value = query.trim()
        error = null
        if (value.isBlank()) {
            result = null
            loading = false
            return@LaunchedEffect
        }

        loading = true
        delay(220)
        try {
            result = container.unifiedSearch().search(value)
            loading = false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            error = failure.message ?: "Search failed. Try again."
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(bottom = 88.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .focusRequester(focusRequester),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search songs, artists, albums") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
        )
        val statuses = result?.providerStatuses?.mapValues { it.value.state }
            ?: mapOf(
                ProviderId.LOCAL to local,
                ProviderId.SPOTIFY to spotify,
                ProviderId.YOUTUBE_MUSIC to youtube,
                ProviderId.SOUNDCLOUD to soundcloud,
            ).let { current ->
                if (sampleIncluded) current + (ProviderId.SAMPLE to sample) else current
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
        val tracks = result?.tracks
        when {
            loading && tracks == null -> CircularProgressIndicator(Modifier.padding(24.dp))
            error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            query.isBlank() -> EmptyState(
                "Search once",
                emptyQueryBody(sampleIncluded, hasLocalTracks),
            )
            tracks.isNullOrEmpty() -> if (loading) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else {
                EmptyState(
                    "No matches for \"${query.trim()}\"",
                    noResultsBody(sampleIncluded, hasLocalTracks),
                )
            }
            else -> LazyColumn {
                items(tracks, key = { it.canonicalId }) { track ->
                    TrackRow(track, onClick = { onPlay(track) }, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

private fun emptyQueryBody(sampleIncluded: Boolean, hasLocalTracks: Boolean): String = when {
    sampleIncluded && hasLocalTracks ->
        "Search your local library, connected providers, and the sample catalog."
    sampleIncluded ->
        "Search connected providers and the sample catalog. Add music to your local library from Settings."
    hasLocalTracks ->
        "Search your local library and connected providers. Enable the sample catalog in Settings for demo tracks."
    else ->
        "Search connected providers. Add music to your local library or enable the sample catalog in Settings."
}

private fun noResultsBody(sampleIncluded: Boolean, hasLocalTracks: Boolean): String = when {
    sampleIncluded && hasLocalTracks ->
        "Nothing matched in your local library, connected providers, or the sample catalog. Try a different title, artist, or album."
    sampleIncluded ->
        "Nothing matched in the sample catalog or connected providers. Try a different title, artist, or album."
    hasLocalTracks ->
        "Nothing matched in your local library or connected providers. Try a different title, artist, or album."
    else ->
        "Try a different title, artist, or album, or connect a provider in Settings."
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
