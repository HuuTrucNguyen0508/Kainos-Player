package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPresenterTest {
    @Test
    fun stateUpdatesWhenInputsAndQueryChange() = runTest(UnconfinedTestDispatcher()) {
        val settings = MutableStateFlow(AppSettings())
        val local = MutableStateFlow(
            listOf(track("Amy", "Ada", provider = ProviderId.LOCAL, canonicalId = "amy")),
        )
        val spotify = MutableStateFlow(
            listOf(track("Zed", "Ada", provider = ProviderId.SPOTIFY, canonicalId = "zed")),
        )
        val favorites = MutableStateFlow(setOf("amy"))
        val presenter = libraryPresenter(
            settings = settings,
            localTracks = local,
            spotifyTracks = spotify,
            favorites = favorites,
        )
        advanceUntilIdle()
        assertEquals(listOf("amy", "zed"), presenter.state.value.queueSongs.map { it.canonicalId })

        local.value = local.value + track("Bea", "Ada", provider = ProviderId.LOCAL, canonicalId = "bea")
        advanceUntilIdle()
        assertEquals(listOf("amy", "bea", "zed"), presenter.state.value.queueSongs.map { it.canonicalId })

        presenter.setQuery("amy")
        advanceUntilIdle()
        assertEquals(listOf("amy"), presenter.state.value.songs.map { it.canonicalId })
        assertEquals(listOf("amy", "bea", "zed"), presenter.state.value.queueSongs.map { it.canonicalId })

        settings.value = settings.value.copy(libraryFavoritesOnly = true)
        advanceUntilIdle()
        assertEquals(listOf("amy"), presenter.state.value.queueSongs.map { it.canonicalId })
        assertEquals(listOf("amy"), presenter.state.value.songs.map { it.canonicalId })
    }

    @Test
    fun toggleLocalOnlyCallsUpdateSettings() = runTest(UnconfinedTestDispatcher()) {
        val settings = MutableStateFlow(AppSettings())
        var calls = 0
        val presenter = libraryPresenter(
            settings = settings,
            localTracks = MutableStateFlow(
                listOf(track("Amy", "Ada", provider = ProviderId.LOCAL, canonicalId = "amy")),
            ),
            spotifyTracks = MutableStateFlow(
                listOf(track("Zed", "Ada", provider = ProviderId.SPOTIFY, canonicalId = "zed")),
            ),
            updateSettings = { transform ->
                calls += 1
                settings.value = transform(settings.value)
            },
        )
        advanceUntilIdle()
        assertEquals(listOf("amy", "zed"), presenter.state.value.queueSongs.map { it.canonicalId })

        presenter.toggleLocalOnly()
        advanceUntilIdle()

        assertEquals(1, calls)
        assertTrue(settings.value.libraryLocalOnly)
        assertEquals(listOf("amy"), presenter.state.value.queueSongs.map { it.canonicalId })
    }

    private fun TestScope.libraryPresenter(
        settings: MutableStateFlow<AppSettings> = MutableStateFlow(AppSettings()),
        localTracks: MutableStateFlow<List<Track>> = MutableStateFlow(emptyList()),
        savedTracks: MutableStateFlow<List<Track>> = MutableStateFlow(emptyList()),
        spotifyTracks: MutableStateFlow<List<Track>> = MutableStateFlow(emptyList()),
        favorites: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit = { transform ->
            settings.value = transform(settings.value)
        },
    ): LibraryPresenter {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        coroutineContext[Job]?.invokeOnCompletion { scope.cancel() }
        return LibraryPresenter(
            settings = settings,
            updateSettings = updateSettings,
            savedTracks = savedTracks,
            favorites = favorites,
            localTracks = localTracks,
            localState = MutableStateFlow(ProviderState.AVAILABLE),
            spotifyTracks = spotifyTracks,
            spotifyPlaylists = MutableStateFlow<List<Playlist>>(emptyList()),
            kainosPlaylistRows = MutableStateFlow<List<PersistedKainosPlaylist>>(emptyList()),
            homePins = MutableStateFlow<List<PersistedHomePin>>(emptyList()),
            spotifyLibraryLoading = MutableStateFlow(false),
            spotifyLibraryError = MutableStateFlow<String?>(null),
            spotifyState = MutableStateFlow(ProviderState.UNAVAILABLE),
            downloads = MutableStateFlow<Map<String, DownloadItemState>>(emptyMap()),
            scope = scope,
        )
    }
}
