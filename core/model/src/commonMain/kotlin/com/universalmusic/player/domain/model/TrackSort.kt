package com.universalmusic.player.domain.model

enum class TrackSort {
    NAME_ASCENDING,
    NAME_DESCENDING,
    DURATION_ASCENDING,
    DURATION_DESCENDING,
    /** First artist A to Z, each artist's albums together in album order. */
    ARTIST_ASCENDING,
    /** Album title A to Z in album order; tracks without an album last. */
    ALBUM_ASCENDING,
    /** Newest release year first; tracks without a year last. */
    YEAR_DESCENDING,
    /** Newest file first; streamed tracks, which have no file time, last. */
    RECENTLY_ADDED,
    ;

    fun sort(tracks: List<Track>): List<Track> {
        val keys = tracks.map(::SortKey)
        return keys.sortedWith(comparator()).map { it.track }
    }

    private fun comparator(): Comparator<SortKey> {
        val byTitle = compareBy<SortKey> { it.title }.thenBy { it.track.canonicalId }
        val inAlbum = nullsLastBy { it.album }
            .then(nullsLastBy { it.track.album?.canonicalId })
            .then(Comparator { left, right -> AlbumTrackOrder.compare(left.track, right.track) })
        return when (this) {
            NAME_ASCENDING -> byTitle
            NAME_DESCENDING -> compareByDescending<SortKey> { it.title }.thenBy { it.track.canonicalId }
            DURATION_ASCENDING -> nullsLastBy { it.duration }.then(byTitle)
            DURATION_DESCENDING -> nullsLastBy(descending = true) { it.duration }.then(byTitle)
            ARTIST_ASCENDING -> nullsLastBy { it.artist }.then(inAlbum)
            ALBUM_ASCENDING -> inAlbum
            YEAR_DESCENDING -> nullsLastBy(descending = true) { it.track.year }.then(inAlbum)
            RECENTLY_ADDED -> nullsLastBy(descending = true) { it.track.addedAtMs }.then(byTitle)
        }
    }
}

/** Missing values sort last in both directions. */
private fun <K : Comparable<K>> nullsLastBy(descending: Boolean = false, key: (SortKey) -> K?) =
    Comparator<SortKey> { left, right ->
        val a = key(left)
        val b = key(right)
        when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            descending -> b.compareTo(a)
            else -> a.compareTo(b)
        }
    }

private class SortKey(val track: Track) {
    val title = track.title.trim().lowercase()
    val duration = track.durationMs?.takeIf { it > 0 }
    val artist = track.artists.firstOrNull()?.name?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    val album = track.album?.title?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
}
