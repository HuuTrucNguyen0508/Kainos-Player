package com.universalmusic.player.domain.model

data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

data class UnifiedSearchResult(
    val tracks: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val playlists: List<Playlist>,
    val providerStatuses: Map<ProviderId, ProviderSearchStatus>,
)

data class ProviderSearchStatus(
    val provider: ProviderId,
    val state: ProviderState,
    val resultCount: Int = 0,
    val message: String? = null,
)

data class TrackMatch(
    val track: Track,
    val confidence: Float,
    val reason: MatchReason,
)

enum class MatchReason {
    ISRC,
    NORMALIZED_METADATA,
    DURATION_SIMILARITY,
    FUZZY_METADATA,
    NONE,
}
