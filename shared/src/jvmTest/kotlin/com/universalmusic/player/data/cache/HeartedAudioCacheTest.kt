package com.universalmusic.player.data.cache

import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.DownloadedYouTubeAudio
import com.universalmusic.player.platform.YouTubeAudioDownloader
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class HeartedAudioCacheTest {
    @Test
    fun storesAndHydratesLocalSourceWithoutStreamUrls() {
        val root = createTempDirectory("kainos-hearted-audio")
        try {
            val disk = FileHeartedAudioCacheDisk(root)
            val cache = DefaultHeartedAudioCache(disk)
            val audio = root.resolve("audio")
            Files.createDirectories(audio)
            val file = audio.resolve("track.m4a")
            Files.write(file, ByteArray(64) { 7 })
            val uri = file.toUri().toASCIIString()
            runBlocking {
                cache.put(
                    HeartedAudioCacheEntry(
                        ownerCanonicalId = "yt:abc",
                        youtubeVideoId = "abc",
                        localUri = uri,
                        qualityTier = QualityTier.HIGH.name,
                        sizeBytes = 64,
                        downloadedAtMs = 1,
                    ),
                )
                val entry = cache.get("yt:abc")
                assertNotNull(entry)
                val track = Track(
                    canonicalId = "yt:abc",
                    title = "Song",
                    artists = listOf(ArtistRef("a", "Artist")),
                    sources = listOf(
                        PlaybackSource(
                            provider = ProviderId.YOUTUBE_MUSIC,
                            providerTrackId = "abc",
                            isPlayable = true,
                            handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, "abc"),
                        ),
                    ),
                ).withHeartedAudioCache(entry)
                assertTrue(track.sources.any { it.provider == ProviderId.LOCAL && it.handle is PlaybackHandle.Url })
                assertNull(track.sources.first { it.provider == ProviderId.YOUTUBE_MUSIC }.streamUrl)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun serviceCachesYoutubeHeartsBeforeSpotify() {
        runBlocking {
            val root = createTempDirectory("kainos-hearted-queue")
            try {
                val disk = FileHeartedAudioCacheDisk(root)
                val cache = DefaultHeartedAudioCache(disk)
                val order = mutableListOf<String>()
                val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                val downloader = object : YouTubeAudioDownloader {
                    override fun isAvailable(): Boolean = true
                    override suspend fun downloadAudio(
                        videoId: String,
                        destinationDirectory: String,
                        fileBaseName: String,
                    ): DownloadedYouTubeAudio {
                        gate.await()
                        order += videoId
                        val dir = root.resolve("audio")
                        Files.createDirectories(dir)
                        val file = dir.resolve("$fileBaseName.m4a")
                        Files.write(file, byteArrayOf(1, 2, 3))
                        return DownloadedYouTubeAudio(
                            absolutePath = file.toAbsolutePath().toString(),
                            quality = AudioQuality(tier = QualityTier.HIGH, codec = "m4a"),
                            sizeBytes = 3,
                        )
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val service = HeartedAudioCacheService(
                    scope = scope,
                    cache = cache,
                    downloader = downloader,
                    youtubeSearch = {
                        listOf(
                            Track(
                                canonicalId = "yt:mirror",
                                title = "Song",
                                artists = listOf(ArtistRef("a", "Artist")),
                                sources = listOf(
                                    PlaybackSource(
                                        provider = ProviderId.YOUTUBE_MUSIC,
                                        providerTrackId = "mirror",
                                        isPlayable = true,
                                        handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, "mirror"),
                                    ),
                                ),
                            ),
                        )
                    },
                )
                val spotify = Track(
                    canonicalId = "spotify:1",
                    title = "Song",
                    artists = listOf(ArtistRef("a", "Artist")),
                    sources = listOf(
                        PlaybackSource(
                            provider = ProviderId.SPOTIFY,
                            providerTrackId = "1",
                            isPlayable = true,
                            handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, "1"),
                        ),
                    ),
                )
                val youtube = Track(
                    canonicalId = "yt:direct",
                    title = "Other",
                    artists = listOf(ArtistRef("a", "Artist")),
                    sources = listOf(
                        PlaybackSource(
                            provider = ProviderId.YOUTUBE_MUSIC,
                            providerTrackId = "direct",
                            isPlayable = true,
                            handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, "direct"),
                        ),
                    ),
                )
                service.enqueue(spotify)
                service.enqueue(youtube)
                delay(50)
                gate.complete(Unit)
                val deadline = System.currentTimeMillis() + 5_000
                while (order.size < 2 && System.currentTimeMillis() < deadline) {
                    delay(50)
                }
                assertEquals(listOf("direct", "mirror"), order)
                assertNotNull(cache.get("yt:direct"))
                assertNotNull(cache.get("spotify:1"))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
}
