package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class LibraryPresenter(
    private val settings: StateFlow<AppSettings>,
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val savedTracks: StateFlow<List<Track>>,
    private val favorites: StateFlow<Set<String>>,
    private val localTracks: StateFlow<List<Track>>,
    private val localState: StateFlow<ProviderState>,
    private val spotifyTracks: StateFlow<List<Track>>,
    private val spotifyPlaylists: StateFlow<List<Playlist>>,
    private val kainosPlaylistRows: StateFlow<List<PersistedKainosPlaylist>>,
    private val homePins: StateFlow<List<PersistedHomePin>>,
    private val spotifyLibraryLoading: StateFlow<Boolean>,
    private val spotifyLibraryError: StateFlow<String?>,
    private val spotifyState: StateFlow<ProviderState>,
    private val downloads: StateFlow<Map<String, DownloadItemState>>,
    private val scope: CoroutineScope,
    private val refreshLocalLibrary: () -> Unit = {},
    private val refreshSpotifyLibrary: suspend () -> Unit = {},
    private val onPinHome: (
        HomePinKind,
        String,
        String,
        String?,
        String?,
        String?,
        String?,
    ) -> Any? = { _, _, _, _, _, _, _ -> null },
    private val onUnpinHome: (HomePinKind, String) -> Any? = { _, _ -> null },
    private val onCreatePlaylist: (String) -> Any? = { null },
    private val onRenamePlaylist: (String, String) -> Any? = { _, _ -> null },
    private val onDeletePlaylist: (String) -> Any? = { null },
    private val onReorderPlaylists: (List<String>) -> Any? = { null },
    private val onAddTracks: (String, List<Track>) -> Any? = { _, _ -> null },
    private val onRemoveEntry: (String, String) -> Any? = { _, _ -> null },
    private val onLoadSpotifyPlaylistTracks: suspend (String) -> List<Track> = { emptyList() },
    private val onCurrentQueueTracks: () -> List<Track> = { emptyList() },
    private val onTrackAvailability: (Track) -> TrackAvailabilityInfo = { track ->
        error("trackAvailability was not provided for ${track.canonicalId}")
    },
) {
    private val queryText = MutableStateFlow("")

    private data class CatalogSlice(
        val settings: AppSettings,
        val saved: List<Track>,
        val favorites: Set<String>,
        val localTracks: List<Track>,
        val localState: ProviderState,
    )

    private data class SpotifySlice(
        val tracks: List<Track>,
        val playlists: List<Playlist>,
        val loading: Boolean,
        val error: String?,
        val providerState: ProviderState,
    )

    private data class PinsSlice(
        val kainosRows: List<PersistedKainosPlaylist>,
        val homePins: List<PersistedHomePin>,
        val downloads: Map<String, DownloadItemState>,
    )

    val state: StateFlow<LibraryUiState> = combine(
        combine(settings, savedTracks, favorites, localTracks, localState, ::CatalogSlice),
        combine(
            spotifyTracks,
            spotifyPlaylists,
            spotifyLibraryLoading,
            spotifyLibraryError,
            spotifyState,
            ::SpotifySlice,
        ),
        combine(kainosPlaylistRows, homePins, downloads, ::PinsSlice),
        queryText,
    ) { catalog, spotify, pins, query ->
        derive(catalog, spotify, pins, query)
    }.stateIn(scope, SharingStarted.Eagerly, initialState())

    fun setQuery(value: String) {
        queryText.value = value
    }

    fun toggleLocalOnly() {
        scope.launch {
            updateSettings { it.copy(libraryLocalOnly = !it.libraryLocalOnly) }
        }
    }

    fun toggleFavoritesOnly() {
        scope.launch {
            updateSettings { it.copy(libraryFavoritesOnly = !it.libraryFavoritesOnly) }
        }
    }

    fun setSort(sort: TrackSort) {
        scope.launch {
            updateSettings { it.copy(librarySongSort = sort) }
        }
    }

    fun refreshLocal() = refreshLocalLibrary()

    fun refreshSpotify() {
        scope.launch { refreshSpotifyLibrary() }
    }

    fun pinHome(
        kind: HomePinKind,
        targetId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        providerEntityId: String? = null,
        provider: String? = null,
    ) {
        onPinHome(kind, targetId, title, subtitle, artworkUrl, providerEntityId, provider)
    }

    fun unpinHome(kind: HomePinKind, targetId: String) {
        onUnpinHome(kind, targetId)
    }

    fun createPlaylist(name: String) = onCreatePlaylist(name)

    fun renamePlaylist(id: String, title: String) = onRenamePlaylist(id, title)

    fun deletePlaylist(id: String) = onDeletePlaylist(id)

    fun reorderPlaylists(orderedIds: List<String>) = onReorderPlaylists(orderedIds)

    fun addTracks(playlistId: String, tracks: List<Track>) = onAddTracks(playlistId, tracks)

    fun removeEntry(playlistId: String, entryId: String) = onRemoveEntry(playlistId, entryId)

    suspend fun loadSpotifyPlaylistTracks(playlistId: String): List<Track> =
        onLoadSpotifyPlaylistTracks(playlistId)

    fun currentQueueTracks(): List<Track> = onCurrentQueueTracks()

    fun trackAvailability(track: Track): TrackAvailabilityInfo = onTrackAvailability(track)

    private fun initialState(): LibraryUiState = derive(
        CatalogSlice(
            settings = settings.value,
            saved = savedTracks.value,
            favorites = favorites.value,
            localTracks = localTracks.value,
            localState = localState.value,
        ),
        SpotifySlice(
            tracks = spotifyTracks.value,
            playlists = spotifyPlaylists.value,
            loading = spotifyLibraryLoading.value,
            error = spotifyLibraryError.value,
            providerState = spotifyState.value,
        ),
        PinsSlice(
            kainosRows = kainosPlaylistRows.value,
            homePins = homePins.value,
            downloads = downloads.value,
        ),
        queryText.value,
    )

    private fun derive(
        catalog: CatalogSlice,
        spotify: SpotifySlice,
        pins: PinsSlice,
        query: String,
    ): LibraryUiState = deriveLibraryUiState(
        settings = catalog.settings,
        saved = catalog.saved,
        favorites = catalog.favorites,
        localTracks = catalog.localTracks,
        localState = catalog.localState,
        spotifyTracks = spotify.tracks,
        spotifyPlaylists = spotify.playlists,
        kainosRows = pins.kainosRows,
        homePins = pins.homePins,
        spotifyLoading = spotify.loading,
        spotifyError = spotify.error,
        spotifyState = spotify.providerState,
        downloads = pins.downloads,
        query = query,
    )

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope): LibraryPresenter = LibraryPresenter(
            settings = container.settings,
            updateSettings = container::updateSettings,
            savedTracks = container.library.savedTracks,
            favorites = container.library.favoriteIds,
            localTracks = container.local.libraryTracks,
            localState = container.local.state,
            spotifyTracks = container.spotifyTracks,
            spotifyPlaylists = container.spotifyPlaylists,
            kainosPlaylistRows = container.kainosPlaylists.playlists,
            homePins = container.library.homePins,
            spotifyLibraryLoading = container.spotifyLibraryLoading,
            spotifyLibraryError = container.spotifyLibraryError,
            spotifyState = container.spotify.state,
            downloads = container.heartedAudio.downloads,
            scope = scope,
            refreshLocalLibrary = container::refreshLocalLibrary,
            refreshSpotifyLibrary = container::refreshSpotifyLibrary,
            onPinHome = { kind, targetId, title, subtitle, artworkUrl, providerEntityId, provider ->
                container.library.pinHome(
                    kind = kind,
                    targetId = targetId,
                    title = title,
                    subtitle = subtitle,
                    artworkUrl = artworkUrl,
                    providerEntityId = providerEntityId,
                    provider = provider,
                )
            },
            onUnpinHome = { kind, targetId ->
                container.library.unpinHome(kind, targetId)
            },
            onCreatePlaylist = { name -> container.kainosPlaylists.create(name) },
            onRenamePlaylist = { id, title -> container.kainosPlaylists.rename(id, title) },
            onDeletePlaylist = { id -> container.kainosPlaylists.delete(id) },
            onReorderPlaylists = { ids -> container.kainosPlaylists.reorderPlaylists(ids) },
            onAddTracks = { id, tracks -> container.kainosPlaylists.addTracks(id, tracks) },
            onRemoveEntry = { playlistId, entryId ->
                container.kainosPlaylists.removeEntry(playlistId, entryId)
            },
            onLoadSpotifyPlaylistTracks = container::loadSpotifyPlaylistTracks,
            onCurrentQueueTracks = {
                container.player.queue.queue.value.playbackOrder()
                    .mapNotNull { idx ->
                        container.player.queue.queue.value.items.getOrNull(idx)?.track
                    }
            },
            onTrackAvailability = container::trackAvailability,
        )
    }
}
