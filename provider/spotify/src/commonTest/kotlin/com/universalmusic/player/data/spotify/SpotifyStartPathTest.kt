package com.universalmusic.player.data.spotify

import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.ProviderRateLimitedException
import com.universalmusic.player.domain.playback.rateLimitCause
import com.universalmusic.player.platform.SpotifyWebPlaybackDevice
import com.universalmusic.player.platform.SpotifyWebPlaybackHost
import com.universalmusic.player.platform.SpotifyWebPlaybackState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Web API calls on the way to starting a Spotify track through a Connect receiver. */
class SpotifyStartPathTest {
    private class NamedReceiver : SpotifyWebPlaybackHost {
        override val state = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.ConnectingSpotify)
        override val requiresStreamingScope = false
        override var receiverGeneration = 1L
        override suspend fun ensureDeviceReady() = SpotifyWebPlaybackDevice(deviceName = "Kainos Player")
        override suspend fun shutdown() = Unit
    }

    private class Tokens(var value: AuthTokens) : TokenStore {
        override suspend fun read(provider: ProviderId) = value
        override suspend fun write(provider: ProviderId, tokens: AuthTokens) { value = tokens }
        override suspend fun clear(provider: ProviderId) = Unit
    }

    private val devices = """{"devices":[{"id":"kainos","is_active":false,"is_restricted":false,"name":"Kainos Player","type":"Computer"}]}"""

    private fun provider(
        host: SpotifyWebPlaybackHost,
        tokens: Tokens = Tokens(AuthTokens("access", refreshToken = "refresh")),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = SpotifyProvider(
        http = HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
        tokens = tokens,
        initialConfig = AppConfig(spotifyClientId = "client-id", spotifyRedirectUri = "http://127.0.0.1:43821/callback"),
        clock = { 1_000L },
        webPlayback = host,
    )

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun MockRequestHandleScope.rateLimited(seconds: String) =
        respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, seconds))

    private fun HttpRequestData.line() = "${method.value} ${url.encodedPath}"

    @Test
    fun aReceiverRebuiltWhileFastPlayIsInFlightIsFoundAgain() = runTest {
        val host = NamedReceiver()
        val requests = mutableListOf<String>()
        var plays = 0
        val provider = provider(host) { request ->
            requests += request.line()
            if (request.url.encodedPath == "/v1/me/player/play" && ++plays == 2) host.receiverGeneration++
            if (request.url.encodedPath == "/v1/me/player/devices") json(devices) else respond("", HttpStatusCode.NoContent)
        }
        provider.startConnectPlayback("first")
        requests.clear()
        provider.startConnectPlayback("second")
        assertEquals(listOf("PUT /v1/me/player/play", "GET /v1/me/player/devices", "PUT /v1/me/player", "PUT /v1/me/player/play"), requests)
    }

    @Test
    fun logoutDropsTheKnownReceiverAndCooldown() = runTest {
        val requests = mutableListOf<String>()
        var limited = false
        val provider = provider(NamedReceiver()) { request ->
            requests += request.line()
            when {
                limited -> rateLimited("120")
                request.url.encodedPath == "/v1/me/player/devices" -> json(devices)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
        provider.startConnectPlayback("first")
        limited = true
        assertFailsWith<ProviderRateLimitedException> { provider.startConnectPlayback("second") }
        provider.logout()
        limited = false
        requests.clear()
        provider.observeConnectPlayback()
        assertEquals(listOf("GET /v1/me/player"), requests, "old account cooldown must be cleared")
        requests.clear()
        provider.startConnectPlayback("third")
        assertEquals(listOf("GET /v1/me/player/devices", "PUT /v1/me/player", "PUT /v1/me/player/play"), requests)
    }

    @Test
    fun anOldAccountsLateRateLimitDoesNotThrottleTheNewSession() = runTest {
        lateinit var provider: SpotifyProvider
        var late = true
        val requests = mutableListOf<String>()
        provider = provider(NamedReceiver()) { request ->
            requests += request.line()
            if (late) {
                late = false
                provider.logout()
                rateLimited("120")
            } else respond("", HttpStatusCode.NoContent)
        }
        assertFailsWith<Exception> { provider.startConnectPlayback("song") }
        requests.clear()
        provider.observeConnectPlayback()
        assertEquals(listOf("GET /v1/me/player"), requests)
    }

    @Test
    fun laterTracksOnTheSameReceiverCostOnePlayCall() = runTest {
        val requests = mutableListOf<String>()
        val provider = provider(NamedReceiver()) { request ->
            requests += request.line()
            if (request.url.encodedPath == "/v1/me/player/devices") json(devices) else respond("", HttpStatusCode.NoContent)
        }

        provider.startConnectPlayback("first")
        requests.clear()
        provider.startConnectPlayback("second")

        assertEquals(listOf("PUT /v1/me/player/play"), requests)
    }

    @Test
    fun aReceiverThatLostItsDeviceIsFoundAgain() = runTest {
        val requests = mutableListOf<String>()
        var plays = 0
        val provider = provider(NamedReceiver()) { request ->
            requests += request.line()
            when (request.url.encodedPath) {
                "/v1/me/player/devices" -> json(devices)
                "/v1/me/player/play" -> if (++plays == 2) respond("", HttpStatusCode.NotFound) else respond("", HttpStatusCode.NoContent)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }

        provider.startConnectPlayback("first")
        requests.clear()
        provider.startConnectPlayback("second")

        assertEquals(
            listOf("PUT /v1/me/player/play", "GET /v1/me/player/devices", "PUT /v1/me/player", "PUT /v1/me/player/play"),
            requests,
        )
    }

    @Test
    fun aRestartedReceiverIsFoundAgainBeforePlaying() = runTest {
        val host = NamedReceiver()
        val requests = mutableListOf<String>()
        val provider = provider(host) { request ->
            requests += request.line()
            if (request.url.encodedPath == "/v1/me/player/devices") json(devices) else respond("", HttpStatusCode.NoContent)
        }

        provider.startConnectPlayback("first")
        host.receiverGeneration++
        requests.clear()
        provider.startConnectPlayback("second")

        assertEquals(listOf("GET /v1/me/player/devices", "PUT /v1/me/player", "PUT /v1/me/player/play"), requests)
    }

    @Test
    fun aShortRetryAfterIsWaitedOutOnce() = runTest {
        var plays = 0
        val provider = provider(NamedReceiver()) { request ->
            when (request.url.encodedPath) {
                "/v1/me/player/devices" -> json(devices)
                "/v1/me/player/play" -> if (++plays == 1) rateLimited("2") else respond("", HttpStatusCode.NoContent)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }

        val started = currentTime
        provider.startConnectPlayback("song")

        assertEquals(2, plays)
        assertEquals(2_000, currentTime - started)
    }

    @Test
    fun aLongRetryAfterFailsTypedWithoutRetrying() = runTest {
        var plays = 0
        val provider = provider(NamedReceiver()) { request ->
            when (request.url.encodedPath) {
                "/v1/me/player/devices" -> json(devices)
                "/v1/me/player/play" -> { plays++; rateLimited("120") }
                else -> respond("", HttpStatusCode.NoContent)
            }
        }

        val failure = assertFailsWith<ProviderRateLimitedException> { provider.startConnectPlayback("song") }

        assertEquals(1, plays)
        assertEquals(120_000, failure.retryAfterMs)
        assertSame(failure, IllegalStateException("wrapped", failure).rateLimitCause())
    }

    @Test
    fun aRateLimitThatPersistsAfterTheShortWaitFailsTyped() = runTest {
        var polls = 0
        val provider = provider(NamedReceiver()) { request ->
            if (request.url.encodedPath == "/v1/me/player/devices") polls++
            rateLimited("1")
        }

        val failure = assertFailsWith<ProviderRateLimitedException> { provider.startConnectPlayback("song") }

        assertEquals(2, polls)
        assertEquals(1_000, failure.retryAfterMs)
    }

    @Test
    fun aRejectedTokenIsRefreshedOnceAndTheCallResent() = runTest {
        val tokens = Tokens(AuthTokens("revoked", refreshToken = "refresh"))
        val authorizations = mutableListOf<String>()
        var refreshes = 0
        val provider = provider(NamedReceiver(), tokens) { request ->
            when (request.url.encodedPath) {
                "/api/token" -> {
                    refreshes++
                    json("""{"access_token":"fresh","expires_in":3600}""")
                }
                else -> {
                    val auth = request.headers[HttpHeaders.Authorization]
                    authorizations += "${request.line()} $auth"
                    when {
                        auth == "Bearer revoked" -> respond("", HttpStatusCode.Unauthorized)
                        request.url.encodedPath == "/v1/me/player/devices" -> json(devices)
                        else -> respond("", HttpStatusCode.NoContent)
                    }
                }
            }
        }

        provider.startConnectPlayback("song")

        assertEquals(1, refreshes)
        assertEquals("fresh", tokens.value.accessToken)
        assertEquals("refresh", tokens.value.refreshToken)
        assertEquals(
            listOf(
                "GET /v1/me/player/devices Bearer revoked",
                "GET /v1/me/player/devices Bearer fresh",
                "PUT /v1/me/player Bearer fresh",
                "PUT /v1/me/player/play Bearer fresh",
            ),
            authorizations,
        )
    }
}
