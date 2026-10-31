package com.universalmusic.player.app

import com.universalmusic.player.data.cache.MetadataArtworkCache
import com.universalmusic.player.data.cache.MetadataCacheStats
import com.universalmusic.player.data.cache.HeartedAudioCacheService
import com.universalmusic.player.data.cache.HeartedAudioCacheStats
import com.universalmusic.player.data.cache.toLocalPlaybackSource
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.KainosPlaylistStore
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.ListeningStore
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerReceipt
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.local.LocalLibraryRootMode
import com.universalmusic.player.data.local.LocalLibraryScanConfig
import com.universalmusic.player.data.local.LocalMusicProvider
import com.universalmusic.player.data.local.cacheKey
import com.universalmusic.player.data.local.createLocalContentKeyReader
import com.universalmusic.player.data.local.createLocalEmbeddedArtworkExtractor
import com.universalmusic.player.data.local.isLocationInFolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.coroutines.EmptyCoroutineContext
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.data.spotify.findDiscoverWeekly
import com.universalmusic.player.data.spotify.loadSpotifyLibrary
import com.universalmusic.player.data.spotify.parseSpotifyPlaylistId
import com.universalmusic.player.data.spotify.resolveDiscoverWeeklyCandidate
import com.universalmusic.player.data.spotify.SpotifyProvider
import com.universalmusic.player.data.youtube.YouTubeMusicProvider
import com.universalmusic.player.domain.continuation.SourceRadio
import com.universalmusic.player.domain.continuation.planSearchPlayback
import com.universalmusic.player.domain.matching.TrackMatcher
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.platform.fetchLibrespotDiscoverWeekly
import com.universalmusic.player.platform.requiresExplicitSpotifyDevice
import com.universalmusic.player.platform.SpotifyPlaybackController
import com.universalmusic.player.platform.createSpotifyWebPlaybackHost
import com.universalmusic.player.platform.createYouTubeAudioDownloader
import com.universalmusic.player.platform.createYouTubeStreamResolver
import com.universalmusic.player.platform.createHeartedAudioCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.PlayerSession
import com.universalmusic.player.domain.playback.FlushResult
import com.universalmusic.player.domain.playback.HandoffPeer
import com.universalmusic.player.domain.playback.ListeningHandoff
import com.universalmusic.player.domain.playback.RemotePlayerAdapter
import com.universalmusic.player.data.sync.RemoteLinkHost
import com.universalmusic.player.data.sync.listeningDeliveryTarget
import com.universalmusic.player.data.sync.openPairedRemoteLink
import com.universalmusic.player.domain.playback.NowPlayingRecorder
import com.universalmusic.player.domain.playback.SleepTimerController
import com.universalmusic.player.domain.provider.MusicProvider
import com.universalmusic.player.domain.search.UnifiedSearch
import com.universalmusic.player.data.sync.HomeLanSyncService
import com.universalmusic.player.data.sync.HttpHomeLanSyncClient
import com.universalmusic.player.data.sync.basenameFromLocalLocation
import com.universalmusic.player.platform.createHomeLanSyncHub
import com.universalmusic.player.platform.createHomeLanVaultStore
import com.universalmusic.player.platform.createPinnedHomeLanHttpClient
import com.universalmusic.player.platform.platformDeviceIdentity
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.homeLanDeviceName
import com.universalmusic.player.data.sync.HttpHomeLanPairingClient
import com.universalmusic.player.platform.homeLanHubPort
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.createHttpClient
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.platform.createLocalLibraryScanCache
import com.universalmusic.player.platform.createLocalTrackSource
import com.universalmusic.player.platform.bindPlatformMediaControls
import com.universalmusic.player.platform.createMetadataArtworkCache
import com.universalmusic.player.platform.createPlaybackEngine
import com.universalmusic.player.platform.createSettingsStore
import com.universalmusic.player.platform.createListeningExchangeStore
import com.universalmusic.player.platform.createListeningStore
import com.universalmusic.player.platform.createTokenStore
import com.universalmusic.player.platform.createUserLibraryStore
import com.universalmusic.player.platform.createKainosPlaylistStore
import com.universalmusic.player.platform.defaultLocalMusicFolder
import com.universalmusic.player.platform.loadAppConfig
import com.universalmusic.player.platform.pickMusicFolder
import com.universalmusic.player.platform.releaseMusicFolderAccess
import com.universalmusic.player.platform.supportsMusicFolderPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** UI actions that platform shells (keyboard shortcuts, media integrations) can request. */
enum class UiRequest {
    FOCUS_SEARCH,
    TOGGLE_QUEUE,
    DISMISS_OVERLAY,
    OPEN_NOW_PLAYING,
    /** Desktop type-to-search: open Library search and hand it [AppContainer.libraryTypeAhead]. */
    FOCUS_LIBRARY_SEARCH,
}

