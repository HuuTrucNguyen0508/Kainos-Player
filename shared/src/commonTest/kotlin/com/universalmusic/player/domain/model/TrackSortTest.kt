package com.universalmusic.player.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackSortTest {
    @Test
    fun namesAreSortedCaseInsensitivelyAfterTrimming() {
        val tracks = listOf(track(" zulu ", "z"), track("alpha", "a"), track("Bravo", "b"))

        val sorted = TrackSort.NAME_ASCENDING.sort(tracks)

        assertEquals(listOf("alpha", "Bravo", " zulu "), sorted.map { it.title })
        assertEquals(listOf(" zulu ", "Bravo", "alpha"), TrackSort.NAME_DESCENDING.sort(tracks).map { it.title })
    }

    @Test
    fun invalidDurationsAreLastWhenSortingAscending() {
        val tracks = listOf(track("zero", "zero", 0), track("long", "long", 200),
            track("short", "short", 10), track("missing", "missing", null), track("negative", "negative", -1))

        val sorted = TrackSort.DURATION_ASCENDING.sort(tracks)

        assertEquals(listOf("short", "long", "missing", "negative", "zero"), sorted.map { it.title })
    }

    @Test
    fun invalidDurationsAreLastWhenSortingDescending() {
        val tracks = listOf(track("zero", "zero", 0), track("short", "short", 10),
            track("long", "long", 200), track("missing", "missing", null), track("negative", "negative", -1))

        val sorted = TrackSort.DURATION_DESCENDING.sort(tracks)

        assertEquals(listOf("long", "short", "missing", "negative", "zero"), sorted.map { it.title })
    }

    private fun track(title: String, canonicalId: String, durationMs: Long? = 1): Track =
        Track(canonicalId = canonicalId, title = title, artists = emptyList(), durationMs = durationMs)
}
