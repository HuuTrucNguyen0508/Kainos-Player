package com.universalmusic.player.data.cache

import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.DownloadedYouTubeAudio
import com.universalmusic.player.platform.YouTubeAudioDownloader
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HeartedAudioCacheRegressionTest {
    private fun entry(file: File, owner: String, video: String = "same-video") = HeartedAudioCacheEntry(
        owner, video, file.toURI().toASCIIString(), sizeBytes = file.length(), downloadedAtMs = 1,
    )

    private fun TestScope.invalidatedDownload(action: suspend (HeartedAudioCacheService, Track) -> Unit,
                                            replacement: String? = null) {
        val root = createTempDirectory("cache-invalidation").toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val callbacks = mutableListOf<String>()
            val attempted = mutableListOf<String>()
            val downloader = object : YouTubeAudioDownloader {
                override fun isAvailable() = true
                override suspend fun downloadAudio(videoId: String, destinationDirectory: String, fileBaseName: String,
                    onProgress: ((Long, Long?) -> Unit)?): DownloadedYouTubeAudio {
                    attempted += videoId
                    if (attempted.size == 1) {
                        started.complete(Unit)
                        // Deliberately emulate a transport that does not cooperate with cancellation.
                        withContext(NonCancellable) { release.await() }
                    }
                    val file = File(destinationDirectory, "$fileBaseName.m4a").apply { parentFile.mkdirs(); writeBytes(ByteArray(8)) }
                    onProgress?.invoke(8, 8)
                    return DownloadedYouTubeAudio(file.absolutePath, sizeBytes = 8)
                }
            }
            val service = HeartedAudioCacheService(backgroundScope, cache, downloader, { emptyList() },
                onCached = { _, e -> callbacks += e.youtubeVideoId })
            val track = Track(canonicalId = "yt:original", title = "Song", artists = emptyList())
            service.enqueue(track)
            runCurrent()
            assertTrue(started.isCompleted)
            backgroundScope.launch { action(service, track) }
            runCurrent()
            release.complete(Unit)
            runCurrent()
            if (replacement == null) {
                assertEquals(emptyList(), callbacks)
                assertEquals(0, runBlocking { cache.stats().entryCount })
                assertEquals(0, File(root, "audio").listFiles().orEmpty().size)
                assertFalse(service.downloads.value.containsKey(track.canonicalId))
            } else {
                assertEquals(listOf("original", replacement), attempted)
                assertEquals(listOf(replacement), callbacks)
                assertEquals(replacement, runBlocking { cache.get(track.canonicalId) }?.youtubeVideoId)
                assertEquals(1, File(root, "audio").listFiles().orEmpty().size)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun clearInvalidatesSuspendedDownload() = runTest {
        invalidatedDownload({ service, _ -> service.clear() })
    }

    @Test fun unfavoriteInvalidatesSuspendedDownload() = runTest {
        invalidatedDownload({ service, track -> service.cancelAndRemove(track.canonicalId) })
    }

    @Test fun overrideReplacesSuspendedDownloadWithoutOldCallback() = runTest {
        invalidatedDownload({ service, track -> service.setYouTubeMatchOverride(track, "replacement") }, "replacement")
    }

    @Test fun clearingOverrideInvalidatesSuspendedDownload() = runTest {
        invalidatedDownload({ service, track -> service.clearYouTubeMatchOverride(track) }, "original")
    }

    @Test fun concurrentInitializationAndIndexMutationsPreserveEveryField() = runTest {
        val root = createTempDirectory("cache-transactions").toFile()
        try {
            val disk = FileHeartedAudioCacheDisk(root.toPath())
            val loading = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val saving = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            var loads = 0
            var saves = 0
            val gated = object : HeartedAudioCacheDisk by disk {
                override suspend fun loadIndex(): HeartedAudioCacheIndex {
                    loads++
                    loading.complete(Unit)
                    releaseLoad.await()
                    return disk.loadIndex()
                }
                override suspend fun saveIndex(index: HeartedAudioCacheIndex) {
                    if (++saves == 1) { saving.complete(Unit); releaseSave.await() }
                    disk.saveIndex(index)
                }
            }
            val cache = DefaultHeartedAudioCache(gated)
            val file = File(cache.audioDirectoryPath(), "entry.m4a").apply { writeBytes(ByteArray(8)) }
            val put = async { cache.put(entry(file, "owner")) }
            runCurrent()
            assertTrue(loading.isCompleted)
            val pin = async { cache.setPinned("owner", true) }
            val override = async { cache.setMatchOverride("owner", "corrected") }
            val failure = async { cache.recordFailure(HeartedCacheFailure("other", "failed", 2)) }
            runCurrent()
            assertEquals(1, loads)
            releaseLoad.complete(Unit)
            runCurrent()
            assertTrue(saving.isCompleted)
            assertEquals(1, saves)
            releaseSave.complete(Unit)
            awaitAll(put, pin, override, failure)
            val reloaded = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            assertNotNull(reloaded.get("owner"))
            assertTrue(reloaded.isPinned("owner"))
            assertEquals("corrected", reloaded.matchOverride("owner"))
            assertEquals("failed", reloaded.failure("other")?.message)
        } finally { root.deleteRecursively() }
    }

    @Test fun sameVideoDistinctFilesAreDeletedIndependently() = runBlocking {
        val root = createTempDirectory("cache-files").toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            val a = File(cache.audioDirectoryPath(), "a.m4a").apply { writeBytes(ByteArray(8)) }
            cache.put(entry(a, "youtube"))
            val b = File(cache.audioDirectoryPath(), "b.m4a").apply { writeBytes(ByteArray(8)) }
            cache.put(entry(b, "spotify"))
            cache.remove("youtube")
            assertFalse(a.exists())
            assertTrue(b.exists())
            cache.remove("spotify")
            assertFalse(b.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun normalizedSharedFileSurvivesUntilLastOwnerIsRemoved() = runBlocking {
        val root = createTempDirectory("cache-shared").toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            val file = File(cache.audioDirectoryPath(), "shared audio.m4a").apply { writeBytes(ByteArray(8)) }
            cache.put(entry(file, "first", "video1"))
            cache.put(entry(file, "second", "video2").copy(localUri = File(file.parentFile, "./${file.name}").path))
            cache.remove("first")
            assertTrue(file.exists())
            cache.remove("second")
            assertFalse(file.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectedDownloadRollsBackAndPreservesPinnedAudio() = runTest {
        val root = createTempDirectory("cache-rollback").toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()), maxBytes = 10)
            val pinned = File(cache.audioDirectoryPath(), "pinned.m4a").apply { writeBytes(ByteArray(8)) }
            cache.put(entry(pinned, "pinned"))
            cache.setPinned("pinned", true)
            val service = HeartedAudioCacheService(backgroundScope, cache, object : YouTubeAudioDownloader {
                override fun isAvailable() = true
                override suspend fun downloadAudio(videoId: String, destinationDirectory: String, fileBaseName: String,
                    onProgress: ((Long, Long?) -> Unit)?): DownloadedYouTubeAudio {
                    val file = File(destinationDirectory, "$fileBaseName.m4a").apply { writeBytes(ByteArray(8)) }
                    return DownloadedYouTubeAudio(file.path, sizeBytes = 8)
                }
            }, { emptyList() })
            service.enqueue(Track(canonicalId = "yt:new", title = "New", artists = emptyList()))
            runCurrent()
            assertNotNull(cache.failure("yt:new"))
            assertNull(cache.get("yt:new"))
            assertTrue(pinned.exists())
            assertEquals(8, cache.stats().bytesUsed)
            assertEquals(listOf("pinned.m4a"), File(root, "audio").listFiles().orEmpty().map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun restartReconcilesOrphansAndPartialsWithoutDeletingReferencedAudio() = runBlocking {
        val root = createTempDirectory("cache-reconcile").toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            val retained = File(cache.audioDirectoryPath(), "retained audio.m4a").apply { writeBytes(ByteArray(8)) }
            cache.put(entry(retained, "retained"))
            val orphan = File(retained.parentFile, "orphan.m4a").apply { writeBytes(ByteArray(8)) }
            val partial = File(retained.parentFile, "partial.m4a.part").apply { writeBytes(ByteArray(8)) }
            val reloaded = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            assertNotNull(reloaded.get("retained"))
            assertTrue(retained.exists())
            assertFalse(orphan.exists())
            assertFalse(partial.exists())
            assertEquals(8, reloaded.stats().bytesUsed)
        } finally { root.deleteRecursively() }
    }
}
