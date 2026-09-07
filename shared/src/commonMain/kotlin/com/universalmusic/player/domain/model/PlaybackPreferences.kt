package com.universalmusic.player.domain.model

enum class SourceSelectionMode {
    AUTOMATIC,
    PREFER_LOSSLESS,
    PREFER_HIGHEST_BITRATE,
    PREFER_SPOTIFY,
    PREFER_YOUTUBE_MUSIC,
    FORCE_SPOTIFY,
    FORCE_YOUTUBE_MUSIC,
}

data class PlaybackPreferences(
    val sourceSelection: SourceSelectionMode = SourceSelectionMode.AUTOMATIC,
    val preferredProvider: ProviderId? = null,
    val crossfadeMs: Int = 0,
    val gapless: Boolean = true,
    val normalizeVolume: Boolean = false,
) {
    companion object {
        val Default = PlaybackPreferences()
    }
}

data class ResolvedPlayback(
    val track: Track,
    val source: PlaybackSource,
    val fallbacks: List<PlaybackSource>,
    val reason: String,
)

data class SourceFallbackEvent(
    val from: ProviderId,
    val to: ProviderId,
    val message: String,
)
