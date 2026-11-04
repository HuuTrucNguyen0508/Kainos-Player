package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal fun fileTrack(path: String, title: String = path.substringAfterLast('/').substringBeforeLast('.')): Track {
    val location = "file://" + path.replace(" ", "%20")
    return Track(
        canonicalId = "local:$path",
        title = title,
        artists = emptyList(),
        sources = listOf(
            PlaybackSource(ProviderId.LOCAL, path, isPlayable = true, handle = PlaybackHandle.Url(location)),
        ),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFoldersTest {
    private val library = listOf(
        fileTrack("/m/Rock/10 Ten.flac"),
        fileTrack("/m/Loose.wav"),
        fileTrack("/m/Rock/Live/a.flac"),
        fileTrack("/m/Rock/2 Two.flac"),
        fileTrack("/m/Jazz Club/b.flac"),
        fileTrack("/elsewhere/x.flac"),
    )

    private fun List<Track>.titles() = map { it.title }

    @Test
    fun rootsBecomeTreesOfTheirFilesInFileBrowserOrder() {
        val (music, empty) = libraryFolderTree(listOf("/m", "/m2", "/m"), library)

        assertEquals("m", music.name)
        assertEquals("/m", music.id)
        assertEquals(listOf("Loose"), music.tracks.titles())
        assertEquals(listOf("Jazz Club", "Rock"), music.folders.map { it.name })
        val rock = music.folders[1]
        assertEquals("/m/Rock", rock.id)
        assertEquals(listOf("2 Two", "10 Ten"), rock.tracks.titles())
        assertEquals("/m/Rock/Live", rock.folders.single().id)
        assertEquals(listOf("Loose", "b", "2 Two", "10 Ten", "a"), music.allTracks.titles())
        assertEquals("2 folders, 5 tracks", folderSummary(music))
        assertEquals("1 folder, 3 tracks", folderSummary(rock))
        assertEquals("1 track", folderSummary(rock.folders.single()))

        assertEquals("m2", empty.name)
        assertEquals("No tracks", folderSummary(empty))
    }

    @Test
    fun aTrailRunsFromTheRootToTheOpenFolder() {
        val tree = libraryFolderTree(listOf("/m"), library)
        assertEquals(listOf("m", "Rock", "Live"), tree.trailTo("/m/Rock/Live")!!.map { it.name })
        assertNull(tree.trailTo("/m/Gone"))
        assertNull(tree.trailTo(null))
    }

    @Test
    fun searchShowsFoldersByNameOrByAMatchingSongInside() {
        val music = libraryFolderTree(listOf("/m"), library).single()
        val (byName, _) = music.visibleFor("jazz", emptySet())
        assertEquals(listOf("Jazz Club"), byName.map { it.name })
        val (byTrack, files) = music.visibleFor("a", setOf("local:/m/Rock/Live/a.flac"))
        assertEquals(listOf("Jazz Club", "Rock"), byTrack.map { it.name }) // "Jazz Club" also spells a
        assertEquals(emptyList(), files)
    }

    @Test
    fun naturalOrderComparesNumbersByValue() {
        assertEquals(listOf("1", "2 b", "02 c", "10", "a", "B"), listOf("10", "B", "2 b", "a", "1", "02 c").sortedWith(::naturalCompare))
    }

    @Test
    fun favoritesOnlyKeepsHeartedFilesAndTheConfiguredRoots() = runTest(UnconfinedTestDispatcher()) {
        val settings = MutableStateFlow(
            AppSettings(localMusicFoldersConfigured = true, localMusicFolders = listOf("/m")),
        )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        coroutineContext[Job]?.invokeOnCompletion { scope.cancel() }
        val presenter = LibraryPresenter(
            settings = settings,
            updateSettings = { settings.value = it(settings.value) },
            savedTracks = MutableStateFlow(emptyList()),
            favorites = MutableStateFlow(setOf("local:/m/Rock/2 Two.flac")),
            localTracks = MutableStateFlow(library),
            localState = MutableStateFlow(ProviderState.AVAILABLE),
            spotifyTracks = MutableStateFlow(emptyList()),
            spotifyPlaylists = MutableStateFlow(emptyList()),
            kainosPlaylistRows = MutableStateFlow<List<PersistedKainosPlaylist>>(emptyList()),
            homePins = MutableStateFlow<List<PersistedHomePin>>(emptyList()),
            spotifyLibraryLoading = MutableStateFlow(false),
            spotifyLibraryError = MutableStateFlow<String?>(null),
            spotifyState = MutableStateFlow(ProviderState.UNAVAILABLE),
            downloads = MutableStateFlow<Map<String, DownloadItemState>>(emptyMap()),
            scope = scope,
            defaultMusicFolder = { "/default" },
        )
        advanceUntilIdle()
        assertEquals(listOf("Loose", "b", "2 Two", "10 Ten", "a"), presenter.state.value.folders.single().allTracks.titles())

        presenter.toggleFavoritesOnly()
        advanceUntilIdle()
        val music = presenter.state.value.folders.single()
        assertEquals(listOf("Rock"), music.folders.map { it.name })
        assertEquals(listOf("2 Two"), music.allTracks.titles())

        // Local files only and Ready offline do not narrow folders: every file here is local.
        presenter.toggleFavoritesOnly()
        presenter.toggleLocalOnly()
        presenter.toggleReadyOffline()
        advanceUntilIdle()
        assertEquals(5, presenter.state.value.folders.single().allTracks.size)

        settings.value = settings.value.copy(localMusicFoldersConfigured = false)
        advanceUntilIdle()
        assertEquals(listOf("default"), presenter.state.value.folders.map { it.name })
    }

    @Test
    fun aPinnedSubfolderOfAConfiguredRootResolvesOnHome() {
        fun resolve(target: String, count: Int) = resolveHomePin(
            pin = PersistedHomePin(id = "p", kind = HomePinKind.LOCAL_FOLDER, targetId = target, title = "Live"),
            kainosTitles = emptyMap(),
            kainosTrackCounts = emptyMap(),
            spotifyPlaylists = emptyList(),
            albumTrackCounts = emptyMap(),
            albumTitles = emptyMap(),
            folders = listOf("/m"),
            folderTrackCounts = mapOf(target to count),
            spotifyState = ProviderState.AVAILABLE,
            busyPinId = null,
        )
        val live = resolve("/m/Rock/Live", 1)
        assertEquals(PinStatus.READY, live.status)
        assertEquals("Folder · 1 track", live.detail)
        assertEquals(PinStatus.MISSING, resolve("/elsewhere", 1).status)
    }
}
