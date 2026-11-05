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
    @Test fun quotaPausesQueueWithoutRecordingNoMatch() = runTest {
        val root = createTempDirectory().toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            val service = HeartedAudioCacheService(backgroundScope, cache, object : YouTubeAudioDownloader {
                override fun isAvailable() = true
                override suspend fun downloadAudio(videoId: String, destinationDirectory: String, fileBaseName: String,
                    onProgress: ((Long, Long?) -> Unit)?): DownloadedYouTubeAudio? = null
            }, { throw IOException("HTTP 403 quotaExceeded") }, networkAvailable = { true })
            service.enqueue(Track("spotify:a", "A", emptyList()))
            runCurrent()
            assertEquals(DownloadPhase.WAITING_FOR_NETWORK, service.downloads.value["spotify:a"]?.phase)
            assertNull(cache.failure("spotify:a"))
        } finally { root.deleteRecursively() }
    }
    @Test fun networkDropResumesOnConnectivityNotificationAndEvictionNotifiesLibrary() = runTest {
        val root = createTempDirectory().toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()), maxBytes = 10)
            var attempts = 0
            val removed = mutableListOf<String>()
            val service = HeartedAudioCacheService(backgroundScope, cache, object : YouTubeAudioDownloader {
                override fun isAvailable() = true
                override suspend fun downloadAudio(videoId: String, destinationDirectory: String, fileBaseName: String,
                    onProgress: ((Long, Long?) -> Unit)?): DownloadedYouTubeAudio {
                    if (++attempts == 1) throw IOException("connection reset")
                    val file = File(destinationDirectory, "$fileBaseName.m4a").apply { writeBytes(ByteArray(8)) }
                    return DownloadedYouTubeAudio(file.absolutePath, sizeBytes = 8)
                }
            }, { emptyList() }, onRemoved = { removed += it }, networkAvailable = { true })
            service.enqueue(Track("yt:a", "A", emptyList())); runCurrent()
            assertEquals(DownloadPhase.WAITING_FOR_NETWORK, service.downloads.value["yt:a"]?.phase)
            service.notifyNetworkChanged(); runCurrent()
            assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:a"]?.phase)
            service.enqueue(Track("yt:b", "B", emptyList())); runCurrent()
            assertEquals(listOf("yt:a"), removed)
            assertNull(cache.get("yt:a"))
            assertNotNull(cache.get("yt:b"))
        } finally { root.deleteRecursively() }
    }
    @Test fun serviceOutageWaitsButRemovedVideoFails() = runTest {
        val root = createTempDirectory().toFile()
        try {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))
            var message = "503 Service unavailable"
            val service = HeartedAudioCacheService(backgroundScope, cache, object : YouTubeAudioDownloader {
                override fun isAvailable() = true
                override suspend fun downloadAudio(videoId: String, destinationDirectory: String, fileBaseName: String,
                    onProgress: ((Long, Long?) -> Unit)?): DownloadedYouTubeAudio? = throw IOException(message)
            }, { emptyList() }, networkAvailable = { true })
            service.enqueue(Track("yt:a", "A", emptyList())); runCurrent()
            assertEquals(DownloadPhase.WAITING_FOR_NETWORK, service.downloads.value["yt:a"]?.phase)
            message = "404 Video unavailable"
            service.notifyNetworkChanged(); runCurrent()
            assertEquals(DownloadPhase.FAILED, service.downloads.value["yt:a"]?.phase)
            assertEquals(message, cache.failure("yt:a")?.message)
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
            var attempts = 0
            val reloaded = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()), maxBytes = 10)
            val service = HeartedAudioCacheService(backgroundScope, reloaded, object : YouTubeAudioDownloader {
                override fun isAvailable() = true
                override suspend fun downloadAudio(videoId: String, destinationDirectory: String, fileBaseName: String,
                    onProgress: ((Long, Long?) -> Unit)?): DownloadedYouTubeAudio? { attempts++; return null }
            }, { emptyList() })
            val stale = Track("yt:a", "A", emptyList()).withHeartedAudioCache(
                HeartedAudioCacheEntry("yt:a", "a", File(root,"deleted").toURI().toString()))
            assertTrue(service.applyCacheToTrack(stale).sources.none { it.providerTrackId.startsWith("hearted-yt:") })
            service.enqueueMissing(listOf(stale)); runCurrent()
            assertEquals(0, attempts)
        } finally { root.deleteRecursively() }
    }
}
