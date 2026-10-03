package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.UnifiedSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val result: UnifiedSearchResult? = null,
    val error: String? = null,
    val spotify: ProviderState = ProviderState.NOT_CONFIGURED,
    val youtube: ProviderState = ProviderState.NOT_CONFIGURED,
    val downloads: Map<String, DownloadItemState> = emptyMap(),
) {
    val providersConfigured: Boolean
        get() = spotify != ProviderState.NOT_CONFIGURED || youtube != ProviderState.NOT_CONFIGURED

    val tracks: List<Track>?
        get() = result?.tracks?.filter { track ->
            track.canonicalId.isNotBlank() && track.sources.any {
                it.provider == ProviderId.SPOTIFY || it.provider == ProviderId.YOUTUBE_MUSIC
            }
        }?.distinctBy { it.canonicalId }

    val playlists: List<Playlist>
        get() = result?.playlists.orEmpty().filter {
            it.source.provider in listOf(ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC) &&
                it.canonicalId.isNotBlank() && it.source.providerEntityId.isNotBlank()
        }.distinctBy { it.canonicalId }
}

/** Lives with AppScaffold so results survive tab changes; scrolling stays in Compose. */
internal class SearchPresenter(
    settings: StateFlow<AppSettings>,
    spotify: StateFlow<ProviderState>,
    youtube: StateFlow<ProviderState>,
    downloads: StateFlow<Map<String, DownloadItemState>>,
    private val search: suspend (String) -> UnifiedSearchResult,
    private val availability: (Track) -> TrackAvailabilityInfo,
    scope: CoroutineScope,
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val query = MutableStateFlow("")
    private val mutableState = MutableStateFlow(
        SearchUiState(spotify = spotify.value, youtube = youtube.value, downloads = downloads.value),
    )
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(spotify, youtube, downloads) { sp, yt, jobs -> Triple(sp, yt, jobs) }
                .collect { (sp, yt, jobs) ->
                    mutableState.update { it.copy(spotify = sp, youtube = yt, downloads = jobs) }
                }
        }
        scope.launch {
            val credentials = settings.map { it.spotifyClientId to it.youtubeDataApiKey }
                .distinctUntilChanged()
            val configured = combine(spotify, youtube) { sp, yt ->
                sp != ProviderState.NOT_CONFIGURED || yt != ProviderState.NOT_CONFIGURED
            }.distinctUntilChanged()
            combine(query, credentials, configured) { text, keys, enabled ->
                SearchRequest(text, keys, enabled)
            }.collectLatest { request ->
                val value = request.query.trim()
                if (value.isBlank() || !request.configured) {
                    mutableState.update { it.copy(loading = false, result = null, error = null) }
                    return@collectLatest
                }
                mutableState.update { it.copy(loading = true, error = null) }
                delay(220)
                try {
                    val result = withContext(searchDispatcher) { search(value) }
                    mutableState.update { it.copy(result = result, loading = false) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    mutableState.update {
                        it.copy(error = failure.message ?: "Search failed. Try again.", loading = false)
                    }
                }
            }
        }
    }

    fun setQuery(value: String) {
        mutableState.update {
            if (value.isBlank()) it.copy(query = value, loading = false, result = null, error = null)
            else it.copy(query = value, error = null)
        }
        query.value = value
    }

    fun trackAvailability(track: Track): TrackAvailabilityInfo = availability(track)

    private data class SearchRequest(
        val query: String,
        val credentials: Pair<String?, String?>,
        val configured: Boolean,
    )

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope): SearchPresenter = SearchPresenter(
            settings = container.settings,
            spotify = container.spotify.state,
            youtube = container.youtube.state,
            downloads = container.heartedAudio.downloads,
            search = { container.unifiedSearch().search(it) },
            availability = container::trackAvailability,
            scope = scope,
        )
    }
}
