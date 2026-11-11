package com.universalmusic.player.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.AlbumRef
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.UnifiedSearchResult
import com.universalmusic.player.ui.screens.CollectionCatalog
import com.universalmusic.player.ui.screens.CollectionStack
import com.universalmusic.player.ui.screens.LibraryPresenter
import com.universalmusic.player.ui.screens.LibraryScreen
import com.universalmusic.player.ui.screens.PagesOverTab
import com.universalmusic.player.ui.screens.SearchPresenter
import com.universalmusic.player.ui.screens.SearchScreen
import com.universalmusic.player.ui.screens.editor
import com.universalmusic.player.ui.screens.testCollectionCatalog
import com.universalmusic.player.ui.screens.testCollectionStack
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Album, artist and playlist pages opened from Library and Search, driven through the real screens. */
@OptIn(ExperimentalTestApi::class)
class CollectionPagesUiTest {
    private val played = mutableListOf<String>()
    private lateinit var stack: CollectionStack
    private val available = TrackAvailabilityInfo(TrackAvailability.AVAILABLE_LOCALLY, "Local", PlaybackSourceKind.LOCAL_FILE, "Local")

    private fun localSong(
        id: String,
        title: String,
        artist: String,
        album: AlbumRef? = null,
        disc: Int? = null,
        number: Int? = null,
        durationMs: Long = 210_000,
    ) = Track(
        canonicalId = "local:$id",
        title = title,
        artists = listOf(ArtistRef("local-artist:${artist.lowercase()}", artist)),
        album = album,
        durationMs = durationMs,
        discNumber = disc,
        trackNumber = number,
        sources = listOf(PlaybackSource(ProviderId.LOCAL, "file-$id", isPlayable = true, handle = PlaybackHandle.Url("file:///music/$id.flac"))),
    )

    private fun library(tracks: List<Track>, scope: CoroutineScope, playlists: KainosPlaylistRepository? = null): LibraryPresenter {
        val settings = MutableStateFlow(AppSettings())
        return LibraryPresenter(
            settings = settings,
            updateSettings = { settings.value = it(settings.value) },
            savedTracks = MutableStateFlow(emptyList()),
            favorites = MutableStateFlow(emptySet()),
            localTracks = MutableStateFlow(tracks),
            localState = MutableStateFlow(ProviderState.AVAILABLE),
            spotifyTracks = MutableStateFlow(emptyList()),
            spotifyPlaylists = MutableStateFlow(emptyList()),
            kainosPlaylistRows = playlists?.playlists ?: MutableStateFlow(emptyList()),
            homePins = MutableStateFlow(emptyList()),
            spotifyLibraryLoading = MutableStateFlow(false),
            spotifyLibraryError = MutableStateFlow(null),
            spotifyState = MutableStateFlow(ProviderState.UNAVAILABLE),
            downloads = MutableStateFlow(emptyMap()),
            onTrackAvailability = { available },
            scope = scope,
        )
    }

