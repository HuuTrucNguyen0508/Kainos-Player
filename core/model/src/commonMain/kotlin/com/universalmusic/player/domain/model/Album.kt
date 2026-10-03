package com.universalmusic.player.domain.model

data class AlbumRef(
    val canonicalId: String,
    val title: String,
    val artwork: Artwork? = null,
    val year: Int? = null,
)

data class Album(
    val canonicalId: String,
    val title: String,
    val artists: List<ArtistRef>,
    val artwork: Artwork? = null,
    val year: Int? = null,
    val tracks: List<Track> = emptyList(),
    val sources: List<ProviderEntityRef> = emptyList(),
)
