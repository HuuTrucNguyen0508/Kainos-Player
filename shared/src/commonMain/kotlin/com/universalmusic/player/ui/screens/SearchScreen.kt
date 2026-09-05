package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val spotify by container.spotify.state.collectAsState()
    val youtube by container.youtube.state.collectAsState()
    val soundcloud by container.soundcloud.state.collectAsState()

    fun runSearch(value: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(220)
            if (value.isBlank()) {
                result = null
                loading = false
                return@launch
            }
            loading = true
            error = null
            runCatching { container.unifiedSearch().search(value) }
                .onSuccess { result = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(query) { runSearch(query) }

    Column(Modifier.fillMaxSize().padding(bottom = 88.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search songs, artists, albums") },
        )
        val statuses = result?.providerStatuses?.mapValues { it.value.state }
            ?: mapOf(
                ProviderId.SPOTIFY to spotify,
                ProviderId.YOUTUBE_MUSIC to youtube,
                ProviderId.SOUNDCLOUD to soundcloud,
            )
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
        when {
            loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            query.isBlank() -> EmptyState(
                "Search once",
                "Results from every connected provider are grouped into one list. The best playable source starts automatically.",
            )
            result?.tracks.isNullOrEmpty() -> EmptyState(
                "No matches",
                "The sample catalog uses demo titles such as Northbound Signal and Paper Lanterns — not Beethoven. Connect a provider in Settings for live catalogs.",
            )
            else -> LazyColumn {
                items(result!!.tracks, key = { it.canonicalId }) { track ->
                    TrackRow(track, onClick = { onPlay(track) }, modifier = Modifier.padding(horizontal = 8.dp))
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
