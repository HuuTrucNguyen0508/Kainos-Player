package com.universalmusic.player.domain.continuation

import com.universalmusic.player.domain.model.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchPlaybackTest {
    private val results = listOf(sp("s1"), yt("y1"), sp("s2"), yt("y2"), sp("s3"))

    @Test
    fun radioQueuesOnlyThePickedTrackThenItsSource() {
        assertEquals(
            SearchPlayback(listOf(sp("s2")), 0, radio = ProviderId.SPOTIFY),
            planSearchPlayback(results, index = 2, radioEnabled = true),
        )
        assertEquals(
            SearchPlayback(listOf(yt("y1")), 0, radio = ProviderId.YOUTUBE_MUSIC),
            planSearchPlayback(results, index = 1, radioEnabled = true),
        )
    }

    @Test
    fun radioOffKeepsTheSameSourceResultsAroundThePick() {
        assertEquals(
            SearchPlayback(listOf(sp("s1"), sp("s2"), sp("s3")), 1, radio = null),
            planSearchPlayback(results, index = 2, radioEnabled = false),
        )
        assertEquals(
            SearchPlayback(listOf(yt("y1"), yt("y2")), 1, radio = null),
            planSearchPlayback(results, index = 3, radioEnabled = false),
        )
    }

    @Test
    fun aTrackWithoutARadioSourceNeverStartsRadio() {
        val mixed = listOf(local("l1"), sp("s1"), local("l2"))

        assertEquals(
            SearchPlayback(listOf(local("l1"), local("l2")), 1, radio = null),
            planSearchPlayback(mixed, index = 2, radioEnabled = true),
        )
    }
}
