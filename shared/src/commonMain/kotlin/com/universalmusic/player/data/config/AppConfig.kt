package com.universalmusic.player.data.config

data class AppConfig(
    val spotifyClientId: String? = null,
    val spotifyRedirectUri: String,
    val youtubeDataApiKey: String? = null,
    val soundCloudClientId: String? = null,
    val soundCloudClientSecret: String? = null,
    val soundCloudRedirectUri: String,
) {
    val hasSpotifyCredentials: Boolean get() = !spotifyClientId.isNullOrBlank()
    val hasYouTubeCredentials: Boolean get() = !youtubeDataApiKey.isNullOrBlank()
    val hasSoundCloudCredentials: Boolean get() = !soundCloudClientId.isNullOrBlank()
}

interface ConfigStore {
    fun current(): AppConfig
}
