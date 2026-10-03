package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.USER_LIBRARY_FORMAT_VERSION
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
    fun localUnheartSurvivesExportRematchAndRestart() = runTest {
        var now = 100L
        val phone = LibraryRepository(clock = { now }, deviceIdProvider = { "phone" })
        val pc = LibraryRepository(clock = { now }, deviceIdProvider = { "pc" })
        val phoneTrack = Track("local:phone", "Track", emptyList(), sources = listOf(PlaybackSource(
            ProviderId.LOCAL, "phone", streamUrl = "file:///phone/song.flac", handle = PlaybackHandle.Url("file:///phone/song.flac"), isPlayable = true,
        )))
        val pcTrack = phoneTrack.copy(canonicalId = "local:pc", sources = listOf(PlaybackSource(
            ProviderId.LOCAL, "pc", streamUrl = "file:///pc/song.flac", handle = PlaybackHandle.Url("file:///pc/song.flac"), isPlayable = true,
        )))
        phone.toggleFavorite(phoneTrack)
        pc.mergeAndPersistSyncState(phone.exportHeartsSyncDocument())
        pc.rematchPortableLocalFileHearts(mapOf("song.flac" to pcTrack))
        assertTrue(pc.isFavorite(pcTrack.canonicalId))
        now = 200
        phone.toggleFavorite(phoneTrack)
        val unheart = phone.exportHeartsSyncDocument()
        assertEquals(HeartAction.UNFAVORITE, unheart.ops.single().action)
        pc.mergeAndPersistSyncState(unheart)
        pc.rematchPortableLocalFileHearts(mapOf("song.flac" to pcTrack))
        assertFalse(pc.isFavorite(pcTrack.canonicalId))
        val restarted = LibraryRepository(clock = { now }, deviceIdProvider = { "pc" })
        restarted.applySnapshot(pc.toSnapshot())
        assertEquals(HeartAction.UNFAVORITE, restarted.exportHeartsSyncDocument().ops.single().action)
        now = 300
        restarted.toggleFavorite(pcTrack)
        phone.mergeAndPersistSyncState(restarted.exportHeartsSyncDocument())
        phone.rematchPortableLocalFileHearts(mapOf("song.flac" to phoneTrack))
        assertTrue(phone.isFavorite(phoneTrack.canonicalId))
    }

    @Test
    fun olderPortableUnheartCannotOverrideNewerLocalHeart() = runTest {
        val library = LibraryRepository(clock = { 300 }, deviceIdProvider = { "pc" })
        val track = Track("local:pc", "Track", emptyList(), sources = listOf(PlaybackSource(
            ProviderId.LOCAL, "pc", streamUrl = "file:///song.flac", handle = PlaybackHandle.Url("file:///song.flac"), isPlayable = true,
        )))
        library.toggleFavorite(track)
        library.mergeAndPersistSyncState(HeartsSyncDocument("phone", null, ops = listOf(HeartOp("localfile:song.flac", HeartAction.UNFAVORITE, 200, "phone"))))
        library.rematchPortableLocalFileHearts(mapOf("song.flac" to track))
        assertTrue(library.isFavorite(track.canonicalId))
        assertEquals(HeartAction.FAVORITE, library.exportHeartsSyncDocument().ops.single().action)
    }

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
        assertEquals(USER_LIBRARY_FORMAT_VERSION, migrated.version)
        assertEquals(2, migrated.heartOps.size)
        assertTrue(migrated.heartOps.all { it.action == HeartAction.FAVORITE })
    }

    @Test
    fun basenameFromAndroidSafDocumentUriUsesFilenameOnly() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2Fstorage-2/" +
                "document/primary%3AMusic%2Fstorage-2%2FHOYO-MiX%20-%20pinKing.flac"
        assertEquals("hoyo-mix - pinking.flac", basenameFromLocalLocation(uri))
        assertEquals(
            "hoyo-mix - pinking.flac",
            normalizedLocalFileHeartBasename(
                "localfile:primary%3amusic%2fstorage-2%2fhoyo-mix%20-%20pinking.flac",
            ),
        )
        assertEquals(
            "love.flac",
            basenameFromLocalLocation("file:///home/music/Love.FLAC"),
        )
    }

    @Test
    fun androidSafLocalFavoriteExportsTrueFilename() = runTest {
        val library = LibraryRepository(
            scope = this,
            store = InMemoryUserLibraryStore(),
            clock = { 5_000L },
            deviceIdProvider = { "phone" },
        )
        library.applySnapshot(UserLibrarySnapshot(spotifyAccountId = null))
        val local = Track(
            canonicalId = "local:abc",
            title = "pinKing",
            artists = listOf(ArtistRef("a", "A")),
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.LOCAL,
                    providerTrackId = "abc",
                    isPlayable = true,
                    handle = PlaybackHandle.Url(
                        "content://com.android.externalstorage.documents/tree/primary%3AMusic%2Fstorage-2/" +
                            "document/primary%3AMusic%2Fstorage-2%2FHOYO-MiX%20-%20pinKing.flac",
                    ),
                ),
            ),
        )
        library.toggleFavorite(local)
        val doc = library.exportHeartsSyncDocument()
        assertEquals("localfile:hoyo-mix - pinking.flac", doc.ops.single().canonicalId)
        assertFalse(doc.ops.any { "primary" in it.canonicalId })
    }

    @Test
    fun rematchRepairsMangledSafLocalfileIds() = runTest {
        val library = LibraryRepository(
            scope = this,
            store = InMemoryUserLibraryStore(),
            clock = { 5_000L },
            deviceIdProvider = { "pc" },
        )
        library.applySnapshot(UserLibrarySnapshot(spotifyAccountId = null))
        library.mergeAndPersistSyncState(
            HeartsSyncDocument(
                deviceId = "phone",
                spotifyAccountId = null,
                ops = listOf(
                    HeartOp(
                        "localfile:primary%3amusic%2fstorage-2%2fhoyo-mix%20-%20pinking.flac",
                        HeartAction.FAVORITE,
                        9_000L,
                        "phone",
                    ),
                ),
                favoritesMetadata = emptyList(),
            ),
        )
        val pcTrack = Track(
            canonicalId = "local:pc-1",
            title = "pinKing",
            artists = listOf(ArtistRef("a", "A")),
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.LOCAL,
                    providerTrackId = "pc-1",
                    isPlayable = true,
                    handle = PlaybackHandle.Url("/home/music/HOYO-MiX - pinKing.flac"),
                ),
            ),
        )
        val n = library.rematchPortableLocalFileHearts(mapOf("hoyo-mix - pinking.flac" to pcTrack))
        assertEquals(1, n)
        assertTrue(library.isFavorite("local:pc-1"))
    }

    @Test
    fun localFileHeartsExportAsPortableBasenameOps() = runTest {
        val library = LibraryRepository(
            scope = this,
            store = InMemoryUserLibraryStore(),
            clock = { 5_000L },
            deviceIdProvider = { "phone" },
        )
        library.applySnapshot(UserLibrarySnapshot(spotifyAccountId = "user-a"))
        val local = Track(
            canonicalId = "local:abc",
            title = "Song",
            artists = listOf(ArtistRef("a", "A")),
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.LOCAL,
                    providerTrackId = "abc",
                    isPlayable = true,
                    handle = PlaybackHandle.Url("file:///music/Love.FLAC"),
                ),
            ),
        )
        library.toggleFavorite(local)
        val doc = library.exportHeartsSyncDocument()
        assertTrue(doc.ops.any { it.canonicalId == "localfile:love.flac" && it.action == HeartAction.FAVORITE })
        assertTrue(doc.favoritesMetadata.any { it.canonicalId == "localfile:love.flac" })
        assertFalse(doc.ops.any { it.canonicalId.startsWith("local:") })
    }

    @Test
    fun mergeAcceptsRemoteLocalFileHeartsAndRematchMapsToLocalId() = runTest {
        val library = LibraryRepository(
            scope = this,
            store = InMemoryUserLibraryStore(),
            clock = { 5_000L },
            deviceIdProvider = { "pc" },
        )
        library.applySnapshot(UserLibrarySnapshot(spotifyAccountId = "user-a"))
        library.mergeAndPersistSyncState(
            HeartsSyncDocument(
                deviceId = "phone",
                spotifyAccountId = "user-a",
                ops = listOf(
                    HeartOp("localfile:love.flac", HeartAction.FAVORITE, 9_000L, "phone"),
                ),
                favoritesMetadata = listOf(
                    PersistedTrack(
                        canonicalId = "localfile:love.flac",
                        title = "Love",
                        artists = listOf(PersistedArtist("a", "A")),
                        sources = listOf(PersistedSource(ProviderId.LOCAL.name, "love.flac")),
                    ),
                ),
            ),
        )
        assertTrue(library.isFavorite("localfile:love.flac"))

        val pcTrack = Track(
            canonicalId = "local:pc-1",
            title = "Love",
            artists = listOf(ArtistRef("a", "A")),
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.LOCAL,
                    providerTrackId = "pc-1",
                    isPlayable = true,
                    handle = PlaybackHandle.Url("/home/music/Love.FLAC"),
                ),
            ),
        )
        val n = library.rematchPortableLocalFileHearts(mapOf("love.flac" to pcTrack))
        assertEquals(1, n)
        assertTrue(library.isFavorite("local:pc-1"))
        assertFalse(library.isFavorite("localfile:love.flac"))
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
    fun sameSizeDifferentMtimeIsNotConflictWithoutHashes() {
        // SAF often reports whole-second mtimes; desktop keeps ms precision.
        val local = VaultIndexDocument(
            deviceId = "pc",
            entries = listOf(VaultFileEntry("love.flac", sizeBytes = 21_575_529, mtimeMs = 1_788_632_244_483)),
        )
        val remote = VaultIndexDocument(
            deviceId = "phone",
            entries = listOf(VaultFileEntry("love.flac", sizeBytes = 21_575_529, mtimeMs = 1_788_632_244_000)),
        )
        val plan = planVaultUnion(local, remote)
        assertTrue(plan.conflicts.isEmpty())
        assertTrue(plan.copyToLocal.isEmpty())
        assertTrue(plan.copyToRemote.isEmpty())
    }

    @Test
    fun sizeMismatchWithoutHashesIsConflict() {
        val local = VaultIndexDocument(
            deviceId = "pc",
            entries = listOf(VaultFileEntry("love.flac", sizeBytes = 100, mtimeMs = 1)),
        )
        val remote = VaultIndexDocument(
            deviceId = "phone",
            entries = listOf(VaultFileEntry("love.flac", sizeBytes = 99, mtimeMs = 1)),
        )
        val plan = planVaultUnion(local, remote)
        assertEquals(1, plan.conflicts.size)
        assertEquals("love.flac", plan.conflicts.single().relPath)
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
