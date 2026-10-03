package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.NowPlayingState
import com.universalmusic.player.platform.defaultLocalMusicFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class HomeUiState(
    val recent: List<Track>,
    val homePins: List<PersistedHomePin>,
    val sessionFocus: SessionFocus?,
    val resolvedPins: List<ResolvedHomePin>,
    val loadedDiscover: Playlist?,
    val empty: Boolean,
    val favoriteCount: Int,
    val localState: ProviderState,
    val spotifyState: ProviderState,
    val youtubeState: ProviderState,
    val attentionMessage: String?,
    val discoverBusy: Boolean,
    val discoverError: String?,
    val pinError: String?,
    val actionMessage: String?,
)

private data class HomeActionUi(
    val discoverBusy: Boolean = false,
    val discoverError: String? = null,
    val pinBusyId: String? = null,
    val pinError: String? = null,
    val actionMessage: String? = null,
)

internal class HomePresenter(
    private val recentlyPlayed: StateFlow<List<Track>>,
    private val homePins: StateFlow<List<PersistedHomePin>>,
    private val favoriteIds: StateFlow<Set<String>>,
    private val spotifyState: StateFlow<ProviderState>,
    private val youtubeState: StateFlow<ProviderState>,
    private val localState: StateFlow<ProviderState>,
    private val localTracks: StateFlow<List<Track>>,
    private val discoverWeekly: StateFlow<Playlist?>,
    private val kainosPlaylists: StateFlow<List<PersistedKainosPlaylist>>,
    private val spotifyPlaylists: StateFlow<List<Playlist>>,
    private val nowPlaying: StateFlow<NowPlayingState>,
    private val settings: StateFlow<AppSettings>,
    private val defaultMusicFolder: () -> String,
    private val tracksInLocalFolder: (String) -> List<Track>,
    private val loadSpotifyPlaylistTracks: suspend (String) -> List<Track>,
    private val playKainosPlaylist: (String) -> Boolean,
    private val playAlbumById: (String) -> Boolean,
    private val playLocalFolder: (String) -> Boolean,
    private val playFavoritesTracks: () -> Boolean,
    private val resumeListeningSession: () -> Unit,
    private val moveHomePin: (String, Int) -> Unit,
    private val unpinHome: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    // Home reads track and play state only. Position ticks must not rebuild the landing page.
    private val stableNow = nowPlaying
        .map { it.copy(positionMs = 0) }
        .distinctUntilChanged()

    private val actions = MutableStateFlow(HomeActionUi())

    val state: StateFlow<HomeUiState> = combine(
        combine(
            recentlyPlayed,
            homePins,
            favoriteIds,
            kainosPlaylists,
            spotifyPlaylists,
        ) { recent, pins, favorites, kainos, spotifyLists ->
            LibrarySlice(recent, pins, favorites, kainos, spotifyLists)
        },
        combine(
            spotifyState,
            youtubeState,
            localState,
            localTracks,
            discoverWeekly,
        ) { spotify, youtube, local, tracks, discover ->
            ProviderSlice(spotify, youtube, local, tracks, discover)
        },
        combine(stableNow, settings, actions) { now, appSettings, action ->
            PlaybackSlice(now, appSettings, action)
        },
    ) { library, providers, playback ->
        derive(library, providers, playback)
    }.stateIn(scope, SharingStarted.Eagerly, snapshot())

    fun clearActionMessage() {
        actions.update { it.copy(actionMessage = null) }
    }

    fun movePin(id: String, delta: Int) {
        moveHomePin(id, delta)
    }

    fun unpin(id: String) {
        unpinHome(id)
    }

    fun resumeListening(onOpenNowPlaying: () -> Unit) {
        actions.update { it.copy(actionMessage = null) }
        resumeListeningSession()
        onOpenNowPlaying()
    }

    fun playFavorites(onOpenNowPlaying: () -> Unit) {
        actions.update { it.copy(actionMessage = null) }
        if (playFavoritesTracks()) {
            onOpenNowPlaying()
        } else {
            val message = if (favoriteIds.value.isEmpty()) {
                "Heart tracks in Library to build favorites."
            } else {
                "Favorites need a connection or local file to play."
            }
            actions.update { it.copy(actionMessage = message) }
        }
    }

    fun playDiscover(
        playlist: Playlist,
        onPlayTracks: (List<Track>, Int) -> Unit,
        onOpenNowPlaying: () -> Unit,
    ) {
        scope.launch {
            actions.update { it.copy(discoverBusy = true, discoverError = null) }
            try {
                val tracks = playlist.tracks.takeIf { it.isNotEmpty() }
                    ?: loadSpotifyPlaylistTracks(playlist.source.providerEntityId)
                if (tracks.isEmpty()) {
                    actions.update {
                        it.copy(
                            discoverError =
                                "Discover Weekly has no playable tracks. Paste your share link in Settings → Spotify, then refresh.",
                        )
                    }
                } else {
                    onPlayTracks(tracks, 0)
                    onOpenNowPlaying()
                }
            } catch (failure: Exception) {
                actions.update {
                    it.copy(discoverError = failure.message ?: "Could not load Discover Weekly.")
                }
            } finally {
                actions.update { it.copy(discoverBusy = false) }
            }
        }
    }

    fun playPin(
        resolved: ResolvedHomePin,
        onPlayTracks: (List<Track>, Int) -> Unit,
        onOpenNowPlaying: () -> Unit,
    ) {
        val pin = resolved.pin
        when (resolved.status) {
            PinStatus.DISCONNECTED -> {
                actions.update { it.copy(pinError = "Connect Spotify in Settings to play \"${pin.title}\".") }
                return
            }
            PinStatus.RATE_LIMITED -> {
                actions.update { it.copy(pinError = "Spotify is rate-limited. Try again in a moment.") }
                return
            }
            PinStatus.MISSING -> {
                actions.update { it.copy(pinError = resolved.detail) }
                return
            }
            PinStatus.EMPTY -> {
                actions.update { it.copy(pinError = "\"${pin.title}\" has nothing to play yet.") }
                return
            }
            PinStatus.LOADING, PinStatus.ERROR -> return
            PinStatus.READY -> Unit
        }
        scope.launch {
            actions.update { it.copy(pinBusyId = pin.id, pinError = null) }
            try {
                val played = when (pin.kind) {
                    HomePinKind.KAINOS_PLAYLIST -> playKainosPlaylist(pin.targetId)
                    HomePinKind.ALBUM -> playAlbumById(pin.targetId)
                    HomePinKind.LOCAL_FOLDER -> playLocalFolder(pin.targetId)
                    HomePinKind.PROVIDER_PLAYLIST -> {
                        val entityId = pin.providerEntityId
                            ?: pin.targetId.removePrefix("spotify-playlist:")
                        val tracks = loadSpotifyPlaylistTracks(entityId)
                        if (tracks.isEmpty()) {
                            actions.update { it.copy(pinError = "No playable tracks in \"${pin.title}\".") }
                            false
                        } else {
                            onPlayTracks(tracks, 0)
                            true
                        }
                    }
                }
                if (played) {
                    onOpenNowPlaying()
                } else if (actions.value.pinError == null) {
                    actions.update { it.copy(pinError = "Could not play \"${pin.title}\".") }
                }
            } catch (failure: Exception) {
                actions.update { it.copy(pinError = failure.message ?: "Could not play \"${pin.title}\".") }
            } finally {
                actions.update { it.copy(pinBusyId = null) }
            }
        }
    }

    private fun snapshot(): HomeUiState = derive(
        LibrarySlice(
            recentlyPlayed.value,
            homePins.value,
            favoriteIds.value,
            kainosPlaylists.value,
            spotifyPlaylists.value,
        ),
        ProviderSlice(
            spotifyState.value,
            youtubeState.value,
            localState.value,
            localTracks.value,
            discoverWeekly.value,
        ),
        PlaybackSlice(
            nowPlaying.value.copy(positionMs = 0),
            settings.value,
            actions.value,
        ),
    )

    private fun derive(library: LibrarySlice, providers: ProviderSlice, playback: PlaybackSlice): HomeUiState {
        val now = playback.now
        val action = playback.action
        val folders = configuredMusicFolders(
            playback.settings.localMusicFolders,
            playback.settings.localMusicFoldersConfigured,
            defaultMusicFolder(),
        )
        val counts = folderTrackCounts(folders, tracksInLocalFolder)
        val focus = sessionFocusOf(now.track, now.isPlaying, library.recent)
        val discover = loadedDiscoverPlaylist(providers.discover)
        return HomeUiState(
            recent = library.recent,
            homePins = library.homePins,
            sessionFocus = focus,
            resolvedPins = resolveHomePins(
                homePins = library.homePins,
                kainosPlaylists = library.kainos,
                spotifyPlaylists = library.spotifyPlaylists,
                localTracks = providers.localTracks,
                folders = folders,
                folderTrackCounts = counts,
                spotifyState = providers.spotifyState,
                busyPinId = action.pinBusyId,
            ),
            loadedDiscover = discover,
            empty = homeIsEmpty(
                library.recent,
                providers.localTracks,
                discover,
                library.homePins,
                library.favorites,
                now.track,
            ),
            favoriteCount = library.favorites.size,
            localState = providers.localState,
            spotifyState = providers.spotifyState,
            youtubeState = providers.youtubeState,
            attentionMessage = providerAttentionMessage(
                providers.localState,
                providers.spotifyState,
                providers.youtubeState,
            ),
            discoverBusy = action.discoverBusy,
            discoverError = action.discoverError,
            pinError = action.pinError,
            actionMessage = action.actionMessage,
        )
    }

    private data class LibrarySlice(
        val recent: List<Track>,
        val homePins: List<PersistedHomePin>,
        val favorites: Set<String>,
        val kainos: List<PersistedKainosPlaylist>,
        val spotifyPlaylists: List<Playlist>,
    )

    private data class ProviderSlice(
        val spotifyState: ProviderState,
        val youtubeState: ProviderState,
        val localState: ProviderState,
        val localTracks: List<Track>,
        val discover: Playlist?,
    )

    private data class PlaybackSlice(
        val now: NowPlayingState,
        val settings: AppSettings,
        val action: HomeActionUi,
    )

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope): HomePresenter = HomePresenter(
            recentlyPlayed = container.library.recentlyPlayed,
            homePins = container.library.homePins,
            favoriteIds = container.library.favoriteIds,
            spotifyState = container.spotify.state,
            youtubeState = container.youtube.state,
            localState = container.local.state,
            localTracks = container.local.libraryTracks,
            discoverWeekly = container.spotifyDiscoverWeekly,
            kainosPlaylists = container.kainosPlaylists.playlists,
            spotifyPlaylists = container.spotifyPlaylists,
            nowPlaying = container.player.nowPlaying,
            settings = container.settings,
            defaultMusicFolder = ::defaultLocalMusicFolder,
            tracksInLocalFolder = container::tracksInLocalFolder,
            loadSpotifyPlaylistTracks = container::loadSpotifyPlaylistTracks,
            playKainosPlaylist = container::playKainosPlaylist,
            playAlbumById = container::playAlbumById,
            playLocalFolder = container::playLocalFolder,
            playFavoritesTracks = container::playFavorites,
            resumeListeningSession = container::resumeListening,
            moveHomePin = { id, delta -> container.library.moveHomePin(id, delta) },
            unpinHome = { id -> container.library.unpinHome(id) },
            scope = scope,
        )
    }
}
