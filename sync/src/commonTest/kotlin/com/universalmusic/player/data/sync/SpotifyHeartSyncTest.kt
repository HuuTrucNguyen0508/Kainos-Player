package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spotify-scoped app hearts cross Home sync only between devices on the same Spotify account. */
class SpotifyHeartSyncTest {
    private var now = 1_000L

    private suspend fun device(id: String, account: String?) =
        LibraryRepository(clock = { now }, deviceIdProvider = { id }).apply { setSpotifyAccountId(account) }

    private fun LibraryRepository.spotifyHearts() = favoriteIds.value.filter { it.startsWith("spotify:") }.toSet()

    private fun HeartsSyncDocument.spotifyOps() = ops.filter { it.canonicalId.startsWith("spotify:") }

    @Test
    fun sameAccountHeartsCrossBothWaysWithMetadata() = runTest {
        val phone = device("phone", "account-a")
        val pc = device("pc", "account-a")
        phone.toggleFavorite(spotifyTrack("1"))
        pc.toggleFavorite(spotifyTrack("2"))

        val reply = pc.mergeAndPersistSyncState(phone.exportHeartsSyncDocument()).forPeerSpotifyAccount("account-a")
        phone.mergeAndPersistSyncState(reply)

        assertEquals(setOf("spotify:1", "spotify:2"), pc.spotifyHearts())
        assertEquals(setOf("spotify:1", "spotify:2"), phone.spotifyHearts())
        assertEquals("Song 1", pc.savedTracks.value.single { it.canonicalId == "spotify:1" }.title)
        assertEquals("Song 2", phone.savedTracks.value.single { it.canonicalId == "spotify:2" }.title)
        assertEquals(setOf<String?>("account-a"), reply.spotifyOps().mapTo(HashSet()) { it.spotifyAccountId })
    }

    @Test
    fun differentAccountHeartsStayOnTheirDevice() = runTest {
        val phone = device("phone", "account-a")
        val pc = device("pc", "account-b")
        phone.toggleFavorite(spotifyTrack("1"))
        pc.toggleFavorite(spotifyTrack("2"))
        pc.toggleFavorite(ytTrack("yt:shared"))

        val reply = pc.mergeAndPersistSyncState(phone.exportHeartsSyncDocument()).forPeerSpotifyAccount("account-a")
        phone.mergeAndPersistSyncState(reply)

        assertEquals(setOf("spotify:2"), pc.spotifyHearts())
        assertEquals(setOf("spotify:1"), phone.spotifyHearts())
        assertEquals(emptyList<Any>(), reply.spotifyOps())
        assertEquals(emptyList<Any>(), reply.favoritesMetadata.filter { it.canonicalId.startsWith("spotify:") })
        assertEquals(true, phone.isFavorite("yt:shared"))
    }

    @Test
    fun signedOutPeerNeitherSendsNorReceivesSpotifyHearts() = runTest {
        val phone = device("phone", "account-a")
        val pc = device("pc", null)
        phone.toggleFavorite(spotifyTrack("1"))

        val reply = pc.mergeAndPersistSyncState(phone.exportHeartsSyncDocument())
        phone.mergeAndPersistSyncState(reply.forPeerSpotifyAccount("account-a"))

        assertEquals(emptySet<String>(), pc.spotifyHearts())
        assertEquals(emptyList<Any>(), reply.spotifyOps())
        assertEquals(emptyList<Any>(), phone.exportHeartsSyncDocument().forPeerSpotifyAccount(null).spotifyOps())
        assertEquals(setOf("spotify:1"), phone.spotifyHearts())
    }

    @Test
    fun unheartPropagatesAndOlderHeartCannotRevive() = runTest {
        val phone = device("phone", "account-a")
        val pc = device("pc", "account-a")
        phone.toggleFavorite(spotifyTrack("1"))
        val staleHeart = phone.exportHeartsSyncDocument()
        pc.mergeAndPersistSyncState(staleHeart)
        assertEquals(setOf("spotify:1"), pc.spotifyHearts())

        now = 2_000
        phone.toggleFavorite(spotifyTrack("1"))
        val reply = pc.mergeAndPersistSyncState(phone.exportHeartsSyncDocument())
        assertEquals(emptySet<String>(), pc.spotifyHearts())
        assertEquals(HeartAction.UNFAVORITE, reply.spotifyOps().single().action)

        pc.mergeAndPersistSyncState(staleHeart)
        assertEquals(emptySet<String>(), pc.spotifyHearts())
    }

    @Test
    fun accountSwitchOnOneDeviceDeletesNothingOnThePeer() = runTest {
        val phone = device("phone", "account-a")
        val pc = device("pc", "account-a")
        phone.toggleFavorite(spotifyTrack("1"))
        pc.mergeAndPersistSyncState(phone.exportHeartsSyncDocument())

        phone.setSpotifyAccountId("account-b")
        val reply = pc.mergeAndPersistSyncState(phone.exportHeartsSyncDocument())
        assertEquals(setOf("spotify:1"), pc.spotifyHearts())
        assertEquals(emptySet<String>(), phone.spotifyHearts())

        // Switching back restores the hearts from the peer that kept them.
        phone.setSpotifyAccountId("account-a")
        phone.mergeAndPersistSyncState(pc.exportHeartsSyncDocument().forPeerSpotifyAccount("account-a"))
        assertEquals(setOf("spotify:1"), phone.spotifyHearts())
        assertEquals(emptyList<Any>(), reply.forPeerSpotifyAccount("account-b").spotifyOps())
    }

    @Test
    fun opStampedWithAnotherAccountIsRejected() = runTest {
        val pc = device("pc", "account-a")
        pc.mergeAndPersistSyncState(HeartsSyncDocument("phone", "account-a",
            ops = listOf(HeartOp("spotify:9", HeartAction.FAVORITE, 5_000, "phone", spotifyAccountId = "account-b"))))
        assertEquals(emptySet<String>(), pc.spotifyHearts())
    }

    @Test
    fun v1DowngradeOmitsSpotifyHearts() = runTest {
        val phone = device("phone", "account-a")
        phone.toggleFavorite(spotifyTrack("1"))
        phone.toggleFavorite(ytTrack("yt:v"))

        val v1 = phone.exportHeartsSyncDocument().forV1Sync()

        assertEquals(listOf("yt:v"), v1.ops.map { it.canonicalId })
        assertEquals(listOf("yt:v"), v1.favoritesMetadata.map { it.canonicalId })
    }
}

private fun spotifyTrack(id: String) = Track(
    canonicalId = "spotify:$id",
    title = "Song $id",
    artists = listOf(ArtistRef("a", "A")),
    durationMs = 1_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.SPOTIFY,
            providerTrackId = id,
            streamUrl = null,
            isPlayable = true,
            handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, id, 1_000),
        ),
    ),
)

private fun ytTrack(id: String) = Track(
    canonicalId = id,
    title = id,
    artists = listOf(ArtistRef("a", "A")),
    durationMs = 1_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.YOUTUBE_MUSIC,
            providerTrackId = id.removePrefix("yt:"),
            streamUrl = null,
            isPlayable = true,
            handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, id.removePrefix("yt:"), 1_000),
        ),
    ),
)
