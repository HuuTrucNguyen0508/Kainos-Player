package com.universalmusic.player.domain.model

data class AlbumRef(
    val canonicalId: String,
    val title: String,
    val artwork: Artwork? = null,
    val year: Int? = null,
    /** Album artist when the source names one; empty means use the tracks' artists. */
    val artists: List<ArtistRef> = emptyList(),
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
