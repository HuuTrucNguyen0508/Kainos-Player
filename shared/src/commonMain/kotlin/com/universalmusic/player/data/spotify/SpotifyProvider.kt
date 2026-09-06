package com.universalmusic.player.data.spotify

import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderCapabilities
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.SearchResult
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.provider.AuthSession
import com.universalmusic.player.domain.provider.AuthenticatingProvider
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.encodeUrl
import com.universalmusic.player.platform.ensureSpotifyConnectClientAvailable
import com.universalmusic.player.platform.secureRandomBytes
import com.universalmusic.player.platform.sha256Bytes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val AUTH_URL = "https://accounts.spotify.com/authorize"
private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
private const val API = "https://api.spotify.com/v1"
private val SCOPES = listOf(
    "user-read-email",
    "user-read-private",
    "user-library-read",
    "playlist-read-private",
    "playlist-read-collaborative",
    "user-read-playback-state",
    "user-modify-playback-state",
    "user-read-currently-playing",
    "user-read-recently-played",
)

class SpotifyProvider(
    private val http: HttpClient,
    private val tokens: TokenStore,
    initialConfig: AppConfig,
    private val clock: () -> Long = { currentTimeMillis() },
) : AuthenticatingProvider {
    override val providerId: ProviderId = ProviderId.SPOTIFY

    private val _state = MutableStateFlow(ProviderState.AUTH_REQUIRED)
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val authMutex = Mutex()
    private var config: AppConfig = initialConfig
    private var pendingLogin: PendingLogin? = null
    private var premium: Boolean = false

    suspend fun restore() {
        if (!config.hasSpotifyCredentials) {
            _state.value = ProviderState.NOT_CONFIGURED
            return
        }
        val stored = tokens.read(ProviderId.SPOTIFY)
        if (stored == null) {
            _state.value = ProviderState.AUTH_REQUIRED
            return
        }
        try {
            refreshIfNeeded(stored)
            loadProfile()
            _state.value = ProviderState.AVAILABLE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            premium = false
            _state.value = ProviderState.UNAVAILABLE
        }
    }

    override suspend fun beginLogin(): AuthSession {
        val clientId = config.spotifyClientId?.takeIf { it.isNotBlank() }
            ?: error("Spotify client ID is not configured")
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(32)
        authMutex.withLock {
            pendingLogin = PendingLogin(verifier, state, clientId, config.spotifyRedirectUri)
        }
        val challenge = pkceChallenge(verifier)
        val url = buildString {
            append(AUTH_URL)
            append("?response_type=code")
            append("&client_id=").append(encode(clientId))
            append("&redirect_uri=").append(encode(config.spotifyRedirectUri))
            append("&scope=").append(encode(SCOPES.joinToString(" ")))
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(encode(challenge))
            append("&state=").append(encode(state))
        }
        return AuthSession(url, config.spotifyRedirectUri)
    }

    override suspend fun completeLogin(redirectUri: String) {
        authMutex.withLock {
            val pending = pendingLogin ?: error("No Spotify login is in progress")
            require(matchesRedirect(redirectUri, pending.redirectUri)) {
                "Spotify returned an unexpected redirect URI"
            }
            val returnedState = queryParam(redirectUri, "state")
            require(returnedState == pending.state) { "Spotify login state did not match" }
            queryParam(redirectUri, "error")?.let { error ->
                pendingLogin = null
                error("Spotify login failed: $error")
            }
            val code = queryParam(redirectUri, "code")
                ?: error("Spotify login did not return an authorization code")
            val tokenResponse = http.submitForm(
                url = TOKEN_URL,
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", pending.redirectUri)
                    append("client_id", pending.clientId)
                    append("code_verifier", pending.verifier)
                },
            )
            val response = tokenResponse.successBody<SpotifyTokenResponse>("Spotify token exchange")
            persist(response)
            pendingLogin = null
            loadProfile()
            _state.value = ProviderState.AVAILABLE
        }
    }

    override suspend fun logout() {
        tokens.clear(ProviderId.SPOTIFY)
        authMutex.withLock { pendingLogin = null }
        premium = false
        _state.value = if (config.hasSpotifyCredentials) ProviderState.AUTH_REQUIRED else ProviderState.NOT_CONFIGURED
    }

    override suspend fun isAuthenticated(): Boolean = tokens.read(ProviderId.SPOTIFY) != null && _state.value == ProviderState.AVAILABLE

    override suspend fun search(query: String): SearchResult {
        if (query.isBlank()) return SearchResult()
        val token = accessToken()
        val response = http.get("$API/search") {
            bearerAuth(token)
            parameter("q", query)
            parameter("type", "track,album,artist,playlist")
            parameter("limit", 10)
        }.successBody<SpotifySearchResponse>("Spotify search")
        return SearchResult(
            tracks = response.tracks?.items?.mapNotNull { it.toDomainOrNull(premium) }.orEmpty(),
            albums = response.albums?.items?.map { it.toDomain(premium) }.orEmpty(),
            artists = response.artists?.items?.map { it.toDomain() }.orEmpty(),
            playlists = response.playlists?.items?.mapNotNull { it?.toDomain(premium) }.orEmpty(),
        )
    }

    override suspend fun getTrack(id: String): Track? {
        val token = accessToken()
        return http.get("$API/tracks/$id") { bearerAuth(token) }
            .successBody<SpotifyTrack>("Spotify track lookup")
            .toDomain(premium)
    }

    override suspend fun getAlbum(id: String): Album? {
        val token = accessToken()
        return http.get("$API/albums/$id") { bearerAuth(token) }
            .successBody<SpotifyAlbum>("Spotify album lookup")
            .toDomain(premium)
    }

    override suspend fun getArtist(id: String): Artist? {
        val token = accessToken()
        return http.get("$API/artists/$id") { bearerAuth(token) }
            .successBody<SpotifyArtist>("Spotify artist lookup")
            .toDomain()
    }

    override suspend fun getPlaylist(id: String): Playlist? {
        val token = accessToken()
        return http.get("$API/playlists/$id") { bearerAuth(token) }
            .successBody<SpotifyPlaylist>("Spotify playlist lookup")
            .toDomain(premium)
    }

    override suspend fun getStream(track: Track): PlaybackSource? {
        val source = track.sourceFor(ProviderId.SPOTIFY) ?: return null
        return if (premium) source.copy(isPlayable = true) else source.copy(isPlayable = false)
    }

    override suspend fun getLibraryTracks(): List<Track> {
        val token = accessToken()
        val result = mutableListOf<Track>()
        var offset = 0
        do {
            val response = http.get("$API/me/tracks") {
                bearerAuth(token)
                parameter("limit", 50)
                parameter("offset", offset)
            }.successBody<SpotifyPaging<SpotifySavedTrack>>("Spotify saved tracks")
            result += response.items.mapNotNull { it.track?.toDomainOrNull(premium) }
            offset += response.items.size
        } while (response.next != null && response.items.isNotEmpty())
        return result
    }

    override suspend fun getUserPlaylists(): List<Playlist> {
        val token = accessToken()
        val result = mutableListOf<Playlist>()
        var offset = 0
        do {
            val response = http.get("$API/me/playlists") {
                bearerAuth(token)
                parameter("limit", 50)
                parameter("offset", offset)
            }.successBody<SpotifyPaging<SpotifyPlaylist>>("Spotify playlists")
            result += response.items.mapNotNull { playlist ->
                playlist.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }?.toDomain(premium)
            }
            offset += response.items.size
        } while (response.next != null && response.items.isNotEmpty())
        return result
    }

    override suspend fun getCapabilities(): ProviderCapabilities = ProviderCapabilities(
        search = true,
        metadata = true,
        playlists = true,
        library = true,
        playback = premium,
        backgroundPlayback = premium,
        losslessPlayback = false,
    )

    suspend fun startConnectPlayback(spotifyTrackId: String) {
        val token = accessToken()
        val target = resolvePlaybackTarget(token)
        if (!target.hasActiveDevice) {
            val transfer = http.put("$API/me/player") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(SpotifyTransferRequest(deviceIds = listOf(target.deviceId), play = false))
            }
            // Transfer can 404 briefly after Spotify launches; play with device_id still retries activation.
            if (!transfer.status.isSuccess() && transfer.status.value != 404) {
                transfer.requireSuccess("Spotify Connect device transfer")
            }
        }
        val response = http.put("$API/me/player/play") {
            bearerAuth(token)
            parameter("device_id", target.deviceId)
            contentType(ContentType.Application.Json)
            setBody(SpotifyPlayRequest(uris = listOf("spotify:track:$spotifyTrackId")))
        }
        response.requireSuccess("Spotify Connect playback")
    }

    private suspend fun resolvePlaybackTarget(token: String): ConnectPlaybackTarget {
        var devices = listConnectDevices(token)
        if (devices.isEmpty()) {
            ensureSpotifyConnectClientAvailable()
            for (attempt in 1..12) {
                delay(1_000)
                devices = listConnectDevices(token)
                if (devices.isNotEmpty()) break
            }
        }
        if (devices.isEmpty()) {
            error(
                "No Spotify Connect device found. Could not start or detect the Spotify app. " +
                    "Open Spotify on this computer (or another Premium device), then try again.",
            )
        }
        val preferred = devices.firstOrNull { it.isActive }
            ?: devices.firstOrNull { it.type.equals("Computer", ignoreCase = true) }
            ?: devices.first()
        return ConnectPlaybackTarget(
            deviceId = preferred.id!!,
            hasActiveDevice = devices.any { it.isActive },
        )
    }

    private suspend fun listConnectDevices(token: String): List<SpotifyDevice> =
        http.get("$API/me/player/devices") { bearerAuth(token) }
            .successBody<SpotifyDevicesResponse>("Spotify devices")
            .devices
            .filter { !it.isRestricted && !it.id.isNullOrBlank() }

    suspend fun pauseConnectPlayback() {
        val response = http.put("$API/me/player/pause") { bearerAuth(accessToken()) }
        response.requireSuccess("Spotify Connect pause")
    }

    suspend fun resumeConnectPlayback() {
        val response = http.put("$API/me/player/play") { bearerAuth(accessToken()) }
        response.requireSuccess("Spotify Connect resume")
    }

    suspend fun seekConnectPlayback(positionMs: Long) {
        val response = http.put("$API/me/player/seek") {
            bearerAuth(accessToken())
            parameter("position_ms", positionMs.coerceAtLeast(0))
        }
        response.requireSuccess("Spotify Connect seek")
    }

    suspend fun updateConfig(next: AppConfig, clearSessionOnChange: Boolean = true) {
        val credentialsChanged = config.spotifyClientId != next.spotifyClientId ||
            config.spotifyRedirectUri != next.spotifyRedirectUri
        if (credentialsChanged && clearSessionOnChange) logout()
        config = next
        restore()
    }

    private suspend fun accessToken(): String = mutex.withLock {
        val stored = tokens.read(ProviderId.SPOTIFY) ?: error("Spotify is not connected")
        refreshIfNeeded(stored).accessToken
    }

    private suspend fun refreshIfNeeded(stored: AuthTokens): AuthTokens {
        if (!stored.isExpired(clock()) || stored.refreshToken.isNullOrBlank()) {
            return stored
        }
        val clientId = config.spotifyClientId ?: error("Spotify client ID is not configured")
        val tokenResponse = http.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", stored.refreshToken)
                append("client_id", clientId)
            },
        )
        val response = tokenResponse.successBody<SpotifyTokenResponse>("Spotify token refresh")
        return persist(response, stored.refreshToken)
    }

    private suspend fun persist(response: SpotifyTokenResponse, previousRefresh: String? = null): AuthTokens {
        val stored = AuthTokens(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: previousRefresh,
            expiresAtEpochMs = clock() + response.expiresIn * 1000,
            scopes = response.scope?.split(' ').orEmpty(),
        )
        tokens.write(ProviderId.SPOTIFY, stored)
        return stored
    }

    private suspend fun loadProfile() {
        val token = tokens.read(ProviderId.SPOTIFY)?.accessToken ?: return
        val me = http.get("$API/me") { bearerAuth(token) }
            .successBody<SpotifyUser>("Spotify profile")
        // Spotify's current profile response no longer guarantees the legacy product field.
        // Player endpoints enforce Premium eligibility, so a valid user session may attempt Connect playback.
        premium = me.id.isNotBlank()
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun pkceChallenge(verifier: String): String {
    val hash = sha256Bytes(verifier.encodeToByteArray())
    return Base64.UrlSafe.encode(hash).trimEnd('=')
}

internal fun randomUrlSafe(length: Int): String {
    require(length > 0)
    val bytes = secureRandomBytes((length * 3 + 3) / 4)
    return Base64.UrlSafe.encode(bytes).trimEnd('=').take(length)
}

internal fun encode(value: String): String = encodeUrl(value)

internal fun queryParam(uri: String, key: String): String? {
    return runCatching { Url(uri).parameters[key] }.getOrNull()
}

private fun matchesRedirect(actual: String, expected: String): Boolean = runCatching {
    val actualUrl = Url(actual)
    val expectedUrl = Url(expected)
    actualUrl.protocol.name.equals(expectedUrl.protocol.name, ignoreCase = true) &&
        actualUrl.host.equals(expectedUrl.host, ignoreCase = true) &&
        actualUrl.port == expectedUrl.port &&
        actualUrl.encodedPath == expectedUrl.encodedPath
}.getOrDefault(false)

private suspend inline fun <reified T> HttpResponse.successBody(operation: String): T {
    if (!status.isSuccess()) errorMessage(operation)
    return body()
}

private suspend fun HttpResponse.requireSuccess(operation: String) {
    if (!status.isSuccess()) errorMessage(operation)
}

private suspend fun HttpResponse.errorMessage(operation: String): Nothing {
    val detail = runCatching { bodyAsText() }.getOrNull()?.takeIf { it.isNotBlank() }
    error("$operation failed (${status.value})${detail?.let { ": $it" }.orEmpty()}")
}

private data class PendingLogin(
    val verifier: String,
    val state: String,
    val clientId: String,
    val redirectUri: String,
)

private data class ConnectPlaybackTarget(
    val deviceId: String,
    val hasActiveDevice: Boolean,
)
