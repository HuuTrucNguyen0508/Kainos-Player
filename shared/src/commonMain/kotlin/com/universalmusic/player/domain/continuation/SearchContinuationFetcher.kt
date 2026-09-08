package com.universalmusic.player.domain.continuation

import com.universalmusic.player.data.spotify.SpotifyProvider
import com.universalmusic.player.data.spotify.SpotifyRecommendationsAccess
import com.universalmusic.player.data.youtube.YouTubeMusicProvider
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track

/**
 * Builds eligible Search continuation tracks.
 * Spotify uses `/recommendations` only when the Client ID gate allows it.
 * Never falls back to Liked Songs shuffle.
 */
class SearchContinuationFetcher(
    private val spotify: SpotifyProvider,
    private val youtube: YouTubeMusicProvider,
    private val batchLimit: Int = 12,
) {
    suspend fun fetch(seed: Track, query: String, exclude: Set<String>): List<Track> {
        val byProvider = linkedMapOf<String, Track>()
        fun ingest(tracks: List<Track>) {
            for (track in tracks) {
                if (track.canonicalId in exclude) continue
                byProvider.putIfAbsent(track.canonicalId, track)
                if (byProvider.size >= batchLimit) return
            }
        }

        when {
            seed.sourceFor(ProviderId.YOUTUBE_MUSIC) != null &&
                youtube.state.value != ProviderState.NOT_CONFIGURED -> {
                ingest(youtubeContinuation(seed, query, exclude))
            }
            seed.sourceFor(ProviderId.SPOTIFY) != null &&
                spotify.state.value != ProviderState.NOT_CONFIGURED &&
                spotify.state.value != ProviderState.AUTH_REQUIRED -> {
                when (val access = spotify.ensureRecommendationsAccess()) {
                    SpotifyRecommendationsAccess.AVAILABLE -> {
                        val ids = listOfNotNull(seed.sourceFor(ProviderId.SPOTIFY)?.providerTrackId)
                        if (ids.isNotEmpty()) {
                            ingest(spotify.getRecommendations(ids, batchLimit).getOrDefault(emptyList()))
                        }
                    }
                    SpotifyRecommendationsAccess.UNAVAILABLE -> {
                        error(
                            "Spotify recommendations/radio unavailable for this Client ID " +
                                "(Web API gate). No Liked Songs shuffle fallback.",
                        )
                    }
                    SpotifyRecommendationsAccess.UNKNOWN -> {
                        error("Spotify recommendations availability unknown.")
                    }
                }
            }
            else -> {
                if (youtube.state.value != ProviderState.NOT_CONFIGURED) {
                    ingest(youtubeContinuation(seed, query, exclude))
                }
            }
        }
        return byProvider.values.toList()
    }

    private suspend fun youtubeContinuation(
        seed: Track,
        query: String,
        exclude: Set<String>,
    ): List<Track> {
        val artist = seed.artists.firstOrNull()?.name.orEmpty()
        val title = seed.title
        val candidates = listOf(
            listOf(artist, title).filter { it.isNotBlank() }.joinToString(" "),
            query.takeIf { it.isNotBlank() },
            title,
        ).filterNotNull().filter { it.isNotBlank() }.distinct()

        val found = linkedMapOf<String, Track>()
        for (q in candidates) {
            if (found.size >= batchLimit) break
            val result = runCatching { youtube.search(q) }.getOrNull() ?: continue
            for (track in result.tracks) {
                if (track.canonicalId in exclude) continue
                found.putIfAbsent(track.canonicalId, track)
                if (found.size >= batchLimit) break
            }
        }
        return found.values.toList()
    }
}
