package com.universalmusic.player.ui.screens

import com.universalmusic.player.platform.YouTubeDownloadResult
import com.universalmusic.player.platform.YouTubeFailureKind
import com.universalmusic.player.data.cache.DefaultHeartedAudioCache
import com.universalmusic.player.data.cache.FileHeartedAudioCacheDisk
import com.universalmusic.player.data.cache.HeartedAudioCacheEntry
import com.universalmusic.player.data.cache.HeartedAudioCacheService
import com.universalmusic.player.data.cache.offlineOnly
import com.universalmusic.player.data.cache.offlineQueue
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackSort
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import com.universalmusic.player.platform.DownloadedYouTubeAudio
import com.universalmusic.player.platform.YouTubeAudioDownloader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReadyOfflineQueueTest {
    @Test
    fun readyOfflineQueuePlaysOnlyFilesAndSkipsForwardPastACachedSongWhoseFileVanished() = runTest {
        val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(createTempDirectory("ready-offline")))
        val audioDir = Path(cache.audioDirectoryPath()).also { Files.createDirectories(it) }
        suspend fun cached(owner: String, videoId: String): HeartedAudioCacheEntry {
            val file = audioDir.resolve("$videoId.m4a").apply { writeBytes(ByteArray(16)) }
            return cache.put(HeartedAudioCacheEntry(owner, videoId, file.toUri().toASCIIString(), sizeBytes = 16))
        }
        val service = HeartedAudioCacheService(
            scope = backgroundScope,
            cache = cache,
            downloader = NoDownloader,
            youtubeSearch = { emptyList() },
            networkAvailable = { true },
        )
        cached("yt:alpha", "alpha").also(service::rememberEntry)
        cached("spotify:gone", "gone").also(service::rememberEntry)
        val keep = cached("yt:keep", "keep").also(service::rememberEntry)

        val queue = libraryQueueSongs(
            local = emptyList(),
            saved = listOf(
                stream("yt:alpha", "Alpha", ProviderId.YOUTUBE_MUSIC),
                stream("yt:keep", "Keep", ProviderId.YOUTUBE_MUSIC),
                stream("yt:net", "Net", ProviderId.YOUTUBE_MUSIC),
            ),
            spotify = listOf(stream("spotify:gone", "Gone", ProviderId.SPOTIFY)),
            sort = TrackSort.NAME_ASCENDING,
            localOnly = false,
            favoritesOnly = false,
            favorites = emptySet(),
            readyOffline = true,
            offlineTrack = { service.applyCacheToTrack(it).offlineOnly() },
        )
        assertEquals(listOf("yt:alpha", "spotify:gone", "yt:keep"), queue.map { it.canonicalId })

        // Gone vanishes after the list was built but before its row is tapped.
        audioDir.resolve("gone.m4a").deleteExisting()
        val offline = offlineQueue(queue) { service.applyCacheToTrack(it).offlineOnly() }
        val played = mutableListOf<String>()
        val enriched = mutableListOf<ProviderId>()
        val session = PlayerSession(
            RecordingEngine(played), DefaultSourceResolver(networkAvailable = { true }), backgroundScope,
            enrichSource = { _, source -> enriched += source.provider; source },
            prepareTrack = service::applyCacheToTrack,
        )
        session.play(offline, startIndex = 1)
        runCurrent()

        assertEquals(listOf(keep.localUri), played)
        assertEquals(listOf(ProviderId.LOCAL), enriched)
        assertEquals("yt:keep", session.nowPlaying.value.track?.canonicalId)
    }
}

private fun stream(id: String, title: String, provider: ProviderId) = Track(
    canonicalId = id,
    title = title,
    artists = listOf(ArtistRef("artist", "Artist")),
    sources = listOf(PlaybackSource(provider, id, isPlayable = true, handle = PlaybackHandle.ProviderPlayback(provider, id))),
)

private object NoDownloader : YouTubeAudioDownloader {
    override fun isAvailable() = false
    override suspend fun downloadAudio(
        videoId: String,
        destinationDirectory: String,
        fileBaseName: String,
        onProgress: ((Long, Long?) -> Unit)?,
    ): YouTubeDownloadResult = YouTubeDownloadResult.Failed(YouTubeFailureKind.PERMANENT, "no downloader")
}

private class RecordingEngine(private val played: MutableList<String>) : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        played += (handle as PlaybackHandle.Url).url
        state.value = EngineState(EngineStatus.PLAYING, durationMs = 200_000, playGeneration = playGeneration)
    }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = state.value.copy(status = EngineStatus.IDLE) }
    override fun setVolume(volume: Float) = Unit
}
