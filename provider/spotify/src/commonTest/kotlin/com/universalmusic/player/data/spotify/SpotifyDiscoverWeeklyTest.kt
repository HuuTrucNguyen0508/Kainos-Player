package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyDiscoverWeeklyTest {
    @Test
    fun findsDiscoverWeeklyCaseInsensitivelyAndIgnoresOthers() {
        val playlists = listOf(
            playlist("likes", "Liked Songs"),
            playlist("dw", "discover weekly"),
            playlist("rr", "Release Radar"),
        )

        val found = findDiscoverWeekly(playlists)

        assertEquals("dw", found?.source?.providerEntityId)
        assertTrue(found!!.isDiscoverWeekly())
        assertNull(findDiscoverWeekly(playlists.filterNot { it.isDiscoverWeekly() }))
    }

    @Test
    fun findsDiscoverWeeklyByMixtapeDescription() {
        val found = findDiscoverWeekly(
            listOf(
                playlist("other", "Made For You"),
                Playlist(
                    canonicalId = "spotify-playlist:dw",
                    title = "Discover Weekly",
                    description = "Your weekly mixtape of fresh music.",
                    source = ProviderEntityRef(ProviderId.SPOTIFY, "dw"),
                ),
            ),
        )
        assertEquals("dw", found?.source?.providerEntityId)
    }

    private fun playlist(id: String, title: String) = Playlist(
        canonicalId = "spotify-playlist:$id",
        title = title,
        source = ProviderEntityRef(ProviderId.SPOTIFY, id),
    )
}
