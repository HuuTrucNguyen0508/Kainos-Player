package com.universalmusic.player.domain.search

import com.universalmusic.player.domain.matching.TrackMatcher
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderSearchStatus
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.SearchResult
import com.universalmusic.player.domain.model.UnifiedSearchResult
import com.universalmusic.player.domain.provider.MusicProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class UnifiedSearch(
    private val providers: List<MusicProvider>,
    private val matcher: TrackMatcher = TrackMatcher(),
    private val timeoutMs: Long = 8_000,
) {
    suspend fun search(query: String): UnifiedSearchResult = coroutineScope {
        val jobs = providers.map { provider ->
            async {
                provider.providerId to runCatching {
                    withTimeoutOrNull(timeoutMs) { provider.search(query) }
                }.onFailure { if (it is CancellationException) throw it }
            }
        }

        val tracks = mutableListOf<com.universalmusic.player.domain.model.Track>()
        val albums = mutableListOf<com.universalmusic.player.domain.model.Album>()
        val artists = mutableListOf<com.universalmusic.player.domain.model.Artist>()
        val playlists = mutableListOf<com.universalmusic.player.domain.model.Playlist>()
        val statuses = mutableMapOf<ProviderId, ProviderSearchStatus>()

        jobs.forEach { job ->
            val (providerId, outcome) = job.await()
            val status = outcome.fold(
                onSuccess = { result ->
                    if (result == null) {
                        ProviderSearchStatus(providerId, ProviderState.UNAVAILABLE, message = "Timed out")
                    } else {
                        tracks += result.tracks
                        albums += result.albums
                        artists += result.artists
                        playlists += result.playlists
                        ProviderSearchStatus(
                            provider = providerId,
                            state = ProviderState.AVAILABLE,
                            resultCount = result.tracks.size + result.albums.size + result.artists.size + result.playlists.size,
                        )
                    }
                },
                onFailure = { error ->
                    ProviderSearchStatus(
                        provider = providerId,
                        state = providers.first { it.providerId == providerId }.state.value
                            .takeIf { it in listOf(ProviderState.AUTH_REQUIRED, ProviderState.RATE_LIMITED, ProviderState.NOT_CONFIGURED) }
                            ?: classify(error),
                        message = error.message,
                    )
                },
            )
            statuses[providerId] = status
        }

        UnifiedSearchResult(
            tracks = matcher.group(tracks),
            albums = albums,
            artists = artists,
            playlists = playlists,
            providerStatuses = statuses,
        )
    }

    private fun classify(error: Throwable): ProviderState {
        val message = error.message.orEmpty().lowercase()
        return when {
            "auth" in message || "login" in message || "unauthorized" in message -> ProviderState.AUTH_REQUIRED
            "rate" in message || "429" in message -> ProviderState.RATE_LIMITED
            else -> ProviderState.UNAVAILABLE
        }
    }
}

fun emptySearchResult(): SearchResult = SearchResult()
