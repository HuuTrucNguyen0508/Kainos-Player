package com.universalmusic.player.domain.search

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderCapabilities
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.SearchResult
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.provider.MusicProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedSearchTest {
    @Test
    fun oneProviderFailureDoesNotBlockOthers() = runTest {
        val search = UnifiedSearch(
            listOf(
                FakeProvider(ProviderId.SPOTIFY, result = SearchResult(tracks = listOf(track("Song", "Artist", provider = ProviderId.SPOTIFY)))),
                FakeProvider(ProviderId.YOUTUBE_MUSIC, error = IllegalStateException("Spotify-like boom").let { IllegalStateException("unavailable") }),
                FakeProvider(ProviderId.SOUNDCLOUD, result = SearchResult(tracks = listOf(track("Song", "Artist", provider = ProviderId.SOUNDCLOUD)))),
            ),
        )
        val result = search.search("Song")
        assertTrue(result.tracks.isNotEmpty())
        assertEquals(ProviderState.AVAILABLE, result.providerStatuses.getValue(ProviderId.SPOTIFY).state)
        assertEquals(ProviderState.UNAVAILABLE, result.providerStatuses.getValue(ProviderId.YOUTUBE_MUSIC).state)
        assertEquals(ProviderState.AVAILABLE, result.providerStatuses.getValue(ProviderId.SOUNDCLOUD).state)
    }

    @Test
    fun twoProviderFailuresStillReturnRemainingResults() = runTest {
        val search = UnifiedSearch(
            listOf(
                FakeProvider(ProviderId.SPOTIFY, error = IllegalStateException("auth required")),
                FakeProvider(ProviderId.YOUTUBE_MUSIC, error = IllegalStateException("rate limited 429")),
                FakeProvider(ProviderId.SOUNDCLOUD, result = SearchResult(tracks = listOf(track("Only", "Here", provider = ProviderId.SOUNDCLOUD)))),
            ),
        )
        val result = search.search("Only")
        assertEquals(1, result.tracks.size)
        assertEquals(ProviderState.AUTH_REQUIRED, result.providerStatuses.getValue(ProviderId.SPOTIFY).state)
        assertEquals(ProviderState.RATE_LIMITED, result.providerStatuses.getValue(ProviderId.YOUTUBE_MUSIC).state)
    }

    @Test
    fun slowProviderTimesOut() = runTest {
        val search = UnifiedSearch(
            providers = listOf(
                FakeProvider(ProviderId.SPOTIFY, result = SearchResult(tracks = listOf(track("Fast", "A", provider = ProviderId.SPOTIFY)))),
                FakeProvider(ProviderId.YOUTUBE_MUSIC, delayMs = 50, result = SearchResult(tracks = listOf(track("Slow", "B", provider = ProviderId.YOUTUBE_MUSIC)))),
            ),
            timeoutMs = 10,
        )
        val result = search.search("query")
        assertEquals(ProviderState.UNAVAILABLE, result.providerStatuses.getValue(ProviderId.YOUTUBE_MUSIC).state)
        assertTrue(result.tracks.any { it.title == "Fast" })
    }

    @Test
    fun emptyResultsAreValid() = runTest {
        val search = UnifiedSearch(
            listOf(
                FakeProvider(ProviderId.SPOTIFY, result = SearchResult()),
                FakeProvider(ProviderId.SOUNDCLOUD, result = SearchResult()),
            ),
        )
        val result = search.search("zzzz")
        assertTrue(result.tracks.isEmpty())
        assertEquals(0, result.providerStatuses.getValue(ProviderId.SPOTIFY).resultCount)
    }

    @Test
    fun duplicateResultsAreGrouped() = runTest {
        val search = UnifiedSearch(
            listOf(
                FakeProvider(ProviderId.SPOTIFY, result = SearchResult(tracks = listOf(track("Same", "Band", "Album", isrc = "USABC123", provider = ProviderId.SPOTIFY)))),
                FakeProvider(ProviderId.YOUTUBE_MUSIC, result = SearchResult(tracks = listOf(track("Same", "Band", "Album", isrc = "USABC123", provider = ProviderId.YOUTUBE_MUSIC)))),
            ),
        )
        val result = search.search("Same")
        assertEquals(1, result.tracks.size)
        assertEquals(2, result.tracks.single().sources.size)
    }
}

private class FakeProvider(
    override val providerId: ProviderId,
    private val result: SearchResult = SearchResult(),
    private val error: Throwable? = null,
    private val delayMs: Long = 0,
) : MusicProvider {
    override val state: StateFlow<ProviderState> = MutableStateFlow(ProviderState.AVAILABLE)

    override suspend fun search(query: String): SearchResult {
        if (delayMs > 0) delay(delayMs)
        error?.let { throw it }
        return result
    }

    override suspend fun getTrack(id: String): Track? = null
    override suspend fun getAlbum(id: String): Album? = null
    override suspend fun getArtist(id: String): Artist? = null
    override suspend fun getPlaylist(id: String): Playlist? = null
    override suspend fun getStream(track: Track): PlaybackSource? = track.sources.firstOrNull()
    override suspend fun getCapabilities(): ProviderCapabilities = ProviderCapabilities(
        search = true,
        metadata = true,
        playlists = false,
        library = false,
        playback = true,
        backgroundPlayback = true,
        losslessPlayback = false,
    )
}
