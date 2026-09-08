package com.universalmusic.player.data.spotify

/**
 * Feasibility of Spotify Web API recommendations / radio for this Client ID.
 * Post–27 Nov 2024 development apps typically receive 403/404 on `/v1/recommendations`.
 */
enum class SpotifyRecommendationsAccess {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
}
