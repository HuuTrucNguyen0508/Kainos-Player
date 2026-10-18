package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.playlist.PersistedPlaylistEntry
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AlbumRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.NowPlayingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeContentTest {
    @Test
    fun sessionCardHidesWhilePlayerPaneIsVisible() {
        val song = track("Song", "Artist", provider = ProviderId.LOCAL)
        val focus = sessionFocusOf(song, isPlaying = true, recent = emptyList())
        assertTrue(homeSessionCardVisible(playerPaneVisible = false, focus))
        assertFalse(homeSessionCardVisible(playerPaneVisible = true, focus))
        assertFalse(homeSessionCardVisible(playerPaneVisible = false, sessionFocus = null))
    }

    @Test
    fun continueListeningKeepsTheSessionTrackWhenThePaneIsOpen() {
        val current = track("Current", "Artist", provider = ProviderId.LOCAL)
        val next = track("Next", "Artist", provider = ProviderId.LOCAL)
        val recent = listOf(current, next)
        val focus = SessionFocus.Restored(current, isPlaying = false)
        assertEquals(listOf(next), continueListeningTracks(recent, focus, playerPaneVisible = false))
        assertEquals(recent, continueListeningTracks(recent, focus, playerPaneVisible = true))
    }

    @Test
    fun sessionFocusPrefersTheRestoredTrack() {
        val recent = track("Recent", "Artist", provider = ProviderId.LOCAL)
        val playing = track("Playing", "Artist", provider = ProviderId.SPOTIFY)
        assertNull(sessionFocusOf(null, isPlaying = false, recent = emptyList()))
        val last = sessionFocusOf(null, isPlaying = false, recent = listOf(recent))
        assertIs<SessionFocus.LastPlayed>(last)
        assertEquals(recent, last.track)
        val restored = sessionFocusOf(playing, isPlaying = true, recent = listOf(recent))
        assertIs<SessionFocus.Restored>(restored)
        assertTrue(restored.isPlaying)
        assertEquals(1, lastPlayedStartIndex(listOf(recent, playing), playing))
        assertEquals(0, lastPlayedStartIndex(listOf(recent), playing))
    }

    @Test
    fun kainosPinKeepsOrderAndLiveTitle() {
        val first = pin("pin-a", HomePinKind.KAINOS_PLAYLIST, "kainos:playlist:a", "Stale")
        val missing = pin("pin-b", HomePinKind.KAINOS_PLAYLIST, "kainos:playlist:gone", "Gone")
        val resolved = resolveHomePins(
            homePins = listOf(first, missing),
            kainosPlaylists = listOf(
                PersistedKainosPlaylist(
                    id = "kainos:playlist:a",
                    title = "Morning",
                    entries = listOf(
                        PersistedPlaylistEntry("e1", PersistedTrack("t1", "One")),
                        PersistedPlaylistEntry("e2", PersistedTrack("t2", "Two")),
                    ),
                ),
            ),
            spotifyPlaylists = emptyList(),
            localTracks = emptyList(),
            folders = emptyList(),
            folderTrackCounts = emptyMap(),
            spotifyState = ProviderState.AVAILABLE,
            busyPinId = null,
        )
        assertEquals(listOf("pin-a", "pin-b"), resolved.map { it.pin.id })
        assertEquals(PinStatus.READY, resolved[0].status)
        assertEquals("Morning", resolved[0].pin.title)
        assertEquals("Kainos · 2 tracks", resolved[0].detail)
        assertEquals(PinStatus.MISSING, resolved[1].status)
        assertEquals("Playlist removed from this device (unpin to clear).", resolved[1].detail)
    }

    @Test
    fun busyPinShowsLoadingWithoutDroppingTheRow() {
        val row = pin("pin-a", HomePinKind.ALBUM, "album-1", "Album")
        val resolved = resolveHomePin(
            pin = row,
            kainosTitles = emptyMap(),
            kainosTrackCounts = emptyMap(),
            spotifyPlaylists = emptyList(),
            albumTrackCounts = mapOf("album-1" to 4),
            albumTitles = mapOf("album-1" to "Album"),
            folders = emptyList(),
            folderTrackCounts = emptyMap(),
            spotifyState = ProviderState.AVAILABLE,
            busyPinId = "pin-a",
        )
        assertEquals(PinStatus.LOADING, resolved.status)
        assertEquals("Loading…", resolved.detail)
    }

    @Test
    fun emptyHomeIgnoresUnavailableProviders() {
        assertTrue(
            homeIsEmpty(
                recent = emptyList(),
                localTracks = emptyList(),
                loadedDiscover = null,
                homePins = emptyList(),
                favorites = emptySet(),
                nowTrack = null,
            ),
        )
        val discover = Playlist(
            canonicalId = "spotify-playlist:dw",
            title = "Discover Weekly",
            trackCount = 30,
            source = ProviderEntityRef(ProviderId.SPOTIFY, "dw"),
        )
        assertEquals(discover, loadedDiscoverPlaylist(discover))
        assertNull(loadedDiscoverPlaylist(discover.copy(trackCount = 0)))
        assertFalse(
            homeIsEmpty(
                recent = emptyList(),
                localTracks = emptyList(),
                loadedDiscover = discover,
                homePins = emptyList(),
                favorites = emptySet(),
                nowTrack = null,
            ),
        )
    }

    @Test
    fun configuredFoldersDoNotReintroduceTheDefaultOnceExplicit() {
        assertEquals(listOf("/music"), configuredMusicFolders(emptyList(), localMusicFoldersConfigured = false, defaultFolder = "/music"))
        assertEquals(emptyList(), configuredMusicFolders(emptyList(), localMusicFoldersConfigured = true, defaultFolder = "/music"))
    }

    @Test
    fun favoritesSummaryCountsBySourceAndUsesStableCovers() {
        val old = track("Old", "Artist", provider = ProviderId.LOCAL).copy(canonicalId = "local:old", artwork = Artwork("old.png"))
        val newer = track("Newer", "Artist", provider = ProviderId.LOCAL).copy(canonicalId = "localfile:newer", artwork = Artwork("new.png"))
        val liked = track("Liked", "Artist", provider = ProviderId.SPOTIFY).copy(canonicalId = "spotify:liked")
        val summary = favoritesSummary(
            favoriteIds = linkedSetOf("local:old", "spotify:liked", "yt:v1", "localfile:newer"),
            catalog = listOf(old, newer, liked),
        )
        assertEquals(4, summary.count)
        assertEquals(2, summary.local)
        assertEquals(1, summary.spotify)
        assertEquals(1, summary.youtube)
        assertEquals(listOf("new.png", "old.png"), summary.covers.map { it.url })
        assertEquals(summary, favoritesSummary(linkedSetOf("localfile:newer", "yt:v1", "spotify:liked", "local:old"), listOf(old, newer, liked)))
        assertEquals(FavoritesSummary.EMPTY, favoritesSummary(emptySet(), listOf(old)))
        assertEquals("4 hearted · 1 local song", homeEyebrow(favorites = 4, localTracks = 1))
        assertEquals("Your listening", homeEyebrow(favorites = 0, localTracks = 0))
    }

    @Test
    fun narrowHomeKeepsEightRecentsAfterExcludingTheSessionTrack() {
        val recent = (0..12).map { index ->
            track("Recent $index", "Artist", provider = ProviderId.LOCAL).copy(canonicalId = "local:$index")
        }
        val shown = continueListeningTracks(
            recent,
            SessionFocus.Restored(recent.first(), isPlaying = false),
            playerPaneVisible = false,
            limit = continueListeningLimit(continueListeningColumns(380)),
        )
        assertEquals(listOf("local:1", "local:2", "local:3", "local:4", "local:5", "local:6", "local:7", "local:8"), shown.map { it.canonicalId })
    }

    @Test
    fun libraryLedgerNamesEachSourceAndItsDoor() {
        val lines = libraryLines(
            localTrackCount = 512,
            folderCount = 1,
            localState = ProviderState.AVAILABLE,
            favorites = FavoritesSummary(95, 94, 0, 1, emptyList()),
            kainosPlaylistCount = 0,
            spotifyState = ProviderState.RATE_LIMITED,
            spotifyPlaylistCount = 0,
            youtubeState = ProviderState.AVAILABLE,
        )
        assertEquals(
            listOf(LibraryMark.LOCAL, LibraryMark.HEARTS, LibraryMark.SPOTIFY, LibraryMark.YOUTUBE),
            lines.map { it.mark },
        )
        assertEquals("512 songs in 1 folder", lines[0].detail)
        assertEquals(LibraryDoor.LIBRARY, lines[0].door)
        assertEquals(LibraryDoor.FAVORITES, lines[1].door)
        assertEquals("Limited · Try again soon", lines[2].detail)
        assertEquals(LibraryDoor.SETTINGS, lines[2].door)
        assertEquals(LibraryDoor.SEARCH, lines[3].door)

        val empty = libraryLines(0, 0, ProviderState.AVAILABLE, FavoritesSummary.EMPTY, 2, ProviderState.NOT_CONFIGURED, 0, ProviderState.UNAVAILABLE)
        assertEquals(LibraryDoor.SETTINGS, empty[0].door)
        assertEquals("2 Kainos playlists", empty[2].detail)
        assertEquals("Offline · Open Settings", empty.last().detail)
    }

    @Test
    fun pinSuggestionsComeFromTheLibraryAndStopOnceThereArePins() {
        val album = track("One", "Artist", provider = ProviderId.LOCAL).copy(
            canonicalId = "local:1",
            album = AlbumRef("album:a", "Alpha", Artwork("a.png")),
        )
        val albumTwo = album.copy(canonicalId = "local:2")
        val single = track("Solo", "Artist", provider = ProviderId.LOCAL).copy(
            canonicalId = "local:3",
            album = AlbumRef("album:b", "Beta"),
        )
        val playlist = PersistedKainosPlaylist(
            id = "kainos:playlist:a",
            title = "Morning",
            entries = listOf(PersistedPlaylistEntry("e1", PersistedTrack("t1", "One", artworkUrl = "p.png"))),
            updatedAtMs = 5,
        )
        val suggestions = pinSuggestions(
            homePins = listOf(pin("pin-b", HomePinKind.ALBUM, "album:b", "Beta")),
            kainosPlaylists = listOf(playlist, PersistedKainosPlaylist(id = "kainos:playlist:empty", title = "Empty")),
            spotifyPlaylists = listOf(
                Playlist(canonicalId = "spotify-playlist:x", title = "Mix", trackCount = 30, source = ProviderEntityRef(ProviderId.SPOTIFY, "x")),
            ),
            spotifyState = ProviderState.RATE_LIMITED,
            localTracks = listOf(album, albumTwo, single),
            folders = listOf("/music/Playlist", "/music/empty"),
            folderTrackCounts = mapOf("/music/Playlist" to 3),
        )
        assertEquals(
            listOf("kainos:playlist:a", "/music/Playlist", "album:a"),
            suggestions.map { it.targetId },
        )
        assertEquals("Kainos · 1 tracks", suggestions[0].subtitle)
        assertEquals("p.png", suggestions[0].artwork?.url)
        assertEquals("Folder · 3 tracks", suggestions[1].subtitle)
        assertEquals("Album · 2 tracks", suggestions[2].subtitle)
        assertEquals("a.png", suggestions[2].artwork?.url)
        val resolved = suggestions[2].asResolvedPin()
        assertEquals(PinStatus.READY, resolved.status)
        assertEquals(HomePinKind.ALBUM, resolved.pin.kind)

        val enough = List(PIN_SUGGESTIONS_UNTIL_PINS) { pin("pin-$it", HomePinKind.LOCAL_FOLDER, "/f$it", "F$it") }
        assertTrue(pinSuggestions(enough, listOf(playlist), emptyList(), ProviderState.AVAILABLE, emptyList(), emptyList(), emptyMap()).isEmpty())
        assertEquals(3, continueListeningColumns(1300))
        assertEquals(2, continueListeningColumns(800))
        assertEquals(1, continueListeningColumns(500))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomePresenterTest {
    @Test
    fun stateFollowsRecentsThenTheRestoredTrack() = runTest(UnconfinedTestDispatcher()) {
        val recent = MutableStateFlow<List<Track>>(emptyList())
        val now = MutableStateFlow(NowPlayingState())
        val presenter = homePresenter(recentlyPlayed = recent, nowPlaying = now)
        assertNull(presenter.state.value.sessionFocus)
        assertTrue(presenter.state.value.empty)

        val song = track("Song", "Artist", provider = ProviderId.LOCAL)
        recent.value = listOf(song)
        val last = presenter.state.value.sessionFocus
        assertIs<SessionFocus.LastPlayed>(last)
        assertEquals(song, last.track)
        assertFalse(presenter.state.value.empty)

        now.value = NowPlayingState(track = song, isPlaying = true, positionMs = 4_000)
        val restored = presenter.state.value.sessionFocus
        assertIs<SessionFocus.Restored>(restored)
        assertTrue(restored.isPlaying)

        val settled = presenter.state.value
        now.value = now.value.copy(positionMs = 9_000)
        assertEquals(settled, presenter.state.value)
    }

    @Test
    fun playFavoritesCallsThePlaybackLambda() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        var opened = false
        var shuffled: Boolean? = null
        val presenter = homePresenter(
            favoriteIds = MutableStateFlow(setOf("local")),
            playFavoritesTracks = { shuffle ->
                calls += 1
                shuffled = shuffle
                true
            },
        )
        presenter.playFavorites(shuffle = true) { opened = true }
        assertEquals(1, calls)
        assertEquals(true, shuffled)
        assertTrue(opened)

        var failed = 0
        val empty = homePresenter(playFavoritesTracks = { failed += 1; false })
        empty.playFavorites {}
        assertEquals(1, failed)
        assertEquals(
            "Heart tracks in Library to build favorites.",
            empty.state.value.actionMessage,
        )
    }

    private fun TestScope.homePresenter(
        recentlyPlayed: StateFlow<List<Track>> = MutableStateFlow(emptyList()),
        homePins: StateFlow<List<PersistedHomePin>> = MutableStateFlow(emptyList()),
        favoriteIds: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
        spotifyState: StateFlow<ProviderState> = MutableStateFlow(ProviderState.AVAILABLE),
        youtubeState: StateFlow<ProviderState> = MutableStateFlow(ProviderState.AVAILABLE),
        localState: StateFlow<ProviderState> = MutableStateFlow(ProviderState.AVAILABLE),
        localTracks: StateFlow<List<Track>> = MutableStateFlow(emptyList()),
        discoverWeekly: StateFlow<Playlist?> = MutableStateFlow(null),
        kainosPlaylists: StateFlow<List<PersistedKainosPlaylist>> = MutableStateFlow(emptyList()),
        spotifyPlaylists: StateFlow<List<Playlist>> = MutableStateFlow(emptyList()),
        nowPlaying: StateFlow<NowPlayingState> = MutableStateFlow(NowPlayingState()),
        settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings()),
        playFavoritesTracks: (Boolean) -> Boolean = { false },
        scope: CoroutineScope = backgroundScope,
    ) = HomePresenter(
        recentlyPlayed = recentlyPlayed,
        homePins = homePins,
        favoriteIds = favoriteIds,
        savedTracks = MutableStateFlow(emptyList()),
        spotifyTracks = MutableStateFlow(emptyList()),
        spotifyState = spotifyState,
        youtubeState = youtubeState,
        localState = localState,
        localTracks = localTracks,
        discoverWeekly = discoverWeekly,
        kainosPlaylists = kainosPlaylists,
        spotifyPlaylists = spotifyPlaylists,
        nowPlaying = nowPlaying,
        settings = settings,
        defaultMusicFolder = { "" },
        tracksInLocalFolder = { emptyList() },
        loadSpotifyPlaylistTracks = { emptyList() },
        playKainosPlaylist = { false },
        playAlbumById = { false },
        playLocalFolder = { false },
        playFavoritesTracks = playFavoritesTracks,
        resumeListeningSession = {},
        moveHomePin = { _, _ -> },
        unpinHome = {},
        pinSuggestionHome = {},
        scope = scope,
    )
}

private fun pin(id: String, kind: HomePinKind, targetId: String, title: String) = PersistedHomePin(
    id = id,
    kind = kind,
    targetId = targetId,
    title = title,
)
