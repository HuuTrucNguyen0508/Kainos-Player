package com.universalmusic.player.domain.matching

import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Track
import org.junit.Test
import kotlin.test.assertEquals

class DuplicateTracksScaleTest {
    @Test(timeout = 10_000)
    fun largeSameTitleBucketKeepsDifferentDurationsWithoutPairwiseScanning() {
        val artists = listOf(ArtistRef("artist", "Band"))
        val local = (1..120_000).map { i -> Track("local:$i", "Song", artists, durationMs = i * 10_000L) }
        val streams = local.mapIndexed { i, track -> track.copy(canonicalId = "spotify:$i") }
        val groups = DuplicateTracks.group(local + streams)
        assertEquals(streams.size, groups.hiddenCount)
        assertEquals(local, groups.visible(local + streams))
    }
}
