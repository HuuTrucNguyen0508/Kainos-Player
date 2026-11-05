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

/**
 * One page of a provider playlist's songs. [next] is the provider's cursor for the following page
 * (a Spotify offset or a YouTube page token), null on the last page. [total] counts every entry
 * the provider reports, including ones that cannot be shown.
 */
data class PlaylistTracksPage(
    val tracks: List<Track>,
    val next: String?,
    val total: Int? = null,
)
