package com.universalmusic.player.data.cache

import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.domain.model.ProviderId
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataArtworkCacheTest {
    @Test
    fun putAndResolvePreferLocalArtwork() = runTest {
        val root = createTempDirectory("kainos-meta-cache")
        try {
            val disk = FileMetadataCacheDisk(root)
            val cache = DefaultMetadataArtworkCache(
                disk = disk,
                downloadArtwork = { ByteArray(32) { 1 } },
                ttlMs = 60_000,
            )
            val track = sampleTrack(artwork = "https://example.test/cover.jpg")
            val entry = cache.put(track, nowMs = 1_000)
            assertNotNull(entry.artworkLocalUri)
            assertTrue(disk.artworkExists(entry.artworkLocalUri!!))
            assertEquals(entry.artworkLocalUri, cache.resolveArtworkUrl(track.canonicalId, track.artworkUrl))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedRefreshPreservesPreviousEntry() = runTest {
        val root = createTempDirectory("kainos-meta-cache-preserve")
        try {
            val real = FileMetadataCacheDisk(root)
            var failWrites = false
            val disk = object : MetadataCacheDisk by real {
                override suspend fun saveIndex(index: MetadataCacheIndex) {
                    if (failWrites) error("disk full")
                    real.saveIndex(index)
                }
            }
            val cache = DefaultMetadataArtworkCache(
                disk = disk,
                downloadArtwork = { ByteArray(16) { 2 } },
            )
            val first = sampleTrack(title = "First", artwork = "https://example.test/a.jpg")
            cache.put(first, 1_000)
            failWrites = true
            cache.putPreservingOnFailure(
                sampleTrack(title = "Second", artwork = "https://example.test/b.jpg"),
                nowMs = 2_000,
            )
            assertEquals("First", cache.get(first.canonicalId)?.track?.title)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun clearRemovesIndexAndArtwork() = runTest {
        val root = createTempDirectory("kainos-meta-cache-clear")
        try {
            val disk = FileMetadataCacheDisk(root)
            val cache = DefaultMetadataArtworkCache(
                disk = disk,
                downloadArtwork = { ByteArray(8) { 3 } },
            )
            cache.put(sampleTrack(artwork = "https://example.test/c.jpg"), 1_000)
            assertTrue(cache.stats().entryCount >= 1)
            cache.clear()
            assertEquals(0, cache.stats().entryCount)
            assertEquals(0, cache.stats().artworkFileCount)
            assertNull(cache.get("yt:vid"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileStoreRoundTripLibrarySnapshot() = runTest {
        val dir = createTempDirectory("kainos-user-library")
        try {
            val path = dir.resolve("user-library.json")
            val store = com.universalmusic.player.data.library.FileUserLibraryStore(path)
            val snapshot = com.universalmusic.player.data.library.UserLibrarySnapshot(
                favoriteIds = listOf("yt:1"),
                remembered = listOf(sampleTrack()),
            )
            store.write(snapshot)
            assertTrue(Files.exists(path))
            assertEquals(listOf("yt:1"), store.read().favoriteIds)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun resolveArtworkFallsBackToRemoteWhenLocalMissing() = runTest {
        val root = createTempDirectory("kainos-meta-cache-stale-local")
        try {
            val disk = FileMetadataCacheDisk(root)
            val cache = DefaultMetadataArtworkCache(
                disk = disk,
                downloadArtwork = { ByteArray(8) { 4 } },
                ttlMs = 60_000,
            )
            val remote = "https://example.test/fresh.jpg"
            val track = sampleTrack(artwork = remote)
            val entry = cache.put(track, nowMs = 1_000)
            val local = checkNotNull(entry.artworkLocalUri)
            disk.deleteArtwork(track.canonicalId)
            assertTrue(!disk.artworkExists(local))
            assertEquals(remote, cache.resolveArtworkUrl(track.canonicalId, remote))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun evictExpiredRemovesStaleEntries() = runTest {
        val root = createTempDirectory("kainos-meta-cache-ttl")
        try {
            val disk = FileMetadataCacheDisk(root)
            val cache = DefaultMetadataArtworkCache(
                disk = disk,
                downloadArtwork = { null },
                ttlMs = 100,
            )
            cache.put(sampleTrack(artwork = null), nowMs = 0)
            cache.evictExpired(nowMs = 1_000)
            assertNull(cache.get("yt:vid"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private fun sampleTrack(
    title: String = "Vid",
    artwork: String? = "https://example.test/x.jpg",
) = PersistedTrack(
    canonicalId = "yt:vid",
    title = title,
    artists = listOf(PersistedArtist("a", "Artist")),
    artworkUrl = artwork,
    sources = listOf(PersistedSource(ProviderId.YOUTUBE_MUSIC.name, "vid")),
    cachedAtMs = 0,
)