    /** Library with its pages laid over it exactly as the app does, through [PagesOverTab]. */
    private fun ComposeUiTest.showLibrary(
        tracks: List<Track>,
        playlists: KainosPlaylistRepository? = null,
        catalog: (CoroutineScope) -> CollectionCatalog = {
            testCollectionCatalog(
                Dispatchers.Unconfined,
                localTracks = MutableStateFlow(tracks),
                playlists = playlists?.playlists ?: MutableStateFlow(emptyList()),
                playlistEditor = { id -> playlists?.editor(id) { emptyList() } },
            )
        },
    ) {
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { library(tracks, scope, playlists) }
                val pages = remember { testCollectionStack(scope, catalog(scope)).also { stack = it } }
                val actions = remember { RecordedTrackActions(openCollection = pages::push) }
                PagesOverTab(
                    stack = pages,
                    holder = rememberSaveableStateHolder(),
                    tabKey = "Library",
                    rootBackLabel = "Back to Library",
                    trackActions = actions.presenter,
                    availability = { available },
                    downloads = emptyMap(),
                    onPlay = { songs, index, shuffle -> played += "${songs.joinToString(",") { it.title }}|$index|$shuffle" },
                    openLink = {},
                ) {
                    LibraryScreen(
                        MutableStateFlow(""), { "" }, presenter, { _, _ -> }, actions.presenter,
                        onOpenCollection = pages::push,
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.text(value: String) = onNodeWithText(value, useUnmergedTree = true)
    private fun ComposeUiTest.topOf(value: String) = text(value).getBoundsInRoot().top.value
    private fun ComposeUiTest.list() = onNode(hasScrollToIndexAction())

    /** System back on Android and Escape on desktop both reach the open tab's stack first. */
    private fun ComposeUiTest.systemBack() {
        runOnIdle { check(stack.back()) }
        waitForIdle()
    }

    private fun ComposeUiTest.savePng(name: String) {
        val directory = Path.of("build", "collection-pages-proof")
        Files.createDirectories(directory)
        val roots = onAllNodes(isRoot())
        val image = roots[0].captureToImage().toAwtImage()
        val canvas = image.createGraphics()
        for (index in 1 until roots.fetchSemanticsNodes().size) {
            val bounds = roots[index].fetchSemanticsNode().boundsInWindow
            canvas.drawImage(roots[index].captureToImage().toAwtImage(), bounds.left.toInt(), bounds.top.toInt(), null)
        }
        canvas.dispose()
        ImageIO.write(image, "png", directory.resolve(name).toFile())
    }

    @Test fun anArtistOpensItsOwnPageAndBackReturnsToTheListWhereItWas() = runDesktopComposeUiTest(width = 560, height = 900) {
        val tracks = List(60) { artist ->
            val name = "Artist ${artist.toString().padStart(2, '0')}"
            val album = AlbumRef("local-album:$artist", "Album of $name", year = 2000 + artist)
            List(3) { song -> localSong("$artist-$song", "$name song ${song + 1}", name, album, number = song + 1) }
        }.flatten()
        showLibrary(tracks)
        text("Artists").performClick()
        waitForIdle()
        list().performScrollToIndex(45)
        waitForIdle()
        text("Artist 00").assertDoesNotExist()
        text("Artist 45").performClick()
        waitForIdle()

        // The page has its own scroll: it opens at its header, not at the list's row 45.
        text("Artist in your library").assertIsDisplayed()
        text("Artist 45 song 1").assertIsDisplayed()
        text("Album of Artist 45").assertIsDisplayed()
        text("3 songs, 1 album").assertIsDisplayed()
        savePng("artist-page.png")

        systemBack()
        text("Artist 45").assertIsDisplayed()
        text("Artist 00").assertDoesNotExist()

        // The arrow does the same as system back.
        text("Artist 46").performClick()
        waitForIdle()
        onNodeWithContentDescription("Back to Library").performClick()
        waitForIdle()
        text("Artist 46").assertIsDisplayed()
        text("Artist 00").assertDoesNotExist()
    }

    @Test fun anAlbumPageListsDiscsInTrackOrderAndPlayQueuesThemInThatOrder() = runDesktopComposeUiTest(width = 560, height = 900) {
        val album = AlbumRef("local-album:harbour", "Harbour Lights", year = 2019, artists = listOf(ArtistRef("local-artist:mara vell", "Mara Vell")))
        val tracks = listOf(
            localSong("d2t1", "Second Side Opener", "Mara Vell", album, disc = 2, number = 1),
            localSong("d1t2", "Breakwater", "Mara Vell", album, disc = 1, number = 2),
            localSong("d1t1", "Lantern Row", "Mara Vell", album, disc = 1, number = 1),
            localSong("d2t2", "Last Ferry", "Guest Singer", album, disc = 2, number = 2),
        )
        showLibrary(tracks)
        text("Albums").performClick()
        waitForIdle()
        text("Harbour Lights").performClick()
        waitForIdle()

        text("Local album").assertIsDisplayed()
        // The header's album artist comes first; the rows repeat the artist below it.
        val albumArtist = onAllNodesWithText("Mara Vell", useUnmergedTree = true)[0]
        albumArtist.assertIsDisplayed()
        text("2019, 4 songs, 14 min").assertIsDisplayed()
        text("Disc 1").assertIsDisplayed()
        text("Disc 2").assertIsDisplayed()
        val order = listOf("Lantern Row", "Breakwater", "Second Side Opener", "Last Ferry")
        assertEquals(order, order.sortedBy { topOf(it) }, "Rows follow disc then track number")
        assertTrue(topOf("Disc 2") in topOf("Breakwater")..topOf("Second Side Opener"))
        savePng("album-page.png")

        text("Play").performClick()
        waitForIdle()
        assertEquals(listOf("${order.joinToString(",")}|0|false"), played)
        text("Last Ferry").performClick()
        waitForIdle()
        assertEquals("${order.joinToString(",")}|3|false", played.last())

        // The album artist opens the artist's page; back returns to the album.
        albumArtist.performClick()
        waitForIdle()
        text("Artist in your library").assertIsDisplayed()
        onNodeWithContentDescription("Back to Harbour Lights").performClick()
        waitForIdle()
        text("Local album").assertIsDisplayed()
    }

    @Test fun goToAlbumFromATrackMenuOpensTheAlbumAndLocalSongsOfferNoRadio() = runDesktopComposeUiTest(width = 560, height = 900) {
        val album = AlbumRef("local-album:night", "Night Ferry", year = 2021)
        val tracks = listOf(
            localSong("a", "Moonlit Quay", "Neon Coast", album, number = 1),
            localSong("b", "Signal Fires", "Neon Coast", album, number = 2),
        )
        showLibrary(tracks)
        onNodeWithContentDescription("More actions for Signal Fires").performClick()
        waitForIdle()
        text("Go to album").assertIsDisplayed()
        text("Go to artist").assertIsDisplayed()
        // Radio stays inside one source, and local files have none.
        text("Start radio").assertDoesNotExist()
        savePng("track-menu-go-to.png")
        text("Go to album").performClick()
        waitForIdle()

        text("Local album").assertIsDisplayed()
        text("Night Ferry").assertIsDisplayed()
        assertTrue(topOf("Moonlit Quay") < topOf("Signal Fires"))
        // On its own album page the menu does not offer the page already open.
        onNodeWithContentDescription("More actions for Signal Fires").performClick()
        waitForIdle()
        text("Go to artist").assertIsDisplayed()
        text("Go to album").assertDoesNotExist()
    }

    @Test fun aKainosPlaylistRemovesWithUndoAndReorders() = runDesktopComposeUiTest(width = 560, height = 900) {
        val songs = listOf(
            localSong("1", "Song A", "Tide"),
            localSong("2", "Song B", "Tide"),
            localSong("3", "Song C", "Tide"),
        )
        val repository = KainosPlaylistRepository()
        val playlist = repository.create("Road Trip", songs)
        fun order() = repository.get(playlist.id)!!.entries.map { it.track.title }
        showLibrary(songs, repository)
        text("Playlists").performClick()
        waitForIdle()
        text("Road Trip").performClick()
        waitForIdle()
        text("Kainos playlist").assertIsDisplayed()
        text("3 songs, 11 min").assertIsDisplayed()

        onNodeWithContentDescription("Remove Song B from playlist").performClick()
        waitForIdle()
        assertEquals(listOf("Song A", "Song C"), order())
        text("Song B").assertDoesNotExist()
        text("Removed Song B").assertIsDisplayed()
        savePng("playlist-undo.png")
        text("Undo").performClick()
        waitForIdle()
        assertEquals(listOf("Song A", "Song B", "Song C"), order(), "Undo puts the song back where it was")
        text("Removed Song B").assertDoesNotExist()

        // Reorder from the menu, then by dragging the handle a row down.
        onNodeWithContentDescription("More actions for Song C").performClick()
        waitForIdle()
        text("Move up").performClick()
        waitForIdle()
        assertEquals(listOf("Song A", "Song C", "Song B"), order())

        // The unmerged node is the handle itself; the merged one is the whole row.
        onNodeWithContentDescription("Drag to reorder Song A", useUnmergedTree = true).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 80.dp.toPx(), durationMillis = 600)
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(listOf("Song C", "Song A", "Song B"), order())
        assertEquals(listOf("Song C", "Song A", "Song B"), listOf("Song A", "Song B", "Song C").sortedBy { topOf(it) })
    }

    @Test fun searchListsSpotifyAlbumsAndArtistsAndAnAlbumOpensInOrder() = runDesktopComposeUiTest(width = 560, height = 900) {
        val spotifyAlbum = Album(
            canonicalId = "spotify-album:tide",
            title = "Tidewater",
            artists = listOf(ArtistRef("spotify-artist:neon", "Neon Coast")),
            year = 2020,
            sources = listOf(ProviderEntityRef(ProviderId.SPOTIFY, "tide")),
        )
        val artist = com.universalmusic.player.domain.model.Artist("spotify-artist:neon", "Neon Coast")
        val results = List(4) { spotifySong("Result ${it + 1}", it + 1, null) }
        fun albumSong(title: String, number: Int) = spotifySong(title, number, AlbumRef("spotify-album:tide", "Tidewater"))
        val requestedAlbums = mutableListOf<String>()
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember {
                    SearchPresenter(
                        settings = MutableStateFlow(AppSettings()),
                        spotify = MutableStateFlow(ProviderState.AVAILABLE),
                        youtube = MutableStateFlow(ProviderState.NOT_CONFIGURED),
                        downloads = MutableStateFlow(emptyMap()),
                        search = { UnifiedSearchResult(results, listOf(spotifyAlbum), listOf(artist), emptyList(), emptyMap()) },
                        availability = { available },
                        searchDispatcher = Dispatchers.Unconfined,
                        scope = scope,
                    )
                }
                val pages = remember {
                    testCollectionStack(
                        scope,
                        testCollectionCatalog(Dispatchers.Unconfined, spotifyAlbum = { id ->
                            requestedAlbums += id
                            spotifyAlbum.copy(tracks = listOf(albumSong("Undertow", 2), albumSong("Shoreline", 1)))
                        }),
                    )
                }
                SearchScreen(presenter, { _, _ -> }, { songs, index, shuffle -> played += "${songs.joinToString(",") { it.title }}|$index|$shuffle" }, RecordedTrackActions().presenter, pages)
            }
        }
        onNode(hasSetTextAction()).performTextInput("tide")
        mainClock.advanceTimeUntil(timeoutMillis = 20_000) { onAllNodes(hasText("Result 1")).fetchSemanticsNodes().isNotEmpty() }
        list().performScrollToNode(hasText("Tidewater"))
        text("Artists").assertIsDisplayed()
        text("Albums").assertIsDisplayed()
        text("Album by Neon Coast, 2020").assertIsDisplayed()
        savePng("search-albums-artists.png")

        text("Tidewater").performClick()
        mainClock.advanceTimeUntil(timeoutMillis = 20_000) { onAllNodes(hasText("Shoreline")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("tide"), requestedAlbums)
        text("Spotify album").assertIsDisplayed()
        assertTrue(topOf("Shoreline") < topOf("Undertow"), "Album songs are in track order")
        text("Play").performClick()
        waitForIdle()
        assertEquals(listOf("Shoreline,Undertow|0|false"), played)
        onNodeWithContentDescription("Back to search results").performClick()
        waitForIdle()
        text("Tidewater").assertIsDisplayed()
    }

    private fun spotifySong(title: String, number: Int, album: AlbumRef?) = Track(
        canonicalId = "spotify:$title",
        title = title,
        artists = listOf(ArtistRef("spotify-artist:neon", "Neon Coast")),
        album = album,
        durationMs = 200_000,
        trackNumber = number,
        sources = listOf(PlaybackSource(ProviderId.SPOTIFY, title, isPlayable = true, handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, title))),
    )
}
