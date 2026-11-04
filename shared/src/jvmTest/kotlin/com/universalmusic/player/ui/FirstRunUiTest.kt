package com.universalmusic.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.universalmusic.player.app.SourceTools
import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.failure.AppFailures
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.UnifiedSearchResult
import com.universalmusic.player.domain.playback.NowPlayingState
import com.universalmusic.player.domain.provider.AuthSession
import com.universalmusic.player.platform.SpotifyReceiverSetup
import com.universalmusic.player.platform.SpotifyWebPlaybackState
import com.universalmusic.player.ui.screens.HomeContent
import com.universalmusic.player.ui.screens.HomePresenter
import com.universalmusic.player.ui.screens.SearchPresenter
import com.universalmusic.player.ui.screens.SearchScreen
import com.universalmusic.player.ui.screens.SettingsConnectPresenter
import com.universalmusic.player.ui.screens.SettingsFoldersPresenter
import com.universalmusic.player.ui.screens.SettingsFrame
import com.universalmusic.player.ui.screens.SettingsSection
import com.universalmusic.player.ui.screens.SettingsSourcesSection
import com.universalmusic.player.ui.screens.SettingsTarget
import com.universalmusic.player.ui.screens.SourcesPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The real device state a screen derives from; every test changes only what its behaviour turns on. */
private class FakeDevice(
    spotify: ProviderState = ProviderState.NOT_CONFIGURED,
    youtube: ProviderState = ProviderState.NOT_CONFIGURED,
    tools: SourceTools = SourceTools(),
    tracks: List<Track> = emptyList(),
    recent: List<Track> = emptyList(),
    ready: Boolean = true,
    settings: AppSettings = AppSettings(),
) {
    val spotify = MutableStateFlow(spotify)
    val youtube = MutableStateFlow(youtube)
    val tools = MutableStateFlow(tools)
    val tracks = MutableStateFlow(tracks)
    val recent = MutableStateFlow(recent)
    val ready = MutableStateFlow(ready)
    val settings = MutableStateFlow(settings)
    val local = MutableStateFlow(ProviderState.AVAILABLE)
    var folderPicks = 0
    var played: List<Track>? = null

    fun sources(scope: CoroutineScope) = SourcesPresenter(
        settings = settings,
        tools = tools,
        local = local,
        tracks = tracks,
        spotify = spotify,
        youtube = youtube,
        desktop = true,
        folderPicker = true,
        defaultMusicFolder = { "" },
        recheckTools = {},
        scope = scope,
    )

    fun home(scope: CoroutineScope) = HomePresenter(
        recentlyPlayed = recent,
        homePins = MutableStateFlow(emptyList<PersistedHomePin>()),
        favoriteIds = MutableStateFlow(emptySet()),
        savedTracks = MutableStateFlow(emptyList()),
        spotifyTracks = MutableStateFlow(emptyList()),
        spotifyState = spotify,
        youtubeState = youtube,
        localState = local,
        localTracks = tracks,
        discoverWeekly = MutableStateFlow<Playlist?>(null),
        kainosPlaylists = MutableStateFlow(emptyList()),
        spotifyPlaylists = MutableStateFlow(emptyList()),
        nowPlaying = MutableStateFlow(NowPlayingState()),
        settings = settings,
        ready = ready,
        sources = sources(scope).state,
        pickFolder = { folderPicks++ },
        defaultMusicFolder = { "" },
        tracksInLocalFolder = { emptyList() },
        loadSpotifyPlaylistTracks = { emptyList() },
        playKainosPlaylist = { false },
        playAlbumById = { false },
        playLocalFolder = { false },
        playFavoritesTracks = { false },
        resumeListeningSession = {},
        moveHomePin = { _, _ -> },
        unpinHome = {},
        pinSuggestionHome = {},
        scope = scope,
    )

    fun folders(scope: CoroutineScope) = SettingsFoldersPresenter(
        settings, local, tracks, MutableStateFlow<String?>(null), MutableStateFlow(emptyList()),
        { emptyList() }, {}, { folderPicks++ }, {}, {}, { _, _ -> }, scope,
    )

    fun connect(scope: CoroutineScope) = SettingsConnectPresenter(
        settings = settings,
        ready = MutableStateFlow(true),
        spotify = spotify,
        youtube = youtube,
        playback = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.Stopped),
        libraryLoading = MutableStateFlow(false),
        libraryError = MutableStateFlow(null),
        config = {
            AppConfig(
                spotifyClientId = settings.value.spotifyClientId,
                spotifyRedirectUri = "http://127.0.0.1:43821/callback",
                youtubeDataApiKey = settings.value.youtubeDataApiKey,
            )
        },
        nativeSpotify = true,
        updateSettings = { transform -> settings.value = transform(settings.value) },
        beginLogin = { error("not signing in here") as AuthSession },
        completeLogin = {},
        disconnectAccount = {},
        prepareReceiver = { true },
        refresh = {},
        scope = scope,
    )

    fun search(scope: CoroutineScope) = SearchPresenter(
        settings = settings,
        spotify = spotify,
        youtube = youtube,
        downloads = MutableStateFlow(emptyMap<String, DownloadItemState>()),
        search = { UnifiedSearchResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()) },
        availability = { error("no tracks in these tests") as TrackAvailabilityInfo },
        scope = scope,
    )
}

