package com.universalmusic.player.ui.screens

import com.universalmusic.player.domain.model.*
import kotlin.test.*

class LibrarySortPerf {
    @Test fun sortKeys() {
        for (size in listOf(2_000, 20_000, 100_000)) {
            val tracks = syntheticLibrary(size).mapIndexed { i, t -> t.copy(durationMs = if(i % 9 == 0) null else (i % 200) * 1000L) }
            for (sort in TrackSort.entries) {
                val reference = ReferenceSort.valueOf(sort.name)
                repeat(15) { run ->
                    for (candidate in if(run % 2 == 0) listOf(false, true) else listOf(true, false)) {
                        val start = System.nanoTime()
                        val result = if(candidate) sort.sort(tracks) else reference.sort(tracks)
                        val ms = (System.nanoTime()-start)/1e6
                        assertEquals(reference.sort(tracks), result)
                        if(run >= 5) println("LIBRARY sort:${sort.name}:${if(candidate) "keys" else "reference"}\t$size\t${run-5}\t$ms\t${result.size}")
                    }
                }
            }
        }
    }
}



private enum class ReferenceSort {
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
