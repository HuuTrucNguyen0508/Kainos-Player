package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.spotifyPlaybackQuality
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastGoodDiscoverWeeklyTest {
    private class MemoryFile(var text: String? = null) : SnapshotFile {
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }

    private val day = 24 * 3_600_000L
    private var now = 1_000 * day

    private fun spotifyTrack(id: String, title: String) = Track(
        canonicalId = "spotify:$id",
        title = title,
        artists = listOf(ArtistRef("spotify-artist:artist", "Artist")),
        durationMs = 200_000,
        artwork = Artwork("https://i.scdn.co/image/$id"),
        sources = listOf(
            PlaybackSource(
                provider = ProviderId.SPOTIFY,
                providerTrackId = id,
                quality = spotifyPlaybackQuality,
                isPlayable = true,
                handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, id, durationMs = 200_000),
            ),
        ),
    )

    private fun discoverWeekly(vararg tracks: Track) = Playlist(
        canonicalId = "spotify-playlist:dw",
        title = "Discover Weekly",
        description = "Your weekly mixtape of fresh music.",
        ownerName = "Spotify",
        trackCount = tracks.size,
        tracks = tracks.toList(),
        source = ProviderEntityRef(ProviderId.SPOTIFY, "dw"),
    )

    @Test
    fun aRefreshFinishingAfterDisconnectCannotRecreateTheSnapshot() = runTest {
        val file = MemoryFile()
        val store = LastGoodDiscoverWeekly(file) { now }
        val revision = store.revision
        store.clear()
        store.remember(discoverWeekly(spotifyTrack("a", "Song A")), "me", revision)
        assertNull(store.fallback("me"))
        assertNull(file.text)
    }

    @Test
    fun anUnownedSnapshotIsNotServedToAKnownAccount() = runTest {
        val store = LastGoodDiscoverWeekly(MemoryFile()) { now }
        store.remember(discoverWeekly(spotifyTrack("a", "Song A")), null)
        assertNull(store.fallback("someone-else"))
    }

    @Test
    fun aFailedRefreshAfterARestartStillShowsThePlayableListWithItsAge() = runTest {
        val file = MemoryFile()
        LastGoodDiscoverWeekly(file) { now }.remember(discoverWeekly(spotifyTrack("a", "Song A"), spotifyTrack("b", "Song B")), "me")
        now += 2 * day + 3_600_000

        val shown = LastGoodDiscoverWeekly(file) { now }.fallback("me")!!

        assertEquals("Discover Weekly · saved 2 days ago", shown.title)
        assertEquals("dw", shown.source.providerEntityId)
        assertEquals(listOf("Song A", "Song B"), shown.tracks.map { it.title })
        assertEquals(2, shown.trackCount)
        val handle = shown.tracks.first().sources.single().handle as PlaybackHandle.ProviderPlayback
        assertEquals("a", handle.trackId)
        assertEquals("Your weekly mixtape of fresh music.", shown.description)
    }

    @Test
    fun anEmptyListDoesNotReplaceTheLastGoodOne() = runTest {
        val store = LastGoodDiscoverWeekly(MemoryFile()) { now }
        store.remember(discoverWeekly(spotifyTrack("a", "Song A")), "me")
        val empty = discoverWeekly()
        assertEquals(empty, store.remember(empty, "me"))
        assertEquals(listOf("Song A"), store.fallback("me")!!.tracks.map { it.title })
    }

    @Test
    fun anotherAccountsListIsNeverShown() = runTest {
        val store = LastGoodDiscoverWeekly(MemoryFile()) { now }
        store.remember(discoverWeekly(spotifyTrack("a", "Song A")), "me")
        assertNull(store.fallback("someone-else"))
        assertNull(store.fallback(null), "an unknown account cannot establish ownership of the saved list")
    }

    @Test
    fun disconnectingForgetsTheListOnDisk() = runTest {
        val file = MemoryFile()
        val store = LastGoodDiscoverWeekly(file) { now }
        store.remember(discoverWeekly(spotifyTrack("a", "Song A")), "me")
        store.clear()
        assertNull(store.fallback("me"))
        assertNull(LastGoodDiscoverWeekly(file) { now }.fallback("me"))
    }

    @Test
    fun aDamagedFileIsIgnored() = runTest {
        assertNull(LastGoodDiscoverWeekly(MemoryFile("{not json")) { now }.fallback(null))
        assertNull(LastGoodDiscoverWeekly(null) { now }.fallback(null))
    }

    @Test
    fun ageReadsNaturally() {
        assertEquals("saved just now", savedAgo(10 * 60_000))
        assertEquals("saved 1 hour ago", savedAgo(90 * 60_000))
        assertEquals("saved 5 hours ago", savedAgo(5 * 3_600_000L))
        assertEquals("saved yesterday", savedAgo(30 * 3_600_000L))
        assertEquals("saved 6 days ago", savedAgo(6 * day))
    }
}
