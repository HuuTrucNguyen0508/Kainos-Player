package com.universalmusic.player.domain.model

data class ArtistRef(
    val canonicalId: String,
    val name: String,
    val artwork: Artwork? = null,
)

data class Artist(
    val canonicalId: String,
    val name: String,
    val artwork: Artwork? = null,
    val biography: String? = null,
    val sources: List<ProviderEntityRef> = emptyList(),
)
