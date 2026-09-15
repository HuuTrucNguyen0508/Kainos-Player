package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.UserLibrarySnapshot
import com.universalmusic.player.data.library.migrated
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
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
class HeartSyncMergeTest {
    @Test
    fun higherRevisionWinsIncludingUnheart() {
        val local = listOf(
            HeartOp("yt:1", HeartAction.FAVORITE, revision = 10, deviceId = "phone"),
        )
        val remote = listOf(
            HeartOp("yt:1", HeartAction.UNFAVORITE, revision = 20, deviceId = "pc"),
        )
        val merged = mergeHeartOps(local, remote, localSpotifyAccountId = null, remoteSpotifyAccountId = null)
        assertEquals(HeartAction.UNFAVORITE, merged.single().action)
        assertTrue(merged.favoriteIdsFromOps().isEmpty())
    }

    @Test
    fun mismatchedSpotifyAccountDropsRemoteSpotifyOpsButKeepsYouTube() {
        val local = listOf(
            HeartOp("spotify:a", HeartAction.FAVORITE, 5, "phone", spotifyAccountId = "user-a"),
            HeartOp("yt:1", HeartAction.FAVORITE, 5, "phone"),
        )
        val remote = listOf(
            HeartOp("spotify:b", HeartAction.FAVORITE, 99, "pc", spotifyAccountId = "user-b"),
            HeartOp("yt:2", HeartAction.FAVORITE, 99, "pc"),
        )
        val merged = mergeHeartOps(
            local,
            remote,
            localSpotifyAccountId = "user-a",
            remoteSpotifyAccountId = "user-b",
        )
        assertTrue(merged.any { it.canonicalId == "spotify:a" })
        assertFalse(merged.any { it.canonicalId == "spotify:b" })
        assertTrue(merged.any { it.canonicalId == "yt:2" })
    }

    @Test
    fun portableMetadataStripsLocalLocationsAndNonHttpArt() {
        val track = PersistedTrack(
            canonicalId = "yt:abc",
            title = "Song",
            artists = listOf(PersistedArtist("a", "A")),
            artworkUrl = "file:///cache/art.jpg",
            sources = listOf(
                PersistedSource(
                    provider = ProviderId.YOUTUBE_MUSIC.name,
                    providerTrackId = "abc",
                    localLocation = "file:///audio-cache/x.m4a",
                ),
            ),
        )
        val portable = track.forHeartsSync()
        assertNull(portable!!.artworkUrl)
        assertNull(portable.sources.single().localLocation)
    }

    @Test
    fun mergeAndPersistFiresFavoriteDeltasAndPersists() = runTest {
        val store = InMemoryUserLibraryStore()
        val deltas = mutableListOf<Pair<String, Boolean>>()
        val library = LibraryRepository(
            scope = this,
            store = store,
            clock = { 1_000L },
            deviceIdProvider = { "pc" },
            onFavoriteChanged = { track, fav -> deltas += track.canonicalId to fav },
        )
        library.applySnapshot(UserLibrarySnapshot(spotifyAccountId = null))
        library.toggleFavorite(ytTrack("yt:keep"))
        advanceUntilIdle()
        deltas.clear()

        val remote = HeartsSyncDocument(
            deviceId = "phone",
            spotifyAccountId = null,
            ops = listOf(
                HeartOp("yt:new", HeartAction.FAVORITE, revision = 2_000L, deviceId = "phone"),
                HeartOp("yt:keep", HeartAction.UNFAVORITE, revision = 2_000L, deviceId = "phone"),
            ),
            favoritesMetadata = listOf(
                PersistedTrack(
                    canonicalId = "yt:new",
                    title = "New",
                    artists = listOf(PersistedArtist("a", "A")),
                    sources = listOf(PersistedSource(ProviderId.YOUTUBE_MUSIC.name, "new")),
                ),
            ),
        )
        library.mergeAndPersistSyncState(remote)
        assertTrue(library.isFavorite("yt:new"))
        assertFalse(library.isFavorite("yt:keep"))
        assertTrue(deltas.any { it == "yt:new" to true })
        assertTrue(deltas.any { it == "yt:keep" to false })
        assertTrue("yt:new" in store.read().favoriteIds)
        assertTrue(store.read().heartOps.any { it.canonicalId == "yt:new" && it.action == HeartAction.FAVORITE })
    }

