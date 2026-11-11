package com.universalmusic.player.data.cache

import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.YouTubeDownloadResult
import com.universalmusic.player.platform.YouTubeFailureKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.*

/** The download queue keeps moving, waits only for real reasons, and remembers what the user removed. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadQueueResilienceTest {
    private lateinit var root: File

    @BeforeTest fun setUp() { root = createTempDirectory("download-queue").toFile() }
    @AfterTest fun tearDown() { root.deleteRecursively() }

    private fun cache() = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(root.toPath()))

    private fun TestScope.service(
        cache: HeartedAudioCache,
        downloader: FakeDownloader,
        online: () -> Boolean = { true },
        metered: () -> Boolean = { false },
        wifiOnly: () -> Boolean = { false },
        scope: CoroutineScope = backgroundScope,
    ) = HeartedAudioCacheService(scope, cache, downloader, { emptyList() }, clock = { currentTime },
        networkAvailable = online, meteredNetwork = metered, wifiOnly = wifiOnly)

    private fun yt(id: String) = Track("yt:$id", id.uppercase(), emptyList())


    @Test fun incomingCopyDoesNotResurrectAnExcludedHeart() = runTest {
        val cache = cache()
        val service = service(cache, FakeDownloader { _, _ -> null })
        service.enqueueMissing(listOf(yt("a"))); runCurrent()
        service.removeAndKeepOff("yt:a"); runCurrent()
        val token = service.copyGeneration("yt:a")
        val file = File(cache.audioDirectoryPath(), "copy.webm").apply { writeBytes(ByteArray(8)) }
        val entry = HeartedAudioCacheEntry("yt:a", "a", file.toURI().toString(), sizeBytes = 8)
        assertEquals(HeartedAudioCacheService.CopyAdoption.STALE, service.adoptCopiedFile(entry, token))
        assertNull(cache.get("yt:a"))
        assertTrue(cache.isUserExcluded("yt:a"))
    }

    @Test fun meteredSwitchCancelsAnActiveDownloadAndResumesWithoutBurningATry() = runTest {
        var metered = false
        var cancelled = false
        val calls = mutableListOf<String>()
        val downloader = object : com.universalmusic.player.platform.YouTubeAudioDownloader {
            override fun isAvailable() = true
            override suspend fun downloadAudio(videoId: String, destinationDirectory: String,
                fileBaseName: String, onProgress: ((Long, Long?) -> Unit)?): YouTubeDownloadResult {
                calls += videoId
                try { kotlinx.coroutines.awaitCancellation() }
                finally { cancelled = true }
            }
        }
        val service = HeartedAudioCacheService(backgroundScope, cache(), downloader, { emptyList() },
            networkAvailable = { true }, meteredNetwork = { metered }, wifiOnly = { true })
        service.enqueue(yt("a")); runCurrent()
        metered = true
        service.notifyNetworkChanged(); runCurrent()
        assertTrue(cancelled, "mobile data must not finish a Wi-Fi-only download")
        assertEquals(DownloadPhase.WAITING_FOR_WIFI, service.downloads.value["yt:a"]?.phase)
        metered = false
        service.notifyNetworkChanged(); runCurrent()
        assertEquals(listOf("a", "a"), calls)
    }

    @Test fun backoffShowsOfflineWhileConnectionIsLost() = runTest {
        var online = true
        val downloader = FakeDownloader { _, _ ->
            YouTubeDownloadResult.Failed(YouTubeFailureKind.TRANSIENT, "HTTP 503")
        }
        val service = service(cache(), downloader, online = { online })
        service.enqueue(yt("a")); runCurrent()
        online = false
        service.notifyNetworkChanged(); runCurrent()
        assertEquals(DownloadPhase.WAITING_FOR_NETWORK, service.downloads.value["yt:a"]?.phase)
        service.removeAndKeepOff("yt:a"); runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(listOf("a"), downloader.calls)
    }


    @Test fun extractorFailureIsNotRetriedOnEveryHeartListRefresh() = runTest {
        val downloader = FakeDownloader { _, _ ->
            YouTubeDownloadResult.Failed(YouTubeFailureKind.OUTDATED_EXTRACTOR, "signature extraction failed")
        }
        val service = service(cache(), downloader)
        service.enqueueMissing(listOf(yt("a"))); runCurrent()
        repeat(3) { service.enqueueMissing(listOf(yt("a"))); runCurrent() }
        assertEquals(listOf("a"), downloader.calls)
    }

    @Test fun backoffCannotDownloadInBackgroundAndResumesOnReturn() = runTest {
        val downloader = FakeDownloader { _, attempt ->
            if (attempt == 1) YouTubeDownloadResult.Failed(YouTubeFailureKind.TRANSIENT, "HTTP 503") else null
        }
        val service = service(cache(), downloader)
        service.enqueue(yt("a")); runCurrent()
        service.setForeground(false)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("a"), downloader.calls)
        service.setForeground(true); runCurrent()
        assertEquals(listOf("a", "a"), downloader.calls)
        assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:a"]?.phase)
    }

    @Test fun clearAndRemoveDuringBackoffNeverRestoreTheirJobs() = runTest {
        val downloader = FakeDownloader { _, _ ->
            YouTubeDownloadResult.Failed(YouTubeFailureKind.TRANSIENT, "HTTP 503")
        }
        val service = service(cache(), downloader)
        service.enqueue(yt("a")); service.enqueue(yt("b")); runCurrent()
        service.removeAndKeepOff("yt:a"); runCurrent()
        service.clear(listOf("yt:a", "yt:b"))
        advanceTimeBy(600_000); runCurrent()
        assertEquals(listOf("a", "b"), downloader.calls)
        assertTrue(service.downloads.value.isEmpty())
    }

    @Test fun brokenVideoFailsWithItsReasonAndDoesNotBlockTheRest() = runTest {
        val cache = cache()
        val reason = "ERROR: [youtube] bad: Video unavailable. This video has been removed by the uploader"
        val downloader = FakeDownloader { id, _ ->
            if (id == "bad") YouTubeDownloadResult.Failed(YouTubeFailureKind.PERMANENT, reason) else null
        }
        val service = service(cache, downloader)
        service.enqueue(yt("bad"))
        service.enqueue(yt("good"))
        runCurrent()
        val bad = assertNotNull(service.downloads.value["yt:bad"])
        assertEquals(DownloadPhase.FAILED, bad.phase)
        assertEquals(reason, bad.errorMessage)
        assertEquals(reason, cache.failure("yt:bad")?.message)
        assertEquals("bad", cache.failure("yt:bad")?.youtubeVideoId)
        assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:good"]?.phase)
        assertEquals(listOf("bad", "good"), downloader.calls)
        assertTrue(service.snapshot.value.items.none { it.phase == DownloadPhase.WAITING_FOR_NETWORK })
    }

    @Test fun transientFailureGoesToTheBackWithBackoffWhileOthersDownload() = runTest {
        val cache = cache()
        val downloader = FakeDownloader { id, attempt ->
            if (id == "flaky" && attempt == 1) {
                YouTubeDownloadResult.Failed(YouTubeFailureKind.TRANSIENT, "HTTP Error 503: Service Unavailable")
            } else null
        }
        val service = service(cache, downloader)
        service.enqueue(yt("flaky"))
        service.enqueue(yt("next"))
        runCurrent()
        val flaky = assertNotNull(service.downloads.value["yt:flaky"])
        assertEquals(DownloadPhase.QUEUED, flaky.phase)
        assertEquals("HTTP Error 503: Service Unavailable", flaky.errorMessage)
        assertEquals(currentTime + transientBackoffMs(0), flaky.retryAtMs)
        assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:next"]?.phase)
        advanceTimeBy(transientBackoffMs(0) - 1); runCurrent()
        assertEquals(listOf("flaky", "next"), downloader.calls)
        advanceTimeBy(2); runCurrent()
        assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:flaky"]?.phase)
        assertNull(service.downloads.value["yt:flaky"]?.retryAtMs)
    }

    @Test fun transientFailuresGiveUpAfterABoundedNumberOfTries() = runTest {
        val cache = cache()
        val downloader = FakeDownloader { _, _ ->
            YouTubeDownloadResult.Failed(YouTubeFailureKind.TRANSIENT, "yt-dlp did not finish within 5 min.")
        }
        val service = service(cache, downloader)
        service.enqueue(yt("mix"))
        // advanceUntilIdle ignores backgroundScope, where the queue runs.
        advanceTimeBy((0 until MAX_TRANSIENT_ATTEMPTS).sumOf(::transientBackoffMs) + 1); runCurrent()
        assertEquals(MAX_TRANSIENT_ATTEMPTS, downloader.calls.size)
        val item = assertNotNull(service.downloads.value["yt:mix"])
        assertEquals(DownloadPhase.FAILED, item.phase)
        assertEquals("Gave up after $MAX_TRANSIENT_ATTEMPTS tries: yt-dlp did not finish within 5 min.", item.errorMessage)
        assertNotNull(cache.failure("yt:mix"))
    }

    @Test fun waitingForNetworkOnlyWhileOfflineAndNoAttemptIsSpent() = runTest {
        var online = false
        val downloader = FakeDownloader { _, _ -> null }
        val service = service(cache(), downloader, online = { online })
        service.enqueue(yt("a"))
        runCurrent()
        assertEquals(DownloadPhase.WAITING_FOR_NETWORK, service.downloads.value["yt:a"]?.phase)
        assertEquals(emptyList(), downloader.calls)
        online = true
        service.notifyNetworkChanged(); runCurrent()
        assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:a"]?.phase)
    }

    @Test fun connectionLostMidDownloadWaitsForNetworkWithoutSpendingATry() = runTest {
        var online = true
        val downloader = FakeDownloader { _, attempt ->
            if (attempt == 1) {
                online = false
                YouTubeDownloadResult.Failed(YouTubeFailureKind.PERMANENT, "ERROR: something unrecognised")
            } else null
        }
        val cache = cache()
        val service = service(cache, downloader, online = { online })
        service.enqueue(yt("a"))
        runCurrent()
        assertEquals(DownloadPhase.WAITING_FOR_NETWORK, service.downloads.value["yt:a"]?.phase)
        assertNull(cache.failure("yt:a"))
        online = true
        service.notifyNetworkChanged(); runCurrent()
        assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:a"]?.phase)
    }

    @Test fun wifiOnlyHoldsYouTubeAndSpotifyMirrorDownloadsOnMeteredNetworks() = runTest {
        var metered = true
        var wifiOnly = true
        val downloader = FakeDownloader { _, _ -> null }
        val cache = cache()
        val service = HeartedAudioCacheService(backgroundScope, cache, downloader,
            { listOf(Track("yt:mirror", "Song", listOf(ArtistRef("a", "Artist")))) }, networkAvailable = { true },
            meteredNetwork = { metered }, wifiOnly = { wifiOnly })
        service.enqueue(yt("a"))
        service.enqueue(Track("spotify:s", "Song", listOf(ArtistRef("a", "Artist"))))
        runCurrent()
        assertEquals(DownloadPhase.WAITING_FOR_WIFI, service.downloads.value["yt:a"]?.phase)
        assertEquals(DownloadPhase.WAITING_FOR_WIFI, service.downloads.value["spotify:s"]?.phase)
        assertEquals(emptyList(), downloader.calls)
        metered = false
        service.notifyNetworkChanged(); runCurrent()
        assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:a"]?.phase)
        assertEquals(DownloadPhase.CACHED, service.downloads.value["spotify:s"]?.phase)

        // With the switch off, a metered network downloads.
        metered = true
        wifiOnly = false
        service.enqueue(yt("b")); runCurrent()
        assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:b"]?.phase)
    }

    @Test fun removedHeartStaysOffAcrossRestartUntilDownloadIsTapped() = runTest {
        val first = FakeDownloader { _, _ -> null }
        val service = service(cache(), first)
        service.enqueueMissing(listOf(yt("a"))); runCurrent()
        assertNotNull(cache().get("yt:a"))
        service.removeAndKeepOff("yt:a"); runCurrent()

        // Next launch: a fresh service over the same index, given every hearted track.
        val downloader = FakeDownloader { _, _ -> null }
        val cache = cache()
        val restarted = service(cache, downloader)
        restarted.enqueueMissing(listOf(yt("a"))); runCurrent()
        assertEquals(emptyList(), downloader.calls)
        val row = restarted.snapshot.value.items.single()
        assertEquals("yt:a", row.ownerCanonicalId)
        assertEquals(DownloadPhase.NOT_DOWNLOADED, row.phase)

        restarted.download("yt:a"); runCurrent()
        assertEquals(listOf("a"), downloader.calls)
        assertEquals(DownloadPhase.CACHED, restarted.downloads.value["yt:a"]?.phase)
        assertEquals(emptySet(), cache.allUserExcluded())
    }

    @Test fun clearingTheCacheKeepsHeartedTracksOffUntilDownloaded() = runTest {
        val service = service(cache(), FakeDownloader { _, _ -> null })
        service.enqueueMissing(listOf(yt("a"), yt("b"))); runCurrent()
        service.clear(hearted = listOf("yt:a", "yt:b", "local:file"))
        runCurrent()

        val downloader = FakeDownloader { _, _ -> null }
        val cache = cache()
        assertEquals(setOf("yt:a", "yt:b"), cache.allUserExcluded())
        val restarted = service(cache, downloader)
        restarted.enqueueMissing(listOf(yt("a"), yt("b"))); runCurrent()
        assertEquals(emptyList(), downloader.calls)
        assertEquals(listOf(DownloadPhase.NOT_DOWNLOADED, DownloadPhase.NOT_DOWNLOADED),
            restarted.snapshot.value.items.map { it.phase })
    }

    @Test fun unheartForgetsARemovalSoALaterHeartDownloads() = runTest {
        val cache = cache()
        val service = service(cache, FakeDownloader { _, _ -> null })
        service.enqueue(yt("a")); runCurrent()
        service.removeAndKeepOff("yt:a"); runCurrent()
        assertEquals(setOf("yt:a"), cache.allUserExcluded())
        service.cancelAndRemove("yt:a"); runCurrent()
        assertEquals(emptySet(), cache.allUserExcluded())
        assertTrue(service.snapshot.value.items.isEmpty())
    }

    @Test fun indexWrittenBeforeRemovalsLoadsUnchanged() = runTest {
        val audio = File(root, "audio").apply { mkdirs() }
        val file = File(audio, "old.webm").apply { writeBytes(ByteArray(4)) }
        File(root, "index.json").writeText(
            """{"version":2,"entries":{"yt:old":{"ownerCanonicalId":"yt:old","youtubeVideoId":"old",""" +
                """"localUri":"${file.toURI().toASCIIString()}","sizeBytes":4}},""" +
                """"failures":{"yt:x":{"ownerCanonicalId":"yt:x","message":"m","failedAtMs":1}}}""",
        )
        val cache = cache()
        assertNotNull(cache.get("yt:old"))
        assertEquals(emptySet(), cache.allUserExcluded())
        assertNull(cache.failure("yt:x")?.kind)
    }

    @Test fun outdatedExtractorIsReportedOnceAndRetriedNextLaunch() = runTest {
        val reason = "ERROR: [youtube] a: Sign in to confirm you’re not a bot"
        val failing = FakeDownloader { _, _ -> YouTubeDownloadResult.Failed(YouTubeFailureKind.OUTDATED_EXTRACTOR, reason) }
        val service = service(cache(), failing)
        service.enqueue(yt("a"))
        service.enqueue(yt("b"))
        runCurrent()
        assertEquals(reason, service.snapshot.value.extractorIssue)
        assertEquals(listOf("a", "b"), failing.calls)
        assertEquals(DownloadPhase.FAILED, service.downloads.value["yt:a"]?.phase)

        // After yt-dlp or the app is updated, the next launch tries again by itself.
        val fixed = FakeDownloader { _, _ -> null }
        val restarted = service(cache(), fixed)
        restarted.enqueueMissing(listOf(yt("a"), yt("b"))); runCurrent()
        assertEquals(listOf("a", "b"), fixed.calls)
        assertNull(restarted.snapshot.value.extractorIssue)
        assertEquals(DownloadPhase.CACHED, restarted.downloads.value["yt:a"]?.phase)
    }

    @Test fun retryAfterAFailureClearsTheExtractorIssueOnSuccess() = runTest {
        var broken = true
        val downloader = FakeDownloader { _, _ ->
            if (broken) YouTubeDownloadResult.Failed(YouTubeFailureKind.OUTDATED_EXTRACTOR, "HTTP Error 403: Forbidden") else null
        }
        val service = service(cache(), downloader)
        service.enqueue(yt("a")); runCurrent()
        assertEquals("HTTP Error 403: Forbidden", service.snapshot.value.extractorIssue)
        broken = false
        service.retry("yt:a"); runCurrent()
        assertNull(service.snapshot.value.extractorIssue)
        assertEquals(DownloadPhase.CACHED, service.downloads.value["yt:a"]?.phase)
    }
}
