package com.universalmusic.player.app

import com.universalmusic.player.data.catalog.SampleCatalogProvider
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.data.soundcloud.SoundCloudProvider
import com.universalmusic.player.data.spotify.SpotifyProvider
import com.universalmusic.player.data.youtube.YouTubeMusicProvider
import com.universalmusic.player.domain.matching.TrackMatcher
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.PlayerSession
import com.universalmusic.player.domain.provider.MusicProvider
import com.universalmusic.player.domain.search.UnifiedSearch
import com.universalmusic.player.platform.createHttpClient
import com.universalmusic.player.platform.createPlaybackEngine
import com.universalmusic.player.platform.createSettingsStore
import com.universalmusic.player.platform.createTokenStore
import com.universalmusic.player.platform.loadAppConfig
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
    val config: AppConfig = loadAppConfig()
    val http = createHttpClient()
    val tokens = createTokenStore()
    val settingsStore: SettingsStore = createSettingsStore()
    val library = LibraryRepository()
    val matcher = TrackMatcher()
    val sample = SampleCatalogProvider()
    val spotify = SpotifyProvider(http, tokens, config)
    val youtube = YouTubeMusicProvider(http, config)
    val soundcloud = SoundCloudProvider(http, config)
    val resolver = DefaultSourceResolver()

    val player = PlayerSession(
        engine = createPlaybackEngine { trackId -> spotify.startConnectPlayback(trackId) },
        resolver = resolver,
        scope = scope,
    )

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

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
            spotify.restore()
        }
    }

    fun providersForSearch(includeSample: Boolean = _settings.value.sampleCatalogEnabled): List<MusicProvider> {
        val live = buildList {
            if (spotify.state.value != ProviderState.NOT_CONFIGURED) add(spotify)
            if (youtube.state.value != ProviderState.NOT_CONFIGURED) add(youtube)
            if (soundcloud.state.value != ProviderState.NOT_CONFIGURED) add(soundcloud)
        }
        return if (includeSample) live + sample else live.ifEmpty { listOf(sample) }
    }

    fun unifiedSearch(): UnifiedSearch = UnifiedSearch(providersForSearch(), matcher)

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        settingsStore.write(next)
        player.updatePreferences(next.toPlaybackPreferences())
    }

    fun providerState(id: ProviderId): StateFlow<ProviderState> = when (id) {
        ProviderId.SPOTIFY -> spotify.state
        ProviderId.YOUTUBE_MUSIC -> youtube.state
        ProviderId.SOUNDCLOUD -> soundcloud.state
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