class AppContainer {
    val storageError = com.universalmusic.player.data.db.StorageErrors.message
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
        kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> com.universalmusic.player.data.db.StorageErrors.report() })

    private suspend fun storageStep(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { com.universalmusic.player.data.db.StorageErrors.report() }
    }
    private val defaults: AppConfig = loadAppConfig()
    var config: AppConfig = defaults
        private set
    val http = createHttpClient()
    val tokens = createTokenStore()
    val settingsStore: SettingsStore = createSettingsStore()
    val userLibraryStore: UserLibraryStore = createUserLibraryStore()
    val kainosPlaylistStore: KainosPlaylistStore = createKainosPlaylistStore()
    val listeningStore: ListeningStore = createListeningStore()
    private val foregroundHandoff = ForegroundHandoff { requestHandoff() }
    private val listeningExchange = PeerNotifyingExchangeStore(createListeningExchangeStore(), foregroundHandoff::onPeerChanged)

    private val foreground = AppForegroundState()
    val metadataCache: MetadataArtworkCache = createMetadataArtworkCache()
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private var favoriteAudioHook: ((Track, Boolean) -> Unit)? = null
    private var currentPlayingCanonicalId: () -> String? = { null }
    private fun protectedCacheOwners(): Set<String> =
        setOfNotNull(currentPlayingCanonicalId())
    val library = LibraryRepository(
        scope = scope,
        store = userLibraryStore,
        metadataCache = metadataCache,
        onFavoriteChanged = { track, nowFavorite -> favoriteAudioHook?.invoke(track, nowFavorite) },
        deviceIdProvider = { _settings.value.homeLanSyncDeviceId ?: "local" },
        localTrackCatalog = { local.libraryTracks.value },
    )
    val kainosPlaylists = KainosPlaylistRepository(
        scope = scope,
        store = kainosPlaylistStore,
        deviceIdProvider = { _settings.value.homeLanSyncDeviceId ?: "local" },
        localTrackCatalog = { local.libraryTracks.value },
    )
    val matcher = TrackMatcher()
    private val sampleUnavailable = MutableStateFlow(ProviderState.UNAVAILABLE)
    val local = LocalMusicProvider(
        source = createLocalTrackSource { localLibraryScanConfig() },
        cache = createLocalLibraryScanCache(),
        configKey = { localLibraryScanConfig().cacheKey() },
        embeddedArtwork = createLocalEmbeddedArtworkExtractor(),
        contentKeyReader = createLocalContentKeyReader(),
    )
    private val _localLibraryMessage = MutableStateFlow<String?>(null)
    val localLibraryMessage: StateFlow<String?> = _localLibraryMessage.asStateFlow()
    private var localLibraryRefreshJob: Job? = null
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
    val heartedAudio = HeartedAudioCacheService(
        scope = scope,
        cache = createHeartedAudioCache(),
        downloader = createYouTubeAudioDownloader(youtubeStreams),
        youtubeSearch = { query -> youtube.search(query).tracks },
        matcher = matcher,
        protectedOwners = { protectedCacheOwners() },
        onCached = { canonicalId, entry ->
            library.attachHeartedCacheSource(canonicalId, entry.toLocalPlaybackSource())
        },
        onRemoved = { canonicalId ->
            library.stripHeartedCacheSource(canonicalId)
        },
    ).also { service ->
        favoriteAudioHook = { track, nowFavorite ->
            if (nowFavorite) service.enqueue(track)
            else service.cancelAndRemove(track.canonicalId)
        }
    }
    val homeLanVault = createHomeLanVaultStore(
        vaultRootProvider = { _settings.value.homeLanSyncVaultFolder },
    )
    val homeLanSync: HomeLanSyncService = HomeLanSyncService(
        scope = scope,
        settings = { _settings.value },
        updateSettings = { transform -> updateSettings(transform) },
        library = library,
        playlists = kainosPlaylists,
        hubFactory = { config ->
            createHomeLanSyncHub(com.universalmusic.player.platform.HomeLanHubConfig(
                deviceId = config.deviceId,
                port = config.port,
                peers = config.peers,
                pairing = config.pairing,
                onHearts = { remote ->
                    library.awaitLoaded()
                    config.onHearts(remote)
                    rematchLocalHeartsByFileName()
                    library.exportHeartsSyncDocument()
                },
                onPlaylists = { remote ->
                    kainosPlaylists.awaitLoaded()
                    config.onPlaylists(remote)
                    rematchPlaylistLocalsByFileName()
                    kainosPlaylists.exportSyncDocument()
                },
                vault = config.vault,
                heartedVaultFileNames = config.heartedVaultFileNames,
                listening = config.listening,
                remote = remoteHost,
                guiPlayer = { remotePlayer.takeIf { guiPlayerEnabled.value } },
            ))
        },
        clientFactory = { peer ->
            HttpHomeLanSyncClient(peer, library, kainosPlaylists, createPinnedHomeLanHttpClient(peer.fingerprint))
        },
        pairingClientFactory = { endpoint, pin ->
            HttpHomeLanPairingClient(endpoint, createPinnedHomeLanHttpClient(pin))
        },
        identity = platformDeviceIdentity(),
        vaultStore = homeLanVault,
        refreshLocalLibrary = { refreshLocalLibraryAndAwait() },
        rematchLocalHearts = { rematchLocalHeartsByFileName() },
        rematchPlaylistLocals = { rematchPlaylistLocalsByFileName() },
        hubPort = homeLanHubPort(),
        listening = listeningExchange,
        retryListeningInProcess = { foreground.isVisible || player.nowPlaying.value.isPlaying },
        isAppForeground = { foreground.isVisible },
        onForegroundListeningCompleted = foregroundHandoff::onExchangeCompleted,
    )
    val resolver = DefaultSourceResolver()
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

    val player = PlayerSession(
        engine = createPlaybackEngine(SpotifyPlaybackController(
            spotify::startConnectPlayback, spotify::pauseConnectPlayback,
            spotify::resumeConnectPlayback, spotify::seekConnectPlayback,
            observe = spotify::observeConnectPlayback,
            receiverEvents = spotifyWebPlayback.receiverEvents,
        )),
        resolver = resolver,
        scope = scope,
        enrichSource = { track, source ->
            val provider = when (source.provider) {
                ProviderId.LOCAL -> local
                ProviderId.SPOTIFY -> spotify
                ProviderId.YOUTUBE_MUSIC -> youtube
                ProviderId.SAMPLE -> null
            }
            provider?.getStream(track) ?: source
        },
        isFavorite = library::isFavorite,
        prepareTrack = { heartedAudio.applyCacheToTrack(it) },
        confineTo = playbackDispatcher(),
        workContext = Dispatchers.Default,
        persistence = NowPlayingRecorder(
            store = PublicationNotifyingStore(listeningStore) { homeLanSync.requestListeningDelivery() },
            scope = scope,
        ),
    ).also {
        currentPlayingCanonicalId = { it.nowPlaying.value.track?.canonicalId }
    }
    val sleepTimer = SleepTimerController(
        player = player,
        scope = scope,
    )
    val listeningHandoff = ListeningHandoff(listeningExchange, player, localTracks = { local.libraryTracks.value })

    /** Set only by a GUI entry point; a `--hub-only` process never lets remote devices see or drive its player. */
    private val guiPlayerEnabled = MutableStateFlow(false)
    private val remotePlayer = RemotePlayerAdapter(player, scope)
    private val remoteHost = RemoteLinkHost(hubName = ::homeLanDeviceName)
    val remoteControl = RemoteControl(
        scope = scope,
        settings = settings,
        isDesktop = platformLabel() == "Linux",
        host = remoteHost,
        player = remotePlayer,
        handoff = listeningHandoff,
        guiPlayer = guiPlayerEnabled,
        linkWanted = remoteLinkWanted(
            visible = foreground.visible,
            active = player.nowPlaying.map { it.isPlaying || it.buffering }.distinctUntilChanged(),
        ),
        clientTarget = {
            _settings.value.takeIf { _ready.value }
                ?.listeningDeliveryTarget(platformLabel() == "Linux", platformDeviceIdentity().fingerprint())
        },
        openLink = ::openPairedRemoteLink,
        onLinkConnected = { homeLanSync.requestListeningDelivery() },
    )
    private val handoffRequests = Channel<Unit>(Channel.CONFLATED)

    private val settingsUpdater = AppSettingsUpdater(scope, settingsStore, _settings, { applyProviderSettings(it) }) {
        player.updatePreferences(it.toPlaybackPreferences())
    }

    // A Channel keeps requests sent before Compose starts collecting. SharedFlow's
    // extraBufferCapacity only helps when a subscriber already exists.
    private val _uiRequests = Channel<UiRequest>(capacity = Channel.BUFFERED)
    val uiRequests: Flow<UiRequest> = _uiRequests.receiveAsFlow()

    /** True while any text field is focused; desktop shortcuts and type-to-search should no-op. */
    private val _textInputFocused = MutableStateFlow(false)
    val textInputFocused: StateFlow<Boolean> = _textInputFocused.asStateFlow()
    private val focusedTextInputs = mutableSetOf<Any>()

    /** Called by [com.universalmusic.player.ui.reportsTextInputFocus] on the UI thread. */
    fun setTextInputFocused(field: Any, focused: Boolean) {
        if (focused) focusedTextInputs += field else focusedTextInputs -= field
        _textInputFocused.value = focusedTextInputs.isNotEmpty()
    }

    /** Keystrokes typed while no field had focus, waiting for Library search to pick them up. */
    private val _libraryTypeAhead = MutableStateFlow("")
    val libraryTypeAhead: StateFlow<String> = _libraryTypeAhead.asStateFlow()

    fun typeAheadLibrarySearch(text: String) {
        if (text.isEmpty()) return
        _libraryTypeAhead.update { it + text }
        requestUi(UiRequest.FOCUS_LIBRARY_SEARCH)
    }

    fun consumeLibraryTypeAhead(): String = _libraryTypeAhead.getAndUpdate { "" }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        player.play(tracks, startIndex = startIndex)
    }

    /** The picked result keeps its source: that source's radio follows it, or its same-source results. */
    fun playSearchResults(tracks: List<Track>, startIndex: Int) {
        val plan = planSearchPlayback(tracks, startIndex, radioEnabled = _settings.value.searchRadioEnabled)
        val radio = plan.radio?.let { provider ->
            SourceRadio(provider, fetch = { seed, exclude ->
                when (provider) {
                    ProviderId.SPOTIFY -> spotify.radio(seed, exclude)
                    ProviderId.YOUTUBE_MUSIC -> youtube.mix(seed)
                    else -> error("$provider has no radio")
                }
            })
        }
        player.play(plan.tracks, startIndex = plan.startIndex, continuation = radio)
    }

    fun clearPlaybackQueue() {
        player.clearQueue()
    }

    fun requestUi(request: UiRequest) {
        _uiRequests.trySend(request)
    }

    init {
        monitorDownloadConnectivity(scope, heartedAudio::notifyNetworkChanged)
        bindPlatformMediaControls(player, scope) { track ->
            library.toggleFavorite(track).also(player::setFavorite)
        }
        // The Now Playing / media-session heart follows the library, including hearts that
        // arrive from Home sync while the same track keeps playing.
        scope.launch {
            combine(
                player.nowPlaying.map { it.track?.canonicalId }.distinctUntilChanged(),
                library.favoriteIds,
            ) { id, favorites -> id to (id != null && id in favorites) }
                .collect { (id, favorite) -> if (id != null) player.syncFavorite(id, favorite) }
        }
        // Local track with no cover yet: pull the embedded picture and show it on Now Playing,
        // the queue row and the media session without waiting for the background pass.
        scope.launch {
            player.nowPlaying
                .map { it.track }
                .distinctUntilChangedBy { it?.canonicalId }
                .collectLatest { track ->
                    if (track == null || track.artwork != null) return@collectLatest
                    if (track.sourceFor(ProviderId.LOCAL) == null) return@collectLatest
                    val artwork = runCatching { local.resolveEmbeddedArtwork(track) }
                        .getOrElse { if (it is CancellationException) throw it else null }
                        ?: return@collectLatest
                    player.updateCurrentTrackArtwork(track.canonicalId, artwork)
                }
        }
        scope.launch {
            storageStep {
                val raw = settingsStore.read()
                val loaded = migrateLocalLibrarySettings(raw)
                if (loaded != raw) settingsStore.write(loaded)
                _settings.value = loaded
                homeLanSync.hydrateFromSettings(loaded)
                if (loaded.homeLanSyncHubAutostart) {
                    homeLanSync.setHubAutostartEnabled(true)
                }
                player.updatePreferences(loaded.toPlaybackPreferences())
                player.setVolume(loaded.playbackVolume)
                applyProviderSettings(loaded, clearSessionOnChange = false)
            }
            storageStep { library.load(spotify.currentUserId()) }
            storageStep { kainosPlaylists.load() }
            val favorites = library.favoriteIds.value
            val hearted = library.savedTracks.value.filter { it.canonicalId in favorites }
            heartedAudio.enqueueMissing(hearted)
            runCatching { local.hydrateFromCache() }
            refreshLocalLibrary()
            // Restore listening session paused; never auto-start audio after process death.
            storageStep { player.initializePersistence() }
            _ready.value = true
            foreground.onReady {
                foregroundHandoff.onForeground()
                homeLanSync.onAppForeground()
            }
            if (spotify.isAuthenticated()) refreshSpotifyLibrary()
        }
        scope.launch {
            _ready.first { it }
            for (request in handoffRequests) runHandoff()
        }
    }

    fun enableListeningHandoff() {
        foregroundHandoff.enable()
    }

    /** Lets paired devices show and control this process's player; only GUI entry points call it. */
    fun enableGuiPlayer() {
        guiPlayerEnabled.value = true
    }

    private fun requestHandoff() {
        if (foreground.isVisible) handoffRequests.trySend(Unit)
    }

    private suspend fun runHandoff() {
        val settings = _settings.value
        if (!settings.homeLanSyncEnabled) return
        val peers = (listOfNotNull(settings.homeLanSyncHub) + settings.homeLanSyncPeers)
            .map { HandoffPeer(it.deviceId, it.displayName) }
        listeningHandoff.run(peers)
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

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsUpdater.update(transform)

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

    suspend fun clearHeartedAudioCache(): HeartedAudioCacheStats {
        val before = heartedAudio.stats()
        heartedAudio.clear()
        library.favoriteIds.value.forEach { library.stripHeartedCacheSource(it) }
        return before
    }

    suspend fun heartedAudioCacheStats(): HeartedAudioCacheStats = heartedAudio.stats()

    fun trackAvailability(track: Track) = heartedAudio.availabilityFor(track)

    fun retryHeartedDownload(canonicalId: String) = heartedAudio.retry(canonicalId)

    fun removeHeartedDownload(canonicalId: String) = heartedAudio.cancelAndRemove(canonicalId)

    fun setHeartedDownloadPinned(canonicalId: String, pinned: Boolean) =
        heartedAudio.setPinned(canonicalId, pinned)

    fun setYouTubeMatchOverride(track: Track, youtubeVideoId: String) =
        heartedAudio.setYouTubeMatchOverride(track, youtubeVideoId)

    fun clearYouTubeMatchOverride(track: Track) =
        heartedAudio.clearYouTubeMatchOverride(track)

    suspend fun searchYouTubeMatchCandidates(track: Track, query: String? = null) =
        heartedAudio.searchMatchCandidates(track, query)

    suspend fun youTubeMatchOverride(canonicalId: String): String? =
        heartedAudio.matchOverride(canonicalId)

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
                    _spotifyDiscoverWeekly.value = resolveDiscoverWeekly(playlists)
                }
                val failedSections = buildList {
                    if (result.tracks.isFailure) add("liked songs")
                    if (result.playlists.isFailure) add("playlists")
                }
                if (failedSections.isNotEmpty()) {
                    val failureText = listOf(result.tracks, result.playlists)
                        .mapNotNull { it.exceptionOrNull()?.message }
                        .joinToString(" ")
                    _spotifyLibraryError.value = spotifyLibraryFailureMessage(failedSections, failureText)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = failure.message.orEmpty()
            _spotifyLibraryError.value = spotifyLibraryFailureMessage(
                sections = listOf("library"),
                failureText = message,
                wholeLibrary = true,
            )
        } finally {
            _spotifyLibraryLoading.value = false
        }
    }

    private fun spotifyLibraryFailureMessage(
        sections: List<String>,
        failureText: String,
        wholeLibrary: Boolean = false,
    ): String {
        val lower = failureText.lowercase()
        val rateLimited = "429" in lower ||
            "rate" in lower ||
            "quota" in lower ||
            spotify.state.value == ProviderState.RATE_LIMITED
        if (rateLimited) {
            return "Spotify is rate-limiting right now. Wait a few minutes, then refresh once. Spamming refresh makes it worse."
        }
        return if (wholeLibrary) {
            "Could not load your Spotify library. Check your connection and Spotify access, then refresh once."
        } else {
            "Could not load Spotify ${sections.joinToString(" and ")}. Successfully loaded sections are still available. Wait a bit, then refresh once."
        }
    }

    /**
     * Resolve Discover Weekly via desktop librespot (algorithmic ids) first, then a
     * user-library playlist named Discover Weekly. Never use Web API search copies.
     */
    private suspend fun resolveDiscoverWeekly(playlists: List<Playlist>): Playlist? {
        val configuredId = parseSpotifyPlaylistId(_settings.value.spotifyDiscoverWeeklyPlaylistId.orEmpty())
        val candidateId = resolveDiscoverWeeklyCandidate(playlists, configuredId)
        val fromLibrespot = runCatching { fetchLibrespotDiscoverWeekly(candidateId) }.getOrNull()
        if (fromLibrespot != null) return fromLibrespot
        return findDiscoverWeekly(playlists)
    }

    /** Load full track list for a Spotify playlist and return playable tracks. */
    suspend fun loadSpotifyPlaylistTracks(playlistId: String): List<Track> {
        val id = playlistId.trim()
        require(id.isNotEmpty()) { "Playlist id is required" }
        val cached = _spotifyDiscoverWeekly.value
        if (cached?.source?.providerEntityId == id && cached.tracks.isNotEmpty()) {
            return cached.tracks
        }
        val webTracks = runCatching { spotify.getPlaylistTracks(id) }.getOrElse { emptyList() }
        if (webTracks.isNotEmpty()) return webTracks
        val fromLibrespot = runCatching { fetchLibrespotDiscoverWeekly(id) }.getOrNull()
        if (fromLibrespot != null && fromLibrespot.tracks.isNotEmpty()) {
            val configured = parseSpotifyPlaylistId(_settings.value.spotifyDiscoverWeeklyPlaylistId.orEmpty())
            if (cached?.source?.providerEntityId == id || configured == id) {
                _spotifyDiscoverWeekly.value = fromLibrespot
            }
            return fromLibrespot.tracks
        }
        return emptyList()
    }

    private var volumePersistJob: Job? = null

    /** Applies volume immediately; the durable setting is written once the wheel/slider settles. */
    fun setPlaybackVolume(volume: Float) {
        val next = volume.coerceIn(0f, 1f)
        player.setVolume(next)
        volumePersistJob?.cancel()
        volumePersistJob = scope.launch {
            delay(VOLUME_PERSIST_DEBOUNCE_MS)
            updateSettings { it.copy(playbackVolume = next) }
        }
    }

    fun onAppForeground() {
        heartedAudio.notifyNetworkChanged()
        foregroundHandoff.onForeground()
        foreground.isVisible = true
        if (_ready.value) homeLanSync.onAppForeground()
    }

    suspend fun onAppBackground() {
        foreground.isVisible = false
        flushListeningSession()
        homeLanSync.scheduleListeningDeliveryIfPending()
    }

    /** Write the listening session now (app exit / backgrounding) instead of waiting for the next checkpoint. */
    suspend fun flushListeningSession() {
        storageStep { library.flush() }
        storageStep { kainosPlaylists.flush() }
        when (val result = player.flushPersistence()) {
            is FlushResult.Committed -> result.exportUnavailable?.let {
                PlaybackTrace.log("Recorder", "flushed; local listening export unavailable: $it")
            }
            is FlushResult.Failed -> PlaybackTrace.log("Recorder", "flush failed: ${result.cause.message}")
        }
    }

    fun refreshLocalLibrary() {
        localLibraryRefreshJob?.cancel()
        localLibraryRefreshJob = scope.launch {
            refreshLocalLibraryAndAwait()
        }
    }

    suspend fun refreshLocalLibraryAndAwait() {
        _localLibraryMessage.value = null
        runCatching { local.refresh() }
            .onSuccess {
                local.lastScanStats?.let { stats ->
                    _localLibraryMessage.value =
                        "Library updated · ${stats.trackCount} tracks · ${stats.probed} read · " +
                        "${stats.reused} unchanged · ${stats.elapsedMs} ms"
                }
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                _localLibraryMessage.value = failure.message ?: "Local library scan failed"
            }
        runCatching { local.enrichContentKeys() }
            .onFailure { if (it is CancellationException) throw it }
        runCatching { local.enrichEmbeddedArtwork() }
            .onFailure { if (it is CancellationException) throw it }
    }

    /**
     * After heart/vault sync: map portable localfile: hearts onto unique same-basename
     * library tracks, and re-heart local: favorites whose path went missing.
     */
    private suspend fun rematchLocalHeartsByFileName(): Int {
        val localTracks = local.libraryTracks.value
        val byName = localTracks.mapNotNull { track ->
            val url = (track.sourceFor(ProviderId.LOCAL)?.handle as? PlaybackHandle.Url)?.url
                ?: return@mapNotNull null
            val name = basenameFromLocalLocation(url) ?: return@mapNotNull null
            name to track
        }.groupBy({ it.first }, { it.second })
        val unique = byName.mapNotNull { (name, tracks) ->
            tracks.singleOrNull()?.let { name to it }
        }.toMap()

        return library.rematchPortableLocalFileHearts(unique, localTracks)
    }

    private suspend fun rematchPlaylistLocalsByFileName(): Int {
        val localTracks = local.libraryTracks.value
        val byName = localTracks.mapNotNull { track ->
            val url = (track.sourceFor(ProviderId.LOCAL)?.handle as? PlaybackHandle.Url)?.url
                ?: return@mapNotNull null
            val name = basenameFromLocalLocation(url) ?: return@mapNotNull null
            name to track
        }.groupBy({ it.first }, { it.second })
        val unique = byName.mapNotNull { (name, tracks) ->
            tracks.singleOrNull()?.let { name to it }
        }.toMap()
        return kainosPlaylists.rematchPortableLocalFileEntries(unique, localTracks)
    }

    fun playKainosPlaylist(playlistId: String): Boolean {
        val tracks = kainosPlaylists.tracksForPlayback(playlistId)
        if (tracks.isEmpty()) return false
        playTracks(tracks, startIndex = 0)
        return true
    }

    /** App-hearted tracks from remembered + local + Spotify liked, ordered for Play favorites. */
    fun resolveFavoriteTracks(): List<Track> {
        val favoriteIds = library.favoriteIds.value
        if (favoriteIds.isEmpty()) return emptyList()
        val catalog = (
            local.libraryTracks.value +
                library.savedTracks.value +
                spotifyTracks.value
            ).distinctBy { it.canonicalId }
        val byId = catalog.associateBy { it.canonicalId }
        return favoriteIds.mapNotNull { byId[it] }
    }

    /** [shuffle] turns shuffle on before the queue builds, so the fresh order starts with the first heart. */
    fun playFavorites(shuffle: Boolean = false): Boolean {
        val tracks = resolveFavoriteTracks()
        if (tracks.isEmpty()) return false
        if (shuffle && !player.queueState.value.shuffle) player.toggleShuffle()
        playTracks(tracks, startIndex = 0)
        return true
    }

    fun playAlbumById(albumCanonicalId: String): Boolean {
        val tracks = local.libraryTracks.value.filter { it.album?.canonicalId == albumCanonicalId }
        if (tracks.isEmpty()) return false
        playTracks(tracks, startIndex = 0)
        return true
    }

    fun playLocalFolder(folder: String): Boolean {
        val tracks = tracksInLocalFolder(folder)
        if (tracks.isEmpty()) return false
        playTracks(tracks, startIndex = 0)
        return true
    }

    fun tracksInLocalFolder(folder: String): List<Track> {
        if (folder.isBlank()) return emptyList()
        return local.libraryTracks.value.filter { track ->
            val location = (track.sourceFor(ProviderId.LOCAL)?.handle as? PlaybackHandle.Url)?.url
                ?: return@filter false
            isLocationInFolder(location, folder)
        }
    }

    fun hasRestorableSession(): Boolean =
        player.nowPlaying.value.track != null ||
            player.queueState.value.items.isNotEmpty()

    fun resumeListening() {
        if (player.nowPlaying.value.track == null && player.queueState.value.items.isEmpty()) return
        if (player.nowPlaying.value.isPlaying) return
        player.playTransport()
    }

    fun setHomeLanVaultFolder(folder: String?) {
        scope.launch {
            updateSettings { it.copy(homeLanSyncVaultFolder = folder) }
        }
    }

    fun setHomeLanHubAutostartEnabled(enabled: Boolean) {
        scope.launch {
            homeLanSync.setHubAutostartEnabled(enabled)
        }
    }

    fun pickHomeLanVaultFolder() {
        if (!supportsMusicFolderPicker()) return
        scope.launch {
            val picked = pickMusicFolder() ?: return@launch
            updateSettings { current ->
                val base = if (current.localMusicFoldersConfigured) {
                    current.localMusicFolders
                } else {
                    listOfNotNull(defaultLocalMusicFolder().takeIf { it.isNotBlank() })
                }
                val nextFolders = (base + picked).distinct()
                current.copy(
                    localMusicFoldersConfigured = true,
                    localMusicFolders = nextFolders,
                    homeLanSyncVaultFolder = picked,
                    includeMediaStoreLibrary = if (
                        !current.localMusicFoldersConfigured || current.localMusicFolders.isEmpty()
                    ) {
                        false
                    } else {
                        current.includeMediaStoreLibrary
                    },
                )
            }
            refreshLocalLibrary()
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
                val nextFolders = (base + picked).distinct()
                val firstExplicitRoot = !current.localMusicFoldersConfigured ||
                    current.localMusicFolders.isEmpty()
                current.copy(
                    localMusicFoldersConfigured = true,
                    localMusicFolders = nextFolders,
                    // Custom SAF folders should not be drowned by the full device MediaStore index.
                    includeMediaStoreLibrary = if (firstExplicitRoot) false else current.includeMediaStoreLibrary,
                )
            }
            refreshLocalLibrary()
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
            refreshLocalLibrary()
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
        ProviderId.SAMPLE -> sampleUnavailable
    }
}

