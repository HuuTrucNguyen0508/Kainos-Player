package com.universalmusic.player.domain.model

data class Playlist(
    val canonicalId: String,
    val title: String,
    val description: String? = null,
    val artwork: Artwork? = null,
    val ownerName: String? = null,
    val trackCount: Int? = null,
    val tracks: List<Track> = emptyList(),
    val source: ProviderEntityRef,
)
