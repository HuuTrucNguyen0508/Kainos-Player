package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.history.PlayCount
import com.universalmusic.player.data.history.PlayHistory
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.matching.DuplicateTracks
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryDuplicatesTest {
    private val file = track("Midnight City", "M83", durationMs = 243_000, provider = ProviderId.LOCAL, canonicalId = "local:midnight")
    private val like = track("Midnight City", "M83", durationMs = 243_400, provider = ProviderId.SPOTIFY, canonicalId = "spotify:midnight")
    private val live = track("Midnight City - Live", "M83", durationMs = 243_000, provider = ProviderId.SPOTIFY, canonicalId = "spotify:midnight-live")
    private val wait = track("Wait", "M83", durationMs = 343_000, provider = ProviderId.SPOTIFY, canonicalId = "spotify:wait")
    private val upload = track("M83 - Midnight City (Official Audio)", "M83VEVO", durationMs = 242_000, provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:midnight")

    private fun state(
        hide: Boolean = true,
        favorites: Set<String> = emptySet(),
        favoritesOnly: Boolean = false,
        localOnly: Boolean = false,
    ) = deriveLibraryUiState(
        settings = AppSettings(libraryHideDuplicates = hide, libraryFavoritesOnly = favoritesOnly, libraryLocalOnly = localOnly),
        saved = listOf(upload),
        favorites = favorites,
        localTracks = listOf(file),
        localState = ProviderState.AVAILABLE,
        spotifyTracks = listOf(like, live, wait),
        spotifyPlaylists = emptyList(),
        kainosRows = emptyList(),
        homePins = emptyList(),
        spotifyLoading = false,
        spotifyError = null,
        spotifyState = ProviderState.AVAILABLE,
        downloads = emptyMap(),
        query = "",
    )

    @Test
    fun playAllPlaysTheLocalCopyOnceAndKeepsOtherVersions() {
        val songs = state().queueSongs.map { it.canonicalId }
        assertEquals(listOf("local:midnight", "spotify:midnight-live", "spotify:wait"), songs)
        assertEquals(songs.distinct(), songs)
    }

    @Test
    fun turningTheSettingOffListsEveryCopy() {
        val songs = state(hide = false).queueSongs.map { it.canonicalId }.toSet()
        assertEquals(setOf("local:midnight", "spotify:midnight", "spotify:midnight-live", "spotify:wait", "yt:midnight"), songs)
    }

    @Test
    fun aHeartOnTheHiddenCopyShowsOnAndFiltersInTheListedOne() {
        val hearted = state(favorites = setOf("spotify:midnight"), favoritesOnly = true)
        assertEquals(listOf("local:midnight"), hearted.queueSongs.map { it.canonicalId }, "Play favorites plays the file once")
        assertTrue("local:midnight" in hearted.favorites)

        val bothHearted = state(favorites = setOf("spotify:midnight", "local:midnight", "yt:midnight"), favoritesOnly = true)
        assertEquals(listOf("local:midnight"), bothHearted.queueSongs.map { it.canonicalId })
    }

    @Test
    fun localOnlyIsUnchanged() {
        assertEquals(listOf("local:midnight"), state(localOnly = true).queueSongs.map { it.canonicalId })
    }

    @Test
    fun homeShelvesFoldPlaysAndHeartsIntoTheListedCopy() {
        val groups = DuplicateTracks.group(listOf(file, like, upload, wait))
        val now = 100L * 24 * 60 * 60 * 1000
        val history = PlayHistory(
            onRepeat = listOf(PlayCount(like, 3, now - 1), PlayCount(wait, 4, now - 1), PlayCount(file, 2, now - 2)),
            lastPlayedAtMs = mapOf("spotify:midnight" to 1L, "local:midnight" to 2L),
        )
        val shelves = homeShelves(history, setOf("spotify:midnight"), listOf(file, like, wait), emptyList(), now, groups)
        val onRepeat = shelves.first { it.kind == ShelfKind.ON_REPEAT }.rows
        assertEquals(listOf("local:midnight", "spotify:wait"), onRepeat.map { it.track.canonicalId })
        assertEquals("5 plays", onRepeat.first().note)
        val rediscover = shelves.first { it.kind == ShelfKind.REDISCOVER }.rows
        assertEquals(listOf("local:midnight"), rediscover.map { it.track.canonicalId })

        val recent = homeRecentWithoutDuplicates(listOf(like, wait, file), listOf(file, like, wait), groups)
        assertEquals(listOf("local:midnight", "spotify:wait"), recent.map { it.canonicalId })
    }

    @Test
    fun removeHeartOnTheListedCopyAlsoRemovesTheHiddenCopysHeart() {
        val favorites = MutableStateFlow(setOf("spotify:midnight"))
        val unhearted = mutableListOf<String>()
        val actions = TrackActionsPresenter(
            favorites = favorites,
            playlists = MutableStateFlow(emptyList()),
            playNextAction = {},
            addToQueueAction = {},
            toggleFavoriteTrack = { track: Track ->
                val now = track.canonicalId !in favorites.value
                favorites.value = if (now) favorites.value + track.canonicalId else favorites.value - track.canonicalId
                now
            },
            syncNowPlayingFavorite = { _, _ -> },
            addTracksToPlaylist = { _, _ -> null },
            createPlaylistWith = { _, _ -> error("unused") },
            duplicates = MutableStateFlow(DuplicateTracks.group(listOf(file, like))),
            unheartTrackIds = { ids -> unhearted += ids; favorites.value = favorites.value - ids },
        )
        assertTrue(actions.isFavorite(file, favorites.value))

        actions.toggleFavorite(file)

        assertEquals(listOf("spotify:midnight"), unhearted)
        assertFalse(actions.isFavorite(file, favorites.value))
        assertEquals(emptySet(), favorites.value, "the listed copy is not hearted in its place")

        actions.toggleFavorite(file)
        assertEquals(setOf("local:midnight"), favorites.value, "Heart hearts the copy you see")
    }
}