private const val VOLUME_PERSIST_DEBOUNCE_MS = 400L

internal class PeerNotifyingExchangeStore(
    private val inner: ListeningExchangeStore,
    private val onPeerChanged: () -> Unit,
) : ListeningExchangeStore by inner {
    override suspend fun receivePeer(peer: PeerListening): PeerReceipt =
        inner.receivePeer(peer).also { if (it.stored) onPeerChanged() }

    override suspend fun attachPeerQueue(deviceId: String, queue: PortableListeningQueue): Boolean =
        inner.attachPeerQueue(deviceId, queue).also { if (it) onPeerChanged() }
}

private class PublicationNotifyingStore(
    private val inner: ListeningStore,
    private val onPublished: () -> Unit,
) : ListeningStore by inner {
    override suspend fun commit(commit: ListeningCommit) {
        inner.commit(commit)
        if (commit.local != null) onPublished()
    }
}

/**
 * Thread that owns [PlayerSession] state: the platform UI thread when there is one (Android main
 * looper, Swing EDT), so UI calls stay synchronous; otherwise a single serialized worker.
 */
private fun playbackDispatcher(): CoroutineDispatcher =
    runCatching { Dispatchers.Main.immediate.also { it.isDispatchNeeded(EmptyCoroutineContext) } }
        .getOrElse { Dispatchers.Default.limitedParallelism(1) }

lateinit var appContainer: AppContainer

fun ensureAppContainer(): AppContainer {
    if (!::appContainer.isInitialized) {
        appContainer = AppContainer()
    }
    return appContainer
}
