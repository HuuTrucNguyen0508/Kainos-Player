package com.universalmusic.player.data.youtube

import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.platform.ResolvedYouTubeAudio
import com.universalmusic.player.platform.YouTubeStreamResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeMusicProviderTest {
    private val config = AppConfig(spotifyRedirectUri = "http://127.0.0.1:43821/callback", youtubeDataApiKey = "test-key")
    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun searchUsesCompatibleFiltersAndHydratesVideoDuration() = runTest {
        val requests = mutableListOf<String>()
        val http = client(MockEngine { request ->
            requests += request.url.encodedPath
            assertEquals("test-key", request.url.parameters["key"])
            val response = when (request.url.encodedPath) {
                "/youtube/v3/search" -> {
                    assertEquals("video,playlist", request.url.parameters["type"])
                    assertNull(request.url.parameters["videoCategoryId"])
                    """{"items":[{"id":{"videoId":"video-1"},"snippet":{"title":"Song","channelTitle":"Artist"}},{"id":{"playlistId":"list-1"},"snippet":{"title":"Playlist"}}]}"""
                }
                "/youtube/v3/videos" -> {
                    assertEquals("video-1", request.url.parameters["id"])
                    """{"items":[{"id":"video-1","snippet":{"title":"Song","channelTitle":"Artist"},"contentDetails":{"duration":"PT3M12S"}}]}"""
                }
                else -> error("Unexpected request")
            }
            respond(response, headers = jsonHeaders)
        })
        try {
            val provider = YouTubeMusicProvider(http, config)
            val result = provider.search("Song")
            assertEquals(2, requests.size)
            assertEquals("yt:video-1", result.tracks.single().canonicalId)
            assertEquals(192_000L, result.tracks.single().durationMs)
            assertFalse(result.tracks.single().sources.single().isPlayable)
            assertEquals("list-1", result.playlists.single().source.providerEntityId)
            assertEquals(ProviderState.AVAILABLE, provider.state.value)
        } finally { http.close() }
    }

    @Test
    fun searchMarksTracksPlayableWhenStreamResolverIsAvailable() = runTest {
        val http = client(MockEngine { request ->
            val response = when (request.url.encodedPath) {
                "/youtube/v3/search" ->
                    """{"items":[{"id":{"videoId":"video-1"},"snippet":{"title":"Song","channelTitle":"Artist"}}]}"""
                "/youtube/v3/videos" ->
                    """{"items":[{"id":"video-1","snippet":{"title":"Song","channelTitle":"Artist"},"contentDetails":{"duration":"PT1M"}}]}"""
                else -> error("Unexpected request")
            }
            respond(response, headers = jsonHeaders)
        })
        try {
            val streams = object : YouTubeStreamResolver {
                override fun isAvailable(): Boolean = true
                override suspend fun resolveAudioUrl(videoId: String) =
                    ResolvedYouTubeAudio(url = "https://example.test/$videoId")
            }
            val provider = YouTubeMusicProvider(http, config, streams)
            val track = provider.search("Song").tracks.single()
            assertTrue(track.sources.single().isPlayable)
            assertTrue(provider.getCapabilities().playback)
            val stream = provider.getStream(track)
            assertEquals("https://example.test/video-1", (stream?.handle as? PlaybackHandle.Url)?.url)
        } finally { http.close() }
    }

    @Test
    fun resourceLookupsDecodeStringIds() = runTest {
        val http = client(MockEngine { request ->
            val response = if (request.url.encodedPath.endsWith("/videos")) {
                """{"items":[{"id":"video-2","snippet":{"title":"Song"},"contentDetails":{"duration":"PT1H2M3S"}}]}"""
            } else {
                """{"items":[{"id":"list-2","snippet":{"title":"Playlist"},"contentDetails":{"itemCount":12}}]}"""
            }
            respond(response, headers = jsonHeaders)
        })
        try {
            val provider = YouTubeMusicProvider(http, config)
            assertEquals(3_723_000L, provider.getTrack("video-2")?.durationMs)
            val playlist = provider.getPlaylist("list-2")
            assertEquals("yt-playlist:list-2", playlist?.canonicalId)
            assertEquals(12, playlist?.trackCount)
        } finally { http.close() }
    }

    @Test
    fun quotaErrorsAreReportedWithoutExposingTheKey() = runTest {
        val http = client(MockEngine {
            respond("""{"error":{"errors":[{"reason":"quotaExceeded"}]}}""", HttpStatusCode.Forbidden, jsonHeaders)
        })
        try {
            val provider = YouTubeMusicProvider(http, config)
            val error = assertFailsWith<IllegalStateException> { provider.search("Song") }
            assertEquals(ProviderState.RATE_LIMITED, provider.state.value)
            assertFalse(error.message.orEmpty().contains("test-key"))
        } finally { http.close() }
    }

    @Test
    fun cancellationDoesNotMarkTheProviderUnavailable() = runTest {
        val http = client(MockEngine { throw CancellationException("Cancelled search") })
        try {
            val provider = YouTubeMusicProvider(http, config)
            assertFailsWith<CancellationException> { provider.search("Song") }
            assertEquals(ProviderState.AVAILABLE, provider.state.value)
        } finally { http.close() }
    }

    @Test
    fun credentialsCanBeChangedWithoutRestarting() = runTest {
        val http = client(MockEngine { error("Blank search must not send requests") })
        try {
            val provider = YouTubeMusicProvider(http, config.copy(youtubeDataApiKey = null))
            assertEquals(ProviderState.NOT_CONFIGURED, provider.state.value)
            provider.updateConfig(config)
            assertEquals(ProviderState.AVAILABLE, provider.state.value)
            assertEquals(emptyList(), provider.search(" ").tracks)
            provider.updateConfig(config.copy(youtubeDataApiKey = null))
            assertFalse(provider.getCapabilities().search)
        } finally { http.close() }
    }
}
