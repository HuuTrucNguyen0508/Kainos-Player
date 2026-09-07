package com.universalmusic.player.domain.model

enum class ProviderId(val displayName: String) {
    LOCAL("Local library"),
    SPOTIFY("Spotify"),
    YOUTUBE_MUSIC("YouTube Music"),
    SAMPLE("Sample catalog"),
}
