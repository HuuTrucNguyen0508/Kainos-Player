package com.universalmusic.player.domain.model

data class ProviderCapabilities(
    val search: Boolean,
    val metadata: Boolean,
    val playlists: Boolean,
    val library: Boolean,
    val playback: Boolean,
    val backgroundPlayback: Boolean,
    val losslessPlayback: Boolean,
)
