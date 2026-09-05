package com.universalmusic.player.domain.model

data class Track(
    val canonicalId: String,
    val title: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef? = null,
    val durationMs: Long? = null,
    val artwork: Artwork? = null,
    val explicit: Boolean = false,
    val isrc: String? = null,
    val sources: List<PlaybackSource> = emptyList(),
) {
    val artistLine: String
        get() = artists.joinToString(", ") { it.name }

    fun sourceFor(provider: ProviderId): PlaybackSource? =
        sources.firstOrNull { it.provider == provider }

    fun playableSources(): List<PlaybackSource> = sources.filter { it.isPlayable }
}
