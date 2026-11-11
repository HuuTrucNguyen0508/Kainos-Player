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
    val localContentKey: String? = null,
    /** Position on its album, from the file's tags; null when the source does not say. */
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** When the file arrived in the library (its modification time); null for streamed tracks. */
    val addedAtMs: Long? = null,
) {
    val artistLine: String
        get() = artists.joinToString(", ") { it.name }

    fun sourceFor(provider: ProviderId): PlaybackSource? =
        sources.firstOrNull { it.provider == provider }

    fun playableSources(): List<PlaybackSource> = sources.filter { it.isPlayable }
}

/** Disc, then track number, then title; untagged tracks follow the numbered ones. */
val AlbumTrackOrder: Comparator<Track> = Comparator { left, right ->
    compareValuesBy(
        left, right,
        { it.discNumber ?: if (it.trackNumber != null) 1 else Int.MAX_VALUE },
        { it.trackNumber ?: Int.MAX_VALUE },
        { it.title.trim().lowercase() },
        { it.canonicalId },
    )
}

fun List<Track>.inAlbumOrder(): List<Track> = sortedWith(AlbumTrackOrder)
