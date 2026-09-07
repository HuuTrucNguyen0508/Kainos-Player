package com.universalmusic.player.data.spotify

import com.universalmusic.player.platform.SpotifyWebPlaybackHost
import com.universalmusic.player.platform.SpotifyWebPlaybackDevice
import com.universalmusic.player.platform.SpotifyWebPlaybackFailure
import com.universalmusic.player.platform.SpotifyWebPlaybackState
import com.universalmusic.player.platform.UnavailableSpotifyWebPlaybackHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.domain.model.ProviderId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpotifyProviderTest {
    @Test
    fun webPlayerIsActivatedBeforeStartingTheTrack() = runTest {
        val requests = mutableListOf<String>()
        val host = object : SpotifyWebPlaybackHost {
            override val state = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.Ready("web-device"))
            override suspend fun ensureDeviceReady() = SpotifyWebPlaybackDevice("web-device")
            override suspend fun shutdown() = Unit
        }
        val provider = provider(TokenStoreFake(AuthTokens("access", scopes = listOf("streaming"))), host) { request ->
            requests += request.url.encodedPath
            respond("", HttpStatusCode.NoContent)
        }

        provider.startConnectPlayback("song")

        assertEquals(listOf("/v1/me/player", "/v1/me/player/play"), requests)
    }

    @Test
    fun cancellingWebStartupDoesNotFallBackToExternalDevices() = runTest {
        val host = object : SpotifyWebPlaybackHost {
            override val state = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.StartingHost)
            override suspend fun ensureDeviceReady(): SpotifyWebPlaybackDevice? = throw CancellationException("Track skipped")
            override suspend fun shutdown() = Unit
        }
        var requests = 0
        val provider = provider(TokenStoreFake(AuthTokens("access", scopes = listOf("streaming"))), host) {
            requests++
            respondJson("{}")
        }

        assertFailsWith<CancellationException> { provider.startConnectPlayback("song") }
        assertEquals(0, requests)
    }

    @Test
    fun nativeReceiverIsResolvedByNameWithoutUsingStreamingScope() = runTest {
        val requests = mutableListOf<String>()
        val transferBodies = mutableListOf<String>()
        val host = object : SpotifyWebPlaybackHost {
            override val state = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.ConnectingSpotify)
            override val requiresStreamingScope: Boolean = false
            override suspend fun ensureDeviceReady() = SpotifyWebPlaybackDevice(deviceName = "Kainos Player")
            override suspend fun shutdown() = Unit
        }
        val provider = provider(TokenStoreFake(AuthTokens("access")), host) { request ->
            requests += request.url.encodedPath + request.url.parameters.entries()
                .flatMap { (key, values) -> values.map { "$key=$it" } }
                .joinToString(prefix = "?", separator = "&")
            when (request.url.encodedPath) {
                "/v1/me/player/devices" -> respondJson(
                    """{"devices":[{"id":"other-speaker","is_active":true,"is_restricted":false,"name":"Kitchen","type":"Speaker"},{"id":"kainos-device","is_active":false,"is_restricted":false,"name":"Kainos Player","type":"Computer"}]}""",
                )
                "/v1/me/player" -> {
                    transferBodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("", HttpStatusCode.NoContent)
                }
                else -> respond("", HttpStatusCode.NoContent)
            }
        }

        provider.startConnectPlayback("song")

        assertEquals(
            listOf(
                "/v1/me/player/devices?",
                "/v1/me/player?",
                "/v1/me/player/play?device_id=kainos-device",
            ),
            requests,
        )
        assertTrue(transferBodies.single().contains("kainos-device"))
        assertTrue(!transferBodies.single().contains("other-speaker"))
    }

    @Test
    fun nativeReceiverAuthenticationFailureDoesNotLaunchExternalPlayback() = runTest {
        val host = object : SpotifyWebPlaybackHost {
            override val state = MutableStateFlow<SpotifyWebPlaybackState>(
                SpotifyWebPlaybackState.Failed(SpotifyWebPlaybackFailure.LibrespotAuthenticationRequired),
            )
            override val requiresStreamingScope: Boolean = false
            override suspend fun ensureDeviceReady(): SpotifyWebPlaybackDevice? = null
            override suspend fun shutdown() = Unit
        }
        var requests = 0
        val provider = provider(TokenStoreFake(AuthTokens("access")), host) {
            requests += 1
            respondJson("{}")
        }

        val failure = assertFailsWith<IllegalStateException> { provider.startConnectPlayback("song") }

        assertTrue(failure.message.orEmpty().contains("one-time librespot sign-in"))
        assertEquals(0, requests)
    }

    @Test
    fun namedReceiverPollStopsImmediatelyOnRateLimitAndShowsRetryDelay() = runTest {
        val host = object : SpotifyWebPlaybackHost {
            override val state = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.ConnectingSpotify)
            override val requiresStreamingScope: Boolean = false
            override suspend fun ensureDeviceReady() = SpotifyWebPlaybackDevice(deviceName = "Kainos Player")
            override suspend fun shutdown() = Unit
        }
        var requests = 0
        val provider = provider(TokenStoreFake(AuthTokens("access")), host) {
            requests += 1
            respond(
                content = "rate limited",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.RetryAfter, "69951"),
            )
        }

        val failure = assertFailsWith<IllegalStateException> { provider.startConnectPlayback("song") }

        assertEquals(1, requests)
        assertTrue(failure.message.orEmpty().contains("69951 seconds"))
        assertTrue(failure.message.orEmpty().contains("about 19 hours"))
    }

    @Test
    fun playlistFailureDoesNotDiscardLikedSongs() = runTest {
        val provider = provider(TokenStoreFake(AuthTokens("access"))) { request ->
            if (request.url.encodedPath.endsWith("/tracks")) {
                respondJson("""{"items":[{"track":{"id":"song","name":"Saved song"}}],"total":1,"next":null}""")
            } else {
                respond("", HttpStatusCode.ServiceUnavailable)
            }
        }

        val result = loadSpotifyLibrary(provider)

        assertEquals("Saved song", result.tracks.getOrThrow().single().title)
        assertTrue(result.playlists.isFailure)
    }

    @Test
    fun likedSongsFailureDoesNotBlockPlaylists() = runTest {
        val provider = provider(TokenStoreFake(AuthTokens("access"))) { request ->
            if (request.url.encodedPath.endsWith("/tracks")) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                respondJson("""{"items":[{"id":"playlist","name":"Saved playlist"}],"total":1,"next":null}""")
            }
        }

        val result = loadSpotifyLibrary(provider)

        assertTrue(result.tracks.isFailure)
        assertEquals("Saved playlist", result.playlists.getOrThrow().single().title)
    }

    @Test
    fun libraryLoadsPlaylistsWithoutCoverImages() = runTest {
        val provider = provider(TokenStoreFake(AuthTokens("access"))) {
            respondJson("""{"items":[{"id":"no-cover","name":"Playlist without cover","images":null,"items":{"total":3}}],"total":1,"next":null}""")
        }

        val playlists = provider.getUserPlaylists()

        assertEquals(1, playlists.size)
        assertEquals("no-cover", playlists.single().source.providerEntityId)
        kotlin.test.assertNull(playlists.single().artwork)
    }

    @Test
    fun loginRejectsWrongStateBeforeTokenExchange() = runTest {
        var requests = 0
        val provider = provider(TokenStoreFake()) {
            requests++
            respondJson("{}")
        }
        val session = provider.beginLogin()

        val failure = assertFailsWith<IllegalArgumentException> {
            provider.completeLogin("http://127.0.0.1:43821/callback?code=code&state=wrong")
        }

        assertTrue(failure.message.orEmpty().contains("state"))
        assertEquals(0, requests)
        assertEquals("S256", queryParam(session.authorizationUrl, "code_challenge_method"))
        assertEquals(43, queryParam(session.authorizationUrl, "code_challenge")?.length)
        assertTrue(queryParam(session.authorizationUrl, "scope").orEmpty().contains("streaming"))
    }

    @Test
    fun loginDecodesCallbackAndPersistsTokens() = runTest {
        val store = TokenStoreFake()
        val bodies = mutableListOf<String>()
        val provider = provider(store) { request ->
            when (request.url.encodedPath) {
                "/api/token" -> {
                    bodies += (request.body as OutgoingContent.ByteArrayContent)
                        .bytes()
                        .decodeToString()
                    respondJson("""{"access_token":"access","refresh_token":"refresh","expires_in":3600}""")
                }
                "/v1/me" -> respondJson("""{"id":"user","product":"premium"}""")
                else -> error("Unexpected request ${request.url}")
            }
        }
        val session = provider.beginLogin()
        val state = queryParam(session.authorizationUrl, "state")

        provider.completeLogin("http://127.0.0.1:43821/callback?code=a%2Bb&state=$state")

        assertEquals("access", store.value?.accessToken)
        assertEquals("refresh", store.value?.refreshToken)
        assertTrue(provider.isAuthenticated())
        assertTrue(bodies.single().contains("a%2Bb"))
    }

    @Test
    fun searchUsesCurrentLimitAndIgnoresNullPlaylistEntries() = runTest {
        val store = TokenStoreFake(AuthTokens("access"))
        var limit: String? = null
        val provider = provider(store) { request ->
            limit = request.url.parameters["limit"]
            respondJson(
                """{
                    "tracks":{"items":[],"total":0},
                    "albums":{"items":[],"total":0},
                    "artists":{"items":[],"total":0},
                    "playlists":{"items":[null,{"id":"p1","name":"Mix","items":{"total":7}}],"total":2}
                }""",
            )
        }

        val result = provider.search("ambient")

        assertEquals("10", limit)
        assertEquals(listOf("Mix"), result.playlists.map { it.title })
        assertEquals(7, result.playlists.single().trackCount)
    }

    @Test
    fun libraryReadsEveryPage() = runTest {
        val store = TokenStoreFake(AuthTokens("access"))
        val offsets = mutableListOf<String?>()
        val provider = provider(store) { request ->
            offsets += request.url.parameters["offset"]
            val offset = request.url.parameters["offset"]
            val next = if (offset == "0") "\"https://api.spotify.com/v1/me/tracks?offset=1\"" else "null"
            val id = if (offset == "0") "first" else "second"
            respondJson(
                """{"items":[{"track":{"id":"$id","name":"$id"}}],"total":2,"next":$next}""",
            )
        }

        val tracks = provider.getLibraryTracks()

        assertEquals("0,1", offsets.joinToString(","))
        assertEquals(listOf("first", "second"), tracks.map { it.title })
    }

    @Test
    fun librarySkipsDelistedTracksWithEmptyMetadata() = runTest {
        val store = TokenStoreFake(AuthTokens("access"))
        val provider = provider(store) {
            respondJson(
                """
                {"items":[
                  {"track":null},
                  {"track":{"id":"gone","name":"","duration_ms":0,"artists":[{"id":"0LyfQWJT6nXafLPZqxe9Of","name":""}],"album":{"id":"a","name":"","images":[]}}},
                  {"track":{"id":"keep","name":"Keep Me","duration_ms":120000,"artists":[{"id":"a1","name":"Artist"}]}}
                ],"total":3,"next":null}
                """.trimIndent(),
            )
        }

        val tracks = provider.getLibraryTracks()

        assertEquals(listOf("Keep Me"), tracks.map { it.title })
        assertEquals(listOf("spotify:keep"), tracks.map { it.canonicalId })
    }

    @Test
    fun connectControlsUsePlayerEndpoints() = runTest {
        val store = TokenStoreFake(AuthTokens("access"))
        val requests = mutableListOf<String>()
        val provider = provider(store) { request ->
            requests += request.url.encodedPath + request.url.parameters.entries()
                .flatMap { (key, values) -> values.map { "$key=$it" } }
                .joinToString(prefix = "?", separator = "&")
            when (request.url.encodedPath) {
                "/v1/me/player/devices" -> respondJson(
                    """{"devices":[{"id":"desk","is_active":true,"is_restricted":false,"name":"PC","type":"Computer"}]}""",
                )
                else -> respond("", HttpStatusCode.NoContent)
            }
        }

        provider.startConnectPlayback("track")
        provider.pauseConnectPlayback()
        provider.resumeConnectPlayback()
        provider.seekConnectPlayback(12_345)

        assertEquals("/v1/me/player/devices?", requests[0])
        assertEquals("/v1/me/player/play?device_id=desk", requests[1])
        assertEquals("/v1/me/player/pause?", requests[2])
        assertEquals("/v1/me/player/play?", requests[3])
        assertEquals("/v1/me/player/seek?position_ms=12345", requests[4])
    }

    @Test
    fun connectPlaybackTransfersWhenNoDeviceIsActive() = runTest {
        val store = TokenStoreFake(AuthTokens("access"))
        val requests = mutableListOf<String>()
        val provider = provider(store) { request ->
            requests += request.url.encodedPath + request.url.parameters.entries()
                .flatMap { (key, values) -> values.map { "$key=$it" } }
                .joinToString(prefix = "?", separator = "&")
            when (request.url.encodedPath) {
                "/v1/me/player/devices" -> respondJson(
                    """{"devices":[{"id":"desk","is_active":false,"is_restricted":false,"name":"PC","type":"Computer"},{"id":"echo","is_active":false,"is_restricted":false,"name":"Echo","type":"Speaker"}]}""",
                )
                else -> respond("", HttpStatusCode.NoContent)
            }
        }

        provider.startConnectPlayback("track")

        assertEquals(
            listOf(
                "/v1/me/player/devices?",
                "/v1/me/player?",
                "/v1/me/player/play?device_id=desk",
            ),
            requests,
        )
    }

    @Test
    fun connectPlaybackFailsClearlyWhenNoDevicesExist() = runTest {
        val store = TokenStoreFake(AuthTokens("access"))
        val provider = provider(store) { request ->
            assertEquals("/v1/me/player/devices", request.url.encodedPath)
            respondJson("""{"devices":[]}""")
        }

        val error = runCatching { provider.startConnectPlayback("track") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.message!!.contains("No Spotify Connect device found"))
    }

    @Test
    fun startupConfigOverridePreservesSavedSession() = runTest {
        val saved = AuthTokens("saved-access", refreshToken = "saved-refresh")
        val store = TokenStoreFake(saved)
        val provider = provider(store) { request ->
            assertEquals("/v1/me", request.url.encodedPath)
            respondJson("""{"id":"user"}""")
        }

        provider.updateConfig(
            AppConfig(
                spotifyClientId = "saved-client-id",
                spotifyRedirectUri = "http://127.0.0.1:43821/callback",
            ),
            clearSessionOnChange = false,
        )

        assertEquals(saved, store.value)
        assertTrue(provider.isAuthenticated())
    }

    private fun provider(
        store: TokenStoreFake,
        webPlayback: SpotifyWebPlaybackHost = UnavailableSpotifyWebPlaybackHost,
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): SpotifyProvider {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return SpotifyProvider(
            http = client,
            tokens = store,
            initialConfig = AppConfig(
                spotifyClientId = "client-id",
                spotifyRedirectUri = "http://127.0.0.1:43821/callback",
            ),
            clock = { 1_000L },
            webPlayback = webPlayback,
        )
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}

private class TokenStoreFake(initial: AuthTokens? = null) : TokenStore {
    var value: AuthTokens? = initial

    override suspend fun read(provider: ProviderId): AuthTokens? = value

    override suspend fun write(provider: ProviderId, tokens: AuthTokens) {
        value = tokens
    }

    override suspend fun clear(provider: ProviderId) {
        value = null
    }
}
