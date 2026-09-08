package com.universalmusic.player.domain.model

data class PlaybackSource(
    val provider: ProviderId,
    val providerTrackId: String,
    val streamUrl: String? = null,
    val quality: AudioQuality? = null,
    val isPlayable: Boolean,
    val handle: PlaybackHandle,
)

sealed interface PlaybackHandle {
    data class Url(val url: String) : PlaybackHandle

    data class ProviderPlayback(
        val provider: ProviderId,
        val trackId: String,
        /** Used by engines to emit natural ENDED for Connect/timer backends. */
        val durationMs: Long? = null,
    ) : PlaybackHandle
}
