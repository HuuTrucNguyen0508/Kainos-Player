package com.universalmusic.player.data.cache

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HeartedAudioCacheIsolationTest {
    @Test
    fun copiedIndexRebasesAudioAndRemoveOnlyDeletesTheCopy() = runBlocking {
        val root = createTempDirectory("hearted-isolation")
        try {
            val originalRoot = root.resolve("original")
            val original = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(originalRoot))
            val file = originalRoot.resolve("audio/song with spaces.m4a")
            Files.createDirectories(file.parent)
            Files.write(file, byteArrayOf(1, 2, 3))
            original.put(HeartedAudioCacheEntry("yt:song", "song", file.toUri().toString(), sizeBytes = 3, downloadedAtMs = 1))
            val copyRoot = root.resolve("copy")
            originalRoot.toFile().copyRecursively(copyRoot.toFile())
            val copy = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(copyRoot))
            val copiedFile = copyRoot.resolve("audio/song with spaces.m4a")
            assertEquals(copiedFile.toUri().toString(), assertNotNull(copy.get("yt:song")).localUri)
            assertTrue(Files.exists(copiedFile), "Reconciliation must preserve the copied audio")
            copy.remove("yt:song")
            assertFalse(Files.exists(copiedFile))
            assertTrue(Files.exists(file), "Removing a copied entry must leave the original audio alone")
            assertNotNull(original.get("yt:song"))
            Unit
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun copiedIndexWithoutLocalAudioDoesNotReadTheOriginalFile() = runBlocking {
        val root = createTempDirectory("hearted-missing-copy")
        try {
            val originalRoot = root.resolve("original")
            val disk = FileHeartedAudioCacheDisk(originalRoot)
            val file = originalRoot.resolve("audio/song.m4a")
            Files.createDirectories(file.parent)
            Files.write(file, byteArrayOf(1))
            disk.saveIndex(HeartedAudioCacheIndex(entries = mapOf("yt:song" to
                HeartedAudioCacheEntry("yt:song", "song", file.toString(), sizeBytes = 1, downloadedAtMs = 1))))
            val copyRoot = root.resolve("copy")
            Files.createDirectories(copyRoot)
            Files.copy(originalRoot.resolve("index.json"), copyRoot.resolve("index.json"))
            val copy = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(copyRoot))
            assertNull(copy.get("yt:song"))
            copy.remove("yt:song")
            assertTrue(Files.exists(file))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun cacheKeepsSymlinkedAudioEntriesWithoutDeletingTheirTargets() = runBlocking {
        val root = createTempDirectory("hearted-symlink")
        try {
            val outside = root.resolve("outside/song.m4a")
            Files.createDirectories(outside.parent)
            Files.write(outside, byteArrayOf(1))
            val cacheRoot = root.resolve("cache")
            Files.createDirectories(cacheRoot)
            Files.createSymbolicLink(cacheRoot.resolve("audio"), outside.parent)
            val linkedFile = cacheRoot.resolve("audio/song.m4a")
            val disk = FileHeartedAudioCacheDisk(cacheRoot)
            disk.saveIndex(HeartedAudioCacheIndex(entries = mapOf("yt:song" to
                HeartedAudioCacheEntry("yt:song", "song", linkedFile.toUri().toString(), sizeBytes = 1, downloadedAtMs = 1))))
            val cache = DefaultHeartedAudioCache(disk)
            assertEquals(linkedFile.toUri().toString(), assertNotNull(cache.get("yt:song")).localUri)
            cache.remove("yt:song")
            assertTrue(Files.exists(outside))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun deleteRejectsOutsidePathsTraversalAndSiblingPrefixes() = runBlocking {
        val root = createTempDirectory("hearted-delete")
        try {
            val cacheRoot = root.resolve("cache")
            val outside = root.resolve("cache-other/song.m4a")
            Files.createDirectories(outside.parent)
            Files.createDirectories(cacheRoot)
            Files.write(outside, byteArrayOf(1))
            val disk = FileHeartedAudioCacheDisk(cacheRoot)
            disk.deleteFile(outside.toUri().toString())
            disk.deleteFile(cacheRoot.resolve("../cache-other/song.m4a").toString())
            assertTrue(Files.exists(outside))
            val target = outside.parent.resolve("child")
            Files.createDirectories(target)
            Files.createSymbolicLink(cacheRoot.resolve("escape"), target)
            disk.deleteFile(cacheRoot.resolve("escape/../song.m4a").toString())
            assertTrue(Files.exists(outside), "Delete must use the normalized path it validated")
        } finally { root.toFile().deleteRecursively() }
    }
}
