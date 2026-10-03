package com.universalmusic.player.domain.model

enum class TrackSort {
    NAME_ASCENDING,
    NAME_DESCENDING,
    DURATION_ASCENDING,
    DURATION_DESCENDING,
    ;

    fun sort(tracks: List<Track>): List<Track> {
        val titleTieBreaker = compareBy<Track>({ it.title.trim().lowercase() }, { it.canonicalId })
        val comparator = when (this) {
            NAME_ASCENDING -> Comparator { left, right ->
                compareTitles(left, right, descending = false, titleTieBreaker)
            }
            NAME_DESCENDING -> Comparator { left, right ->
                compareTitles(left, right, descending = true, titleTieBreaker)
            }
            DURATION_ASCENDING -> compareDurations(descending = false, titleTieBreaker)
            DURATION_DESCENDING -> compareDurations(descending = true, titleTieBreaker)
        }
        return tracks.sortedWith(comparator)
    }

    private fun compareTitles(
        left: Track,
        right: Track,
        descending: Boolean,
        titleTieBreaker: Comparator<Track>,
    ): Int {
        val leftTitle = left.title.trim().lowercase()
        val rightTitle = right.title.trim().lowercase()
        val primary = leftTitle.compareTo(rightTitle)
        return if (primary != 0) {
            if (descending) -primary else primary
        } else {
            titleTieBreaker.compare(left, right)
        }
    }

    private fun compareDurations(
        descending: Boolean,
        titleTieBreaker: Comparator<Track>,
    ): Comparator<Track> = Comparator { left, right ->
        val leftDuration = left.durationMs?.takeIf { it > 0 }
        val rightDuration = right.durationMs?.takeIf { it > 0 }
        when {
            leftDuration == null && rightDuration == null -> titleTieBreaker.compare(left, right)
            leftDuration == null -> 1
            rightDuration == null -> -1
            leftDuration != rightDuration -> {
                val primary = leftDuration.compareTo(rightDuration)
                if (descending) -primary else primary
            }
            else -> titleTieBreaker.compare(left, right)
        }
    }
}
