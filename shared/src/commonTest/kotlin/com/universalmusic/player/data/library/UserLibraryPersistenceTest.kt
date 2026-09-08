package com.universalmusic.player.data.library

import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UserLibraryPersistenceTest {
    @Test
    fun roundTripsFavoritesRememberedAndRecentsWithoutStreamUrls() = runTest {
        val store = InMemoryUserLibraryStore()
        val library = LibraryRepository(scope = this, store = store, clock = { 1_000L })

        val youtube = youtubeTrack(streamUrl = "https://googlevideo.example/expire=1")
        library.toggleFavorite(youtube)
        library.recordPlay(localTrack())
        advanceUntilIdle()

        val onDisk = store.read()
        assertTrue("yt:abc" in onDisk.favoriteIds)
        assertEquals(1, onDisk.remembered.count { it.canonicalId == "yt:abc" })
        assertNull(onDisk.remembered.first { it.canonicalId == "yt:abc" }.sources.single().localLocation)
        assertTrue(onDisk.remembered.any { it.canonicalId == "local:1" })
        assertTrue(onDisk.recents.any { it.canonicalId == "local:1" })

        val youtubeSource = onDisk.remembered.first { it.canonicalId == "yt:abc" }.toDomain().sources.single()
        assertNull(youtubeSource.streamUrl)
        assertTrue(youtubeSource.handle is PlaybackHandle.ProviderPlayback)

        val reloaded = LibraryRepository(scope = this, store = store, clock = { 2_000L })
        reloaded.load(activeSpotifyAccountId = null)
        assertTrue(reloaded.isFavorite("yt:abc"))
        assertEquals("Cached YT", reloaded.savedTracks.value.first { it.canonicalId == "yt:abc" }.title)
        assertTrue(reloaded.recentlyPlayed.value.any { it.canonicalId == "local:1" })
    }

    @Test
    fun accountSwitchDropsSpotifyScopedFavoritesButKeepsYouTube() = runTest {
        val store = InMemoryUserLibraryStore()
        store.write(
            UserLibrarySnapshot(
                spotifyAccountId = "user-a",
                favoriteIds = listOf("spotify:1", "yt:keep", "local:1"),
                remembered = listOf(
                    spotifyPersisted("spotify:1"),
                    youtubePersisted("yt:keep"),
                    localPersisted("local:1"),
                ),
                recents = listOf(spotifyPersisted("spotify:1"), youtubePersisted("yt:keep")),
            ),
        )
        val library = LibraryRepository(scope = this, store = store)
        library.load(activeSpotifyAccountId = "user-b")

        assertFalse(library.isFavorite("spotify:1"))
        assertTrue(library.isFavorite("yt:keep"))
        assertTrue(library.isFavorite("local:1"))
        assertTrue(library.savedTracks.value.none { it.canonicalId == "spotify:1" })
        assertEquals("user-b", store.read().spotifyAccountId)
    }

    @Test
    fun disconnectClearsSpotifyAccountScopedData() = runTest {
        val store = InMemoryUserLibraryStore()
        val library = LibraryRepository(scope = this, store = store)
        library.applySnapshot(
            UserLibrarySnapshot(
                spotifyAccountId = "user-a",
                favoriteIds = listOf("spotify:1", "yt:keep"),
                remembered = listOf(spotifyPersisted("spotify:1"), youtubePersisted("yt:keep")),
            ),
        )
        library.setSpotifyAccountId(null)
        assertFalse(library.isFavorite("spotify:1"))
        assertTrue(library.isFavorite("yt:keep"))
        assertNull(store.read().spotifyAccountId)
    }

    @Test
    fun persistedLocalTrackKeepsStableLocation() {
        val track = localTrack().toPersisted(0)
        assertEquals("file:///music/song.flac", track.sources.single().localLocation)
        val restored = track.toDomain()
        assertEquals(PlaybackHandle.Url("file:///music/song.flac"), restored.sources.single().handle)
        assertFalse(restored.requiresNetworkToPlay())
        assertTrue(youtubeTrack().requiresNetworkToPlay())
    }
}

private class InMemoryUserLibraryStore : UserLibraryStore {
    private var snapshot = UserLibrarySnapshot()
    override suspend fun read(): UserLibrarySnapshot = snapshot
    override suspend fun write(snapshot: UserLibrarySnapshot) {
        this.snapshot = snapshot
    }
}

private fun youtubeTrack(streamUrl: String? = null) = Track(
    canonicalId = "yt:abc",
    title = "Cached YT",
    artists = listOf(ArtistRef("yt-artist:1", "YT Artist")),
    artwork = Artwork("https://i.ytimg.com/vi/abc/hqdefault.jpg"),
    durationMs = 180_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.YOUTUBE_MUSIC,
            providerTrackId = "abc",
            streamUrl = streamUrl,
            quality = AudioQuality(QualityTier.STANDARD, codec = "opus"),
            isPlayable = true,
            handle = if (streamUrl != null) {
                PlaybackHandle.Url(streamUrl)
            } else {
                PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, "abc", 180_000)
            },
        ),
    ),
)

private fun localTrack() = Track(
    canonicalId = "local:1",
    title = "Local Song",
    artists = listOf(ArtistRef("local-artist:1", "Local Artist")),
    durationMs = 200_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.LOCAL,
            providerTrackId = "1",
            streamUrl = "file:///music/song.flac",
            isPlayable = true,
            handle = PlaybackHandle.Url("file:///music/song.flac"),
        ),
    ),
)

private fun youtubePersisted(id: String) = PersistedTrack(
    canonicalId = id,
    title = "YT",
    artists = listOf(PersistedArtist("a", "A")),
    sources = listOf(PersistedSource(ProviderId.YOUTUBE_MUSIC.name, id.removePrefix("yt:"))),
)

private fun spotifyPersisted(id: String) = PersistedTrack(
    canonicalId = id,
    title = "Spotify",
    artists = listOf(PersistedArtist("a", "A")),
    sources = listOf(PersistedSource(ProviderId.SPOTIFY.name, id.removePrefix("spotify:"))),
)

private fun localPersisted(id: String) = PersistedTrack(
    canonicalId = id,
    title = "Local",
    artists = listOf(PersistedArtist("a", "A")),
    sources = listOf(
        PersistedSource(ProviderId.LOCAL.name, id.removePrefix("local:"), localLocation = "file:///x.mp3"),
    ),
)
