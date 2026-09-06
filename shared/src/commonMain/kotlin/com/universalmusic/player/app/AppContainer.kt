package com.universalmusic.player.app

import com.universalmusic.player.data.catalog.SampleCatalogProvider
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.local.LocalMusicProvider
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.data.spotify.loadSpotifyLibrary
import com.universalmusic.player.data.spotify.SpotifyProvider
import com.universalmusic.player.data.youtube.YouTubeMusicProvider
import com.universalmusic.player.domain.matching.TrackMatcher
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.platform.SpotifyPlaybackController
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
import com.universalmusic.player.platform.createPlaybackEngine
import com.universalmusic.player.platform.createSettingsStore
import com.universalmusic.player.platform.createTokenStore
import com.universalmusic.player.platform.defaultLocalMusicFolder
import com.universalmusic.player.platform.loadAppConfig
import com.universalmusic.player.platform.pickMusicFolder
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
}

class AppContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val defaults: AppConfig = loadAppConfig()
    var config: AppConfig = defaults
        private set
    val http = createHttpClient()
    val tokens = createTokenStore()
    val settingsStore: SettingsStore = createSettingsStore()
    val library = LibraryRepository()
    val matcher = TrackMatcher()
    val sample = SampleCatalogProvider()
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val local = LocalMusicProvider(createLocalTrackSource { _settings.value.localMusicFolders })
    val spotify = SpotifyProvider(http, tokens, config)
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
    private val _spotifyLibraryError = MutableStateFlow<String?>(null)
    val spotifyLibraryError = _spotifyLibraryError.asStateFlow()
    private val _spotifyLibraryLoading = MutableStateFlow(false)
    val spotifyLibraryLoading = _spotifyLibraryLoading.asStateFlow()
    private val libraryMutex = Mutex()

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
    )

    private val _uiRequests = MutableSharedFlow<UiRequest>(extraBufferCapacity = 4)
    val uiRequests: SharedFlow<UiRequest> = _uiRequests.asSharedFlow()

    fun requestUi(request: UiRequest) {
        _uiRequests.tryEmit(request)
    }

    init {
        scope.launch {
            val loaded = settingsStore.read()
            _settings.value = loaded
            player.updatePreferences(loaded.toPlaybackPreferences())
            refreshLocalLibrary()
            applyProviderSettings(loaded, clearSessionOnChange = false)
            _ready.value = true
            if (spotify.isAuthenticated()) refreshSpotifyLibrary()
        }
    }

    fun providersForSearch(includeSample: Boolean = _settings.value.sampleCatalogEnabled): List<MusicProvider> {
        val live = buildList {
            add(local)
            if (spotify.state.value != ProviderState.NOT_CONFIGURED) add(spotify)
            if (youtube.state.value != ProviderState.NOT_CONFIGURED) add(youtube)
        }
        return if (includeSample) live + sample else live.ifEmpty { listOf(sample) }
    }

    fun unifiedSearch(): UnifiedSearch = UnifiedSearch(providersForSearch(), matcher)

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsMutex.withLock {
        val previous = _settings.value
        val next = transform(previous)
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
        clearSpotifyLibrary()
    }

    private fun clearSpotifyLibrary() {
        _spotifyTracks.value = emptyList()
        _spotifyPlaylists.value = emptyList()
        _spotifyLibraryError.value = null
    }

    suspend fun refreshSpotifyLibrary() = libraryMutex.withLock {
        _spotifyLibraryLoading.value = true
        _spotifyLibraryError.value = null
        try {
            val result = loadSpotifyLibrary(spotify)
            if (spotify.isAuthenticated()) {
                result.tracks.onSuccess { _spotifyTracks.value = it }
                result.playlists.onSuccess { _spotifyPlaylists.value = it }
                val failedSections = buildList {
                    if (result.tracks.isFailure) add("liked songs")
                    if (result.playlists.isFailure) add("playlists")
                }
                if (failedSections.isNotEmpty()) {
                    _spotifyLibraryError.value = "Could not load Spotify ${failedSections.joinToString(" and ")}. Successfully loaded sections are still available. Try refreshing."
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _spotifyLibraryError.value = "Could not load your Spotify library. Check your connection and Spotify access, then refresh."
        } finally {
            _spotifyLibraryLoading.value = false
        }
    }

    fun refreshLocalLibrary() {
        scope.launch { runCatching { local.refresh() } }
    }

    /** Folders currently used for desktop scanning (settings, or the platform default). */
    fun effectiveLocalMusicFolders(): List<String> {
        val configured = _settings.value.localMusicFolders
        if (configured.isNotEmpty()) return configured
        return listOfNotNull(defaultLocalMusicFolder().takeIf { it.isNotBlank() })
    }

    fun addLocalMusicFolderFromPicker() {
        if (!supportsMusicFolderPicker()) return
        scope.launch {
            val picked = pickMusicFolder() ?: return@launch
            updateSettings { current ->
                val base = current.localMusicFolders.ifEmpty {
                    listOfNotNull(defaultLocalMusicFolder().takeIf { it.isNotBlank() })
                }
                current.copy(localMusicFolders = (base + picked).distinct())
            }
            runCatching { local.refresh() }
        }
    }

    fun removeLocalMusicFolder(path: String) {
        scope.launch {
            updateSettings { current ->
                val base = current.localMusicFolders.ifEmpty {
                    listOfNotNull(defaultLocalMusicFolder().takeIf { it.isNotBlank() })
                }
                current.copy(localMusicFolders = base.filterNot { it == path })
            }
            runCatching { local.refresh() }
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