    @Test
    fun v1SnapshotMigratesFavoriteIdsIntoHeartOps() {
        val migrated = UserLibrarySnapshot(
            version = 1,
            favoriteIds = listOf("yt:1", "spotify:2"),
            spotifyAccountId = "user-a",
        ).migrated(deviceId = "pc")
        assertEquals(2, migrated.version)
        assertEquals(2, migrated.heartOps.size)
        assertTrue(migrated.heartOps.all { it.action == HeartAction.FAVORITE })
    }
}

class VaultUnionTest {
    @Test
    fun copiesMissingBothWaysWithoutInferringDeletes() {
        val local = VaultIndexDocument(
            deviceId = "pc",
            entries = listOf(VaultFileEntry("a.flac", 10, 1)),
        )
        val remote = VaultIndexDocument(
            deviceId = "phone",
            entries = listOf(VaultFileEntry("b.flac", 20, 2)),
        )
        val plan = planVaultUnion(local, remote)
        assertEquals(listOf("b.flac"), plan.copyToLocal.map { it.relPath })
        assertEquals(listOf("a.flac"), plan.copyToRemote.map { it.relPath })
        assertTrue(plan.applyTombstones.isEmpty())
    }

    @Test
    fun tombstoneRemovesWithoutPresenceOnPeer() {
        val local = VaultIndexDocument(
            deviceId = "pc",
            entries = listOf(VaultFileEntry("gone.flac", 10, 1)),
            tombstones = emptyList(),
        )
        val remote = VaultIndexDocument(
            deviceId = "phone",
            entries = emptyList(),
            tombstones = listOf(VaultTombstone("gone.flac", deletedAtMs = 5, deviceId = "phone", revision = 5)),
        )
        val plan = planVaultUnion(local, remote)
        assertEquals(listOf("gone.flac"), plan.applyTombstones.map { it.relPath })
        assertTrue(plan.copyToRemote.isEmpty())
    }

    @Test
    fun hashMismatchIsConflictNotSilentOverwrite() {
        val local = VaultIndexDocument(
            deviceId = "pc",
            entries = listOf(VaultFileEntry("x.flac", 10, 1, contentHash = "aaa")),
        )
        val remote = VaultIndexDocument(
            deviceId = "phone",
            entries = listOf(VaultFileEntry("x.flac", 10, 1, contentHash = "bbb")),
        )
        val plan = planVaultUnion(local, remote)
        assertEquals(1, plan.conflicts.size)
        assertTrue(plan.copyToLocal.isEmpty())
        assertTrue(plan.copyToRemote.isEmpty())
    }

    @Test
    fun heartsOnlyFilterKeepsMatchingBasenamesAndTombstones() {
        val index = VaultIndexDocument(
            deviceId = "pc",
            entries = listOf(
                VaultFileEntry("albums/love.flac", 10, 1),
                VaultFileEntry("albums/skip.flac", 20, 2),
                VaultFileEntry("Love.FLAC", 30, 3),
            ),
            tombstones = listOf(
                VaultTombstone("albums/gone.flac", deletedAtMs = 1, deviceId = "pc", revision = 1),
            ),
        )
        val filtered = index.applyHeartsOnlyVaultFilter(
            heartsOnly = true,
            heartedBasenamesLower = setOf("love.flac"),
        )
        assertEquals(listOf("albums/love.flac", "Love.FLAC"), filtered.entries.map { it.relPath })
        assertEquals(1, filtered.tombstones.size)
        assertEquals(index, index.applyHeartsOnlyVaultFilter(false, emptySet()))
    }
}

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

private class InMemoryUserLibraryStore : com.universalmusic.player.data.library.UserLibraryStore {
    private var snapshot = UserLibrarySnapshot()
    override suspend fun read(): UserLibrarySnapshot = snapshot
    override suspend fun write(snapshot: UserLibrarySnapshot) {
        this.snapshot = snapshot
    }
}
