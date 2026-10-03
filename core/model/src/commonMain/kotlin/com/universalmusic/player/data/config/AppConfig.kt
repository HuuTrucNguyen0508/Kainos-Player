package com.universalmusic.player.data.config

data class AppConfig(
    val spotifyClientId: String? = null,
    val spotifyRedirectUri: String,
    val youtubeDataApiKey: String? = null,
) {
    val hasSpotifyCredentials: Boolean get() = !spotifyClientId.isNullOrBlank()
    val hasYouTubeCredentials: Boolean get() = !youtubeDataApiKey.isNullOrBlank()
}

interface ConfigStore {
    fun current(): AppConfig
}
