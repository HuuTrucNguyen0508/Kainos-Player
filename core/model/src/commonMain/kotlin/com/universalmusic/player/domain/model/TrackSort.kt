package com.universalmusic.player.domain.model

enum class TrackSort {
    NAME_ASCENDING,
    NAME_DESCENDING,
    DURATION_ASCENDING,
    DURATION_DESCENDING,
    ;

    fun sort(tracks: List<Track>): List<Track> {
        val keys = tracks.map { SortKey(it, it.title.trim().lowercase(), it.durationMs?.takeIf { ms -> ms > 0 }) }
        val descending = this == NAME_DESCENDING || this == DURATION_DESCENDING
        val byDuration = this == DURATION_ASCENDING || this == DURATION_DESCENDING
        return keys.sortedWith { left, right ->
            val primary = if (byDuration) {
                when {
                    left.duration == null && right.duration == null -> 0
                    left.duration == null -> return@sortedWith 1
                    right.duration == null -> return@sortedWith -1
                    else -> left.duration.compareTo(right.duration)
                }
            } else left.title.compareTo(right.title)
            when {
                primary != 0 -> if (descending) -primary else primary
                else -> {
                    val title = left.title.compareTo(right.title)
                    if (title != 0) title else left.track.canonicalId.compareTo(right.track.canonicalId)
                }
            }
        }.map { it.track }
    }
}

private data class SortKey(val track: Track, val title: String, val duration: Long?)
