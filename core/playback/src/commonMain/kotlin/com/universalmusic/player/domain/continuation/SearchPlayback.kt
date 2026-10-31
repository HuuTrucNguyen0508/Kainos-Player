package com.universalmusic.player.domain.continuation

import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track

/** What tapping a Search result plays: [tracks] from [startIndex], then [radio]'s recommendations. */
data class SearchPlayback(
    val tracks: List<Track>,
    val startIndex: Int,
    val radio: ProviderId?,
)

/**
 * The picked result keeps its source. With radio on, only the picked track is queued and its
 * provider's radio follows; with radio off, the same-source results from the list follow it.
 */
fun planSearchPlayback(results: List<Track>, index: Int, radioEnabled: Boolean): SearchPlayback {
    val picked = results.getOrNull(index) ?: return SearchPlayback(results, 0, radio = null)
    val provider = picked.radioProvider()
    if (radioEnabled && provider != null) return SearchPlayback(listOf(picked), 0, radio = provider)
    val sameSource = results.withIndex().filter { (i, track) -> i == index || track.radioProvider() == provider }
    return SearchPlayback(
        tracks = sameSource.map { it.value },
        startIndex = sameSource.indexOfFirst { it.index == index },
        radio = null,
    )
}
