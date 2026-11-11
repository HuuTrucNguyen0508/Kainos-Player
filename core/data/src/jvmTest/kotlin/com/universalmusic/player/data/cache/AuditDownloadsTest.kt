package com.universalmusic.player.data.cache

import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.*
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuditDownloadsTest {
    @Test fun quotaBacksOffWithoutRecordingNoMatchOrClaimingNoNetwork() = runTest {
        val root = createTempDirectory().toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            val service = HeartedAudioCacheService(backgroundScope, cache, FakeDownloader { _, _ -> null },
                { throw IOException("HTTP 403 quotaExceeded") }, clock = { currentTime }, networkAvailable = { true })
            service.enqueue(Track("spotify:a", "A", emptyList()))
            runCurrent()
            val item = assertNotNull(service.downloads.value["spotify:a"])
            assertEquals(DownloadPhase.QUEUED, item.phase)
            assertEquals("HTTP 403 quotaExceeded", item.errorMessage)
            assertNotNull(item.retryAtMs)
            assertNull(cache.failure("spotify:a"))
        } finally { root.deleteRecursively() }
    }

    @Test fun droppedConnectionRetriesAfterBackoffAndEvictionNotifiesLibrary() = runTest {
        val root = createTempDirectory().toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()), maxBytes = 10)
            val removed = mutableListOf<String>()
            val downloader = FakeDownloader { id, attempt ->
                if (id == "a" && attempt == 1) YouTubeDownloadResult.Failed(YouTubeFailureKind.TRANSIENT, "connection reset") else null
            }
            val service = HeartedAudioCacheService(backgroundScope, cache, downloader,
                { emptyList() }, onRemoved = { removed += it }, networkAvailable = { true })
            service.enqueue(Track("yt:a", "A", emptyList())); runCurrent()
            assertEquals(DownloadPhase.QUEUED, service.downloads.value["yt:a"]?.phase)
            advanceTimeBy(transientBackoffMs(0) + 1); runCurrent()
            assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:a"]?.phase)
            service.enqueue(Track("yt:b", "B", emptyList())); runCurrent()
            assertEquals(listOf("yt:a"), removed)
            assertNull(cache.get("yt:a"))
            assertNotNull(cache.get("yt:b"))
        } finally { root.deleteRecursively() }
    }

    @Test fun serviceOutageRetriesButRemovedVideoFailsWithItsReason() = runTest {
        val root = createTempDirectory().toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            var message = "ERROR: Unable to download webpage: HTTP Error 503: Service Unavailable"
            val service = HeartedAudioCacheService(backgroundScope, cache, FakeDownloader { _, _ ->
                YouTubeDownloadResult.Failed(classifyYouTubeFailure(message), message)
            }, { emptyList() }, networkAvailable = { true })
            service.enqueue(Track("yt:a", "A", emptyList())); runCurrent()
            assertEquals(DownloadPhase.QUEUED, service.downloads.value["yt:a"]?.phase)
            message = "ERROR: [youtube] a: Video unavailable"
            advanceTimeBy(transientBackoffMs(0) + 1); runCurrent()
            assertEquals(DownloadPhase.FAILED, service.downloads.value["yt:a"]?.phase)
            assertEquals(message, cache.failure("yt:a")?.message)
            assertEquals(YouTubeFailureKind.PERMANENT.name, cache.failure("yt:a")?.kind)
        } finally { root.deleteRecursively() }
    }

    @Test fun budgetEvictionDoesNotRequeueOnRestart() = runTest {
        val root = createTempDirectory().toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()), maxBytes = 10)
            suspend fun put(id: String) {
                val file = File(cache.audioDirectoryPath(), "$id.m4a").apply { writeBytes(ByteArray(8)) }
                cache.put(HeartedAudioCacheEntry("yt:$id", id, file.toURI().toString(), sizeBytes = 8))
            }
            put("a"); put("b")
            val reloaded = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()), maxBytes = 10)
            val downloader = FakeDownloader { _, _ -> YouTubeDownloadResult.Failed(YouTubeFailureKind.PERMANENT, "no") }
            val service = HeartedAudioCacheService(backgroundScope, reloaded, downloader, { emptyList() })
            val stale = Track("yt:a", "A", emptyList()).withHeartedAudioCache(
                HeartedAudioCacheEntry("yt:a", "a", File(root,"deleted").toURI().toString()))
            assertTrue(service.applyCacheToTrack(stale).sources.none { it.providerTrackId.startsWith("hearted-yt:") })
            service.enqueueMissing(listOf(stale)); runCurrent()
            assertEquals(0, downloader.calls.size)
        } finally { root.deleteRecursively() }
    }
}

/** Writes an 8-byte file unless [fail] returns a failure for this video's [attempt] (1-based). */
internal class FakeDownloader(
    private val fail: (videoId: String, attempt: Int) -> YouTubeDownloadResult.Failed?,
) : YouTubeAudioDownloader {
    val calls = mutableListOf<String>()
    override fun isAvailable() = true
    override suspend fun downloadAudio(videoId: String, destinationDirectory: String, fileBaseName: String,
        onProgress: ((Long, Long?) -> Unit)?): YouTubeDownloadResult {
        calls += videoId
        fail(videoId, calls.count { it == videoId })?.let { return it }
        val file = File(destinationDirectory, "$fileBaseName.m4a").apply { parentFile.mkdirs(); writeBytes(ByteArray(8)) }
        return YouTubeDownloadResult.Downloaded(DownloadedYouTubeAudio(file.absolutePath, sizeBytes = 8))
    }
}
