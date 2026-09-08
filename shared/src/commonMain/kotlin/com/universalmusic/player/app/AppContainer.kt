package com.universalmusic.player.app

import com.universalmusic.player.data.catalog.SampleCatalogProvider
import com.universalmusic.player.data.cache.MetadataArtworkCache
import com.universalmusic.player.data.cache.MetadataCacheStats
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.local.LocalLibraryRootMode
import com.universalmusic.player.data.local.LocalLibraryScanConfig
import com.universalmusic.player.data.local.LocalMusicProvider
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.data.spotify.findDiscoverWeekly
import com.universalmusic.player.data.spotify.loadSpotifyLibrary
import com.universalmusic.player.data.spotify.SpotifyProvider
import com.universalmusic.player.data.spotify.SpotifyRecommendationsAccess
import com.universalmusic.player.data.youtube.YouTubeMusicProvider
import com.universalmusic.player.domain.continuation.ContinuationOutcome
import com.universalmusic.player.domain.continuation.SearchAutoplayController
import com.universalmusic.player.domain.continuation.SearchContinuationFetcher
import com.universalmusic.player.domain.matching.TrackMatcher
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.platform.requiresExplicitSpotifyDevice
import com.universalmusic.player.platform.SpotifyPlaybackController
import com.universalmusic.player.platform.createSpotifyWebPlaybackHost
import com.universalmusic.player.platform.createYouTubeStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.PlayerSession
import com.universalmusic.player.domain.provider.MusicProvider
import com.universalmusic.player.domain.search.UnifiedSearch
import com.universalmusic.player.platform.createHttpClient
import com.universalmusic.player.platform.createLocalTrackSource
import com.universalmusic.player.platform.bindPlatformMediaControls
import com.universalmusic.player.platform.createMetadataArtworkCache
import com.universalmusic.player.platform.createPlaybackEngine
import com.universalmusic.player.platform.createSettingsStore
import com.universalmusic.player.platform.createTokenStore
import com.universalmusic.player.platform.createUserLibraryStore
import com.universalmusic.player.platform.defaultLocalMusicFolder
import com.universalmusic.player.platform.loadAppConfig
import com.universalmusic.player.platform.pickMusicFolder
import com.universalmusic.player.platform.releaseMusicFolderAccess
import com.universalmusic.player.platform.supportsMusicFolderPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI actions that platform shells (keyboard shortcuts, media integrations) can request. */
enum class UiRequest {
    FOCUS_SEARCH,
    TOGGLE_QUEUE,
    DISMISS_OVERLAY,
}

class AppContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val defaults: AppConfig = loadAppConfig()
    var config: AppConfig = defaults
        private set
    val http = createHttpClient()
    val tokens = createTokenStore()
    val settingsStore: SettingsStore = createSettingsStore()
    val userLibraryStore: UserLibraryStore = createUserLibraryStore()
    val metadataCache: MetadataArtworkCache = createMetadataArtworkCache()
    val library = LibraryRepository(
        scope = scope,
        store = userLibraryStore,
        metadataCache = metadataCache,
    )
    val matcher = TrackMatcher()
    val sample = SampleCatalogProvider()
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val local = LocalMusicProvider(createLocalTrackSource { localLibraryScanConfig() })
    private val _localLibraryMessage = MutableStateFlow<String?>(null)
    val localLibraryMessage: StateFlow<String?> = _localLibraryMessage.asStateFlow()
    private lateinit var spotifyProvider: SpotifyProvider
    val spotifyWebPlayback = createSpotifyWebPlaybackHost(
        tokenSupplier = { spotifyProvider.validAccessToken() },
    )
    val spotify = SpotifyProvider(
        http, tokens, config, webPlayback = spotifyWebPlayback,
        requireExplicitPlaybackDevice = requiresExplicitSpotifyDevice(),
        selectedPlaybackDeviceId = { _settings.value.spotifyPlaybackDeviceId },
    ).also {
        spotifyProvider = it
    }
    val youtubeStreams = createYouTubeStreamResolver()
    val youtube = YouTubeMusicProvider(http, config, youtubeStreams)
    val resolver = DefaultSourceResolver()
    private val settingsMutex = Mutex()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _spotifyTracks = MutableStateFlow<List<Track>>(emptyList())
    val spotifyTracks = _spotifyTracks.asStateFlow()
    private val _spotifyPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val spotifyPlaylists = _spotifyPlaylists.asStateFlow()
    private val _spotifyDiscoverWeekly = MutableStateFlow<Playlist?>(null)
    val spotifyDiscoverWeekly = _spotifyDiscoverWeekly.asStateFlow()
    private val _spotifyLibraryError = MutableStateFlow<String?>(null)
    val spotifyLibraryError = _spotifyLibraryError.asStateFlow()
    private val _spotifyLibraryLoading = MutableStateFlow(false)
    val spotifyLibraryLoading = _spotifyLibraryLoading.asStateFlow()
    private val libraryMutex = Mutex()

    val searchContinuation = SearchContinuationFetcher(spotify = spotify, youtube = youtube)
    val searchAutoplay = SearchAutoplayController(
        autoplayEnabled = { _settings.value.searchAutoplayEnabled },
        fetchContinuation = { seed, query, exclude ->
            searchContinuation.fetch(seed, query, exclude)
        },
    )

    val player = PlayerSession(
        engine = createPlaybackEngine(SpotifyPlaybackController(
            spotify::startConnectPlayback, spotify::pauseConnectPlayback,
            spotify::resumeConnectPlayback, spotify::seekConnectPlayback,
        )),
        resolver = resolver,
        scope = scope,
        enrichSource = { track, source ->
            val provider = when (source.provider) {
                ProviderId.LOCAL -> local
                ProviderId.SPOTIFY -> spotify
                ProviderId.YOUTUBE_MUSIC -> youtube
                ProviderId.SAMPLE -> sample
            }
            provider.getStream(track) ?: source
        },
        isFavorite = library::isFavorite,
        onQueueExhausted = { exclude ->
            when (val outcome = searchAutoplay.requestContinuation(exclude)) {
                is ContinuationOutcome.Appended -> outcome.tracks
                else -> emptyList()
            }
        },
    )

    private val _uiRequests = MutableSharedFlow<UiRequest>(extraBufferCapacity = 4)
    val uiRequests: SharedFlow<UiRequest> = _uiRequests.asSharedFlow()

    /** True while a search/library text field is focused; desktop shortcuts should no-op. */
    private val _textInputFocused = MutableStateFlow(false)
    val textInputFocused: StateFlow<Boolean> = _textInputFocused.asStateFlow()

    fun setTextInputFocused(focused: Boolean) {
        _textInputFocused.value = focused
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        searchAutoplay.clearSearchSession()
        player.play(tracks, startIndex = startIndex)
    }

    fun playSearchResults(tracks: List<Track>, startIndex: Int, query: String) {
        searchAutoplay.beginSearchPlayback(tracks, query)
        player.play(tracks, startIndex = startIndex)
    }

    fun clearPlaybackQueue() {
        searchAutoplay.clearSearchSession()
        player.clearQueue()
    }

    suspend fun spotifyRecommendationsAccess(): SpotifyRecommendationsAccess =
        spotify.ensureRecommendationsAccess()

    fun requestUi(request: UiRequest) {
        _uiRequests.tryEmit(request)
    }

    init {
        bindPlatformMediaControls(player, scope)
        scope.launch {
            val raw = settingsStore.read()
            val loaded = migrateLocalLibrarySettings(raw)
            if (loaded != raw) settingsStore.write(loaded)
            _settings.value = loaded
            player.updatePreferences(loaded.toPlaybackPreferences())
            applyProviderSettings(loaded, clearSessionOnChange = false)
            library.load(spotify.currentUserId())
            refreshLocalLibrary()
            _ready.value = true
            if (spotify.isAuthenticated()) refreshSpotifyLibrary()
        }
    }

    private fun migrateLocalLibrarySettings(settings: AppSettings): AppSettings {
        if (settings.localMusicFolders.isNotEmpty() && !settings.localMusicFoldersConfigured) {
            return settings.copy(localMusicFoldersConfigured = true)
        }
        return settings
    }

    private fun localLibraryScanConfig(): LocalLibraryScanConfig {
        val settings = _settings.value
        return LocalLibraryScanConfig(
            mode = if (settings.localMusicFoldersConfigured) {
                LocalLibraryRootMode.EXPLICIT
            } else {
                LocalLibraryRootMode.USE_DEFAULTS
            },
            folders = settings.localMusicFolders,
            includeMediaStore = settings.includeMediaStoreLibrary,
        )
    }

    /** Global Search providers: Spotify and YouTube only (Library keeps local search). */
    fun providersForSearch(): List<MusicProvider> = buildList {
        if (spotify.state.value != ProviderState.NOT_CONFIGURED) add(spotify)
        if (youtube.state.value != ProviderState.NOT_CONFIGURED) add(youtube)
    }

    fun unifiedSearch(): UnifiedSearch = UnifiedSearch(providersForSearch(), matcher)

    fun searchProvidersConfigured(): Boolean = providersForSearch().isNotEmpty()

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsMutex.withLock {
        val previous = _settings.value
        val transformed = transform(previous)
        val next = if (transformed.spotifyClientId != previous.spotifyClientId) {
            transformed.copy(spotifyPlaybackDeviceId = null, spotifyPlaybackDeviceName = null)
        } else transformed
        settingsStore.write(next)
        if (next.spotifyClientId != previous.spotifyClientId || next.youtubeDataApiKey != previous.youtubeDataApiKey) {
            applyProviderSettings(next)
        }
        _settings.value = next
        player.updatePreferences(next.toPlaybackPreferences())
    }

    private suspend fun applyProviderSettings(settings: AppSettings, clearSessionOnChange: Boolean = true) {
        val next = defaults.copy(
            spotifyClientId = settings.spotifyClientId?.takeIf { it.isNotBlank() } ?: defaults.spotifyClientId,
            youtubeDataApiKey = settings.youtubeDataApiKey?.takeIf { it.isNotBlank() } ?: defaults.youtubeDataApiKey,
        )
        if (next.spotifyClientId != config.spotifyClientId) clearSpotifyLibrary()
        config = next
        youtube.updateConfig(next)
        spotify.updateConfig(next, clearSessionOnChange)
    }

    suspend fun disconnectSpotify() {
        spotify.logout()
        updateSettings { it.copy(spotifyPlaybackDeviceId = null, spotifyPlaybackDeviceName = null) }
        clearSpotifyLibrary()
        library.setSpotifyAccountId(null)
    }

    private fun clearSpotifyLibrary() {
        _spotifyTracks.value = emptyList()
        _spotifyPlaylists.value = emptyList()
        _spotifyDiscoverWeekly.value = null
        _spotifyLibraryError.value = null
    }

    suspend fun clearMetadataArtworkCache(): MetadataCacheStats {
        metadataCache.clear()
        return metadataCache.stats()
    }

    suspend fun metadataCacheStats(): MetadataCacheStats = metadataCache.stats()

    suspend fun refreshSpotifyLibrary() = libraryMutex.withLock {
        _spotifyLibraryLoading.value = true
        _spotifyLibraryError.value = null
        try {
            val result = loadSpotifyLibrary(spotify)
            spotify.currentUserId()?.let { library.setSpotifyAccountId(it) }
            if (spotify.isAuthenticated() || spotify.state.value == ProviderState.RATE_LIMITED) {
                result.tracks.onSuccess { _spotifyTracks.value = it }
                result.playlists.onSuccess { playlists ->
                    _spotifyPlaylists.value = playlists
                    _spotifyDiscoverWeekly.value = findDiscoverWeekly(playlists)
                }
                val failedSections = buildList {
                    if (result.tracks.isFailure) add("liked songs")
                    if (result.playlists.isFailure) add("playlists")
                }
                if (failedSections.isNotEmpty()) {
                    val quota = listOf(result.tracks, result.playlists).any { part ->
                        part.exceptionOrNull()?.message?.contains("quota", ignoreCase = true) == true ||
                            part.exceptionOrNull()?.message?.contains("QUOTA", ignoreCase = false) == true
                    }
                    _spotifyLibraryError.value = if (quota) {
                        "Spotify development quota exceeded. Keep using the library already loaded; try refreshing later."
                    } else {
                        "Could not load Spotify ${failedSections.joinToString(" and ")}. Successfully loaded sections are still available. Try refreshing."
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = failure.message.orEmpty()
            _spotifyLibraryError.value = if ("quota" in message.lowercase() || "QUOTA" in message) {
                "Spotify development quota exceeded. Keep using the library already loaded; try refreshing later."
            } else {
                "Could not load your Spotify library. Check your connection and Spotify access, then refresh."
            }
        } finally {
            _spotifyLibraryLoading.value = false
        }
    }

    /** Load full track list for a Spotify playlist and return playable tracks. */
    suspend fun loadSpotifyPlaylistTracks(playlistId: String): List<Track> {
        val id = playlistId.trim()
        require(id.isNotEmpty()) { "Playlist id is required" }
        return spotify.getPlaylistTracks(id)
    }

    fun refreshLocalLibrary() {
        scope.launch {
            _localLibraryMessage.value = null
            runCatching { local.refresh() }
                .onFailure { failure ->
                    _localLibraryMessage.value = failure.message ?: "Local library scan failed"
                }
        }
    }

    /** Folders currently shown in Settings (configured list, or platform default when not configured). */
    fun effectiveLocalMusicFolders(): List<String> {
        val settings = _settings.value
        if (settings.localMusicFoldersConfigured) return settings.localMusicFolders
        return listOfNotNull(defaultLocalMusicFolder().takeIf { it.isNotBlank() })
    }

    fun addLocalMusicFolderFromPicker() {
        if (!supportsMusicFolderPicker()) return
        scope.launch {
            val picked = pickMusicFolder() ?: return@launch
            updateSettings { current ->
                val base = if (current.localMusicFoldersConfigured) {
                    current.localMusicFolders
                } else {
                    listOfNotNull(defaultLocalMusicFolder().takeIf { it.isNotBlank() })
                }
                current.copy(
                    localMusicFoldersConfigured = true,
                    localMusicFolders = (base + picked).distinct(),
                )
            }
            runCatching { local.refresh() }
                .onFailure { failure ->
                    _localLibraryMessage.value = failure.message ?: "Local library scan failed"
                }
                .onSuccess { _localLibraryMessage.value = null }
        }
    }

    fun removeLocalMusicFolder(path: String) {
        scope.launch {
            releaseMusicFolderAccess(path)
            updateSettings { current ->
                val base = if (current.localMusicFoldersConfigured) {
                    current.localMusicFolders
                } else {
                    listOfNotNull(defaultLocalMusicFolder().takeIf { it.isNotBlank() })
                }
                current.copy(
                    localMusicFoldersConfigured = true,
                    localMusicFolders = base.filterNot { it == path },
                )
            }
            runCatching { local.refresh() }
                .onFailure { failure ->
                    _localLibraryMessage.value = failure.message ?: "Local library scan failed"
                }
                .onSuccess { _localLibraryMessage.value = null }
        }
    }

    fun setIncludeMediaStoreLibrary(include: Boolean) {
        scope.launch {
            updateSettings { it.copy(includeMediaStoreLibrary = include) }
            refreshLocalLibrary()
        }
    }

    fun providerState(id: ProviderId): StateFlow<ProviderState> = when (id) {
        ProviderId.LOCAL -> local.state
        ProviderId.SPOTIFY -> spotify.state
        ProviderId.YOUTUBE_MUSIC -> youtube.state
        ProviderId.SAMPLE -> sample.state
    }
}

lateinit var appContainer: AppContainer

fun ensureAppContainer(): AppContainer {
    if (!::appContainer.isInitialized) {
        appContainer = AppContainer()
    }
    return appContainer
}