/** Home and Settings with the app's own navigation reduced to which of them is showing. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.showApp(device: FakeDevice) {
    setContent {
        TestTheme {
            val scope = rememberCoroutineScope()
            val home = remember { device.home(scope) }
            val folders = remember { device.folders(scope) }
            val connect = remember { device.connect(scope) }
            val sources = remember { device.sources(scope) }
            var target by remember { mutableStateOf<SettingsTarget?>(null) }
            if (target == null) {
                HomeContent(
                    presenter = home,
                    onPlayTracks = { tracks, _ -> device.played = tracks },
                    onOpenNowPlaying = {},
                    onOpenSettings = { target = it },
                    onOpenSearch = {},
                    onOpenLibrary = {},
                )
            } else {
                SettingsFrame(target) { section ->
                    if (section == SettingsSection.Sources) SettingsSourcesSection(folders, connect, sources)
                }
            }
        }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.node(text: String): SemanticsNodeInteraction = onNodeWithText(text, useUnmergedTree = true)

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.assertAbsent(text: String) = onNodeWithText(text, substring = true, useUnmergedTree = true).assertDoesNotExist()

@OptIn(ExperimentalTestApi::class)
class FirstRunUiTest {
    @AfterTest fun clearFailures() = AppFailures.dismiss()

    @Test fun populatedHomeRoutesSpotifyToPlaybackSetup() = runComposeUiTest {
        val device = FakeDevice(
            tracks = localTracks(3),
            spotify = ProviderState.AVAILABLE,
            tools = SourceTools(spotifyReceiver = SpotifyReceiverSetup.NOT_INSTALLED),
        )
        showApp(device)
        node("Needs librespot · Install librespot").assertIsDisplayed()
        device.tools.value = SourceTools(spotifyReceiver = SpotifyReceiverSetup.NEEDS_SIGN_IN)
        waitForIdle()
        node("One step left · Set up playback").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag("settings-spot-SpotifyPlayback").assertIsDisplayed()
    }

    @Test fun aFreshInstallStatesWhatEachSourceStillNeeds() = runComposeUiTest {
        showApp(FakeDevice())
        node("Where does your music live?").assertIsDisplayed()
        node("Needs a folder").assertIsDisplayed()
        node("Needs a Client ID").assertIsDisplayed()
        node("Needs a key").assertIsDisplayed()
        node("Search needs your own free YouTube Data API key.").assertIsDisplayed()
        assertAbsent("Search for anything")
    }

    @Test fun eachProviderStateIsShownAsItIs() = runComposeUiTest {
        val device = FakeDevice(
            spotify = ProviderState.AUTH_REQUIRED,
            youtube = ProviderState.AVAILABLE,
            tools = SourceTools(youtubeExtractor = false),
        )
        showApp(device)
        node("Needs sign-in").assertIsDisplayed()
        node("Client ID saved. Sign in to load your library. Playback needs Premium.").assertIsDisplayed()
        node("Needs yt-dlp").assertIsDisplayed()
        node("Search works. Playing audio needs yt-dlp, which is not installed.").assertIsDisplayed()

        device.spotify.value = ProviderState.RATE_LIMITED
        waitForIdle()
        node("Limited").assertIsDisplayed()

        device.spotify.value = ProviderState.AVAILABLE
        device.tools.value = SourceTools(youtubeExtractor = false, spotifyReceiver = SpotifyReceiverSetup.NOT_INSTALLED)
        waitForIdle()
        node("Needs librespot").assertIsDisplayed()

        device.tools.value = SourceTools(youtubeExtractor = false, spotifyReceiver = SpotifyReceiverSetup.NEEDS_SIGN_IN)
        waitForIdle()
        node("One step left").assertIsDisplayed()
    }

    @Test fun choosingAFolderGoesStraightToThePicker() = runComposeUiTest {
        val device = FakeDevice()
        showApp(device)
        node("Choose a music folder").assertIsEnabled()
        val layouts = mutableListOf<TextLayoutResult>()
        node("Choose a music folder").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val scheme = com.universalmusic.player.ui.theme.folioPalette(com.universalmusic.player.data.settings.FolioTint.FOLIO, false).scheme
        val color = layouts.single().layoutInput.style.color
        assertEquals(scheme.onPrimary, color)
        assertEquals(1f, color.alpha)
        println("Folder CTA: enabled, color=$color, primary=${scheme.primary}, contrast=${com.universalmusic.player.ui.theme.contrastRatio(color, scheme.primary)}")
        node("Choose a music folder").performClick()
        waitForIdle()
        assertEquals(1, device.folderPicks)
        node("Where does your music live?").assertIsDisplayed()
    }

    @Test fun spotifyOpensAtItsDeveloperSteps() = runComposeUiTest {
        showApp(FakeDevice())
        node("Set up Spotify").performClick()
        waitForIdle()
        onNodeWithText("Music sources").assertIsSelected()
        onNodeWithTag("settings-spot-SpotifyClientId").assertIsDisplayed()
        onNodeWithText("Open Spotify dashboard").assertIsDisplayed().assertIsFocused()
        node("http://127.0.0.1:43821/callback").assertIsDisplayed()
    }

    @Test fun signInOpensAtConnect() = runComposeUiTest {
        showApp(FakeDevice(spotify = ProviderState.AUTH_REQUIRED, settings = AppSettings(spotifyClientId = "abc")))
        node("Sign in to Spotify").performClick()
        waitForIdle()
        onNodeWithText("Connect").assertIsDisplayed().assertIsFocused()
    }

    @Test fun youtubeOpensBelowEverythingElseAtItsOwnSteps() = runComposeUiTest {
        showApp(FakeDevice())
        node("Set up YouTube").performClick()
        waitForIdle()
        onNodeWithText("Open Google Cloud").assertIsDisplayed().assertIsFocused()
    }

    @Test fun aMissingToolOpensAtItsInstallNote() = runComposeUiTest {
        showApp(FakeDevice(youtube = ProviderState.AVAILABLE, tools = SourceTools(youtubeExtractor = false)))
        node("Install yt-dlp").performClick()
        waitForIdle()
        onNodeWithTag("settings-spot-YouTubePlayback").assertIsDisplayed()
        onNodeWithText("Check again").assertIsDisplayed().assertIsFocused()
    }

    @Test fun aMissingPlayerOpensItsNoteEvenWhenLocalCannotAskForIt() = runComposeUiTest {
        showApp(
            FakeDevice(
                youtube = ProviderState.AVAILABLE,
                tools = SourceTools(audioPlayer = false),
            ).also { it.local.value = ProviderState.UNAVAILABLE },
        )
        node("Install mpv").performClick()
        waitForIdle()
        onNodeWithTag("settings-spot-AudioPlayer").assertIsDisplayed()
    }

    @Test fun aConfiguredSourceStopsAskingForSetup() = runComposeUiTest {
        val device = FakeDevice(youtube = ProviderState.AVAILABLE, settings = AppSettings(youtubeDataApiKey = "key"))
        showApp(device)
        node("Ready when you are").assertIsDisplayed()
        node("Search YouTube").assertIsDisplayed()
        assertAbsent("Set up YouTube")
        assertAbsent("Needs a key")

        node("Set up Spotify").performClick()
        waitForIdle()
        assertAbsent("Open Google Cloud")
        assertAbsent("Google only answers searches")
    }

    @Test fun savingAClientIdMovesSpotifyOnToSignIn() = runComposeUiTest {
        val device = FakeDevice()
        showApp(device)
        node("Set up Spotify").performClick()
        waitForIdle()
        onNode(hasText("Spotify Client ID")).performTextInput("my-client-id")
        onNodeWithText("Save Client ID").performClick()
        waitForIdle()
        assertEquals("my-client-id", device.settings.value.spotifyClientId)
        assertAbsent("Open Spotify dashboard")
        onNodeWithText("Client ID saved.").assertIsDisplayed()
    }

    @Test fun aReturningListenerNeverSeesIt() = runComposeUiTest {
        val library = localTracks(3)
        val device = FakeDevice(tracks = library, recent = library, ready = false)
        showApp(device)
        node("Home").assertIsDisplayed()
        device.ready.value = true
        waitForIdle()
        node("Home").assertIsDisplayed()
        assertAbsent("Where does your music live?")
        assertAbsent("Your music is ready")
    }

    @Test fun nothingShowsWhileSavedDataIsLoading() = runComposeUiTest {
        val device = FakeDevice(ready = false)
        showApp(device)
        assertAbsent("Where does your music live?")
        device.ready.value = true
        waitForIdle()
        node("Where does your music live?").assertIsDisplayed()
    }

    @Test fun aNewLibraryOffersItsFirstSound() = runComposeUiTest {
        val library = localTracks(12)
        val device = FakeDevice(tracks = library)
        showApp(device)
        node("Your music is ready").assertIsDisplayed()
        assertAbsent("Where does your music live?")
        node("Play").performClick()
        waitForIdle()
        assertEquals(library, device.played)
    }

    @Test fun tracksWithNoAudioPlayerAskForMpvInsteadOfOfferingPlay() = runComposeUiTest {
        showApp(FakeDevice(tracks = localTracks(2), tools = SourceTools(audioPlayer = false)))
        node("Needs mpv").assertIsDisplayed()
        assertAbsent("Your music is ready")
        node("Install mpv").performClick()
        waitForIdle()
        onNodeWithTag("settings-spot-AudioPlayer").assertIsDisplayed()
    }

    @Test fun itFitsAPhoneWidth() = runDesktopComposeUiTest(width = 360, height = 800) {
        showApp(FakeDevice())
        node("Where does your music live?").assertIsDisplayed()
        node("Choose a music folder").assertIsDisplayed()
        node("Set up Spotify").assertIsDisplayed()
        node("Set up Spotify").performClick()
        waitForIdle()
        onNodeWithText("Open Spotify dashboard").assertIsDisplayed()
    }

    @Test fun searchPointsAtSetupWhenItCanReachNothing() = runComposeUiTest {
        var opened: SettingsTarget? = null
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { FakeDevice(spotify = ProviderState.AUTH_REQUIRED).search(scope) }
                SearchScreen(presenter, { _, _ -> }, RecordedTrackActions().presenter, onOpenSettings = { opened = it })
            }
        }
        node("Search needs Spotify or YouTube").assertIsDisplayed()
        node("Set up Spotify").performClick()
        waitForIdle()
        assertEquals(SettingsTarget.SpotifySignIn, opened)
    }

    @Test fun searchNamesOnlyWhatItCanReach() = runComposeUiTest {
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { FakeDevice(youtube = ProviderState.AVAILABLE).search(scope) }
                SearchScreen(presenter, { _, _ -> }, RecordedTrackActions().presenter)
            }
        }
        onAllNodesWithText("Search YouTube", useUnmergedTree = true).assertCountEquals(2)
        assertAbsent("Search Spotify")
        assertAbsent("Search needs Spotify")
    }

    @Test fun aFailureCanBeDismissed() = runComposeUiTest {
        setContent { TestTheme { FailureBar(AppFailures.open) } }
        AppFailures.report(IllegalStateException("test failure"))
        waitForIdle()
        onNode(hasText("Something failed", substring = true)).assertIsDisplayed()
        onNodeWithContentDescription("Dismiss").performClick()
        waitForIdle()
        onNode(hasText("Something failed", substring = true)).assertDoesNotExist()
        assertEquals(emptySet(), AppFailures.open.value)
    }
}
