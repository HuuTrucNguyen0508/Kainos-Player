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
import com.universalmusic.player.platform.SpotifyWebPlaybackHost
import com.universalmusic.player.platform.SpotifyWebPlaybackFailure
import com.universalmusic.player.platform.SpotifyWebPlaybackState
import com.universalmusic.player.platform.UnavailableSpotifyWebPlaybackHost
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
    "streaming",
)

class SpotifyProvider(
    private val http: HttpClient,
    private val tokens: TokenStore,
    initialConfig: AppConfig,
    private val clock: () -> Long = { currentTimeMillis() },
    private val webPlayback: SpotifyWebPlaybackHost = UnavailableSpotifyWebPlaybackHost,
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
            when (loadProfileStatus()) {
                ProfileStatus.Ok -> _state.value = ProviderState.AVAILABLE
                ProfileStatus.QuotaExceeded -> {
                    // Tokens are valid; development-mode account quota is exhausted.
                    premium = true
                    _state.value = ProviderState.RATE_LIMITED
                }
                ProfileStatus.Failed -> {
                    premium = false
                    _state.value = ProviderState.UNAVAILABLE
                }
            }
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
            when (loadProfileStatus()) {
                ProfileStatus.Ok -> _state.value = ProviderState.AVAILABLE
                ProfileStatus.QuotaExceeded -> {
                    premium = true
                    _state.value = ProviderState.RATE_LIMITED
                }
                ProfileStatus.Failed -> {
                    // Keep tokens; player endpoints can still work once quota recovers.
                    premium = true
                    _state.value = ProviderState.AVAILABLE
                }
            }
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
        // #region agent log
        debugSpotifyLog(
            "H1",
            "SpotifyProvider.startConnectPlayback",
            "playback target resolved",
            mapOf(
                "kainosLocal" to target.kainosLocal,
                "hasActiveDevice" to target.hasActiveDevice,
                "devicePrefix" to target.deviceId.take(8),
            ),
        )
        // #endregion
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
        var lastError: String? = null
        repeat(3) { attempt ->
            val response = http.put("$API/me/player/play") {
                bearerAuth(token)
                parameter("device_id", target.deviceId)
                contentType(ContentType.Application.Json)
                setBody(SpotifyPlayRequest(uris = listOf("spotify:track:$spotifyTrackId")))
            }
            if (response.status.isSuccess()) {
                // #region agent log
                debugSpotifyLog(
                    "H1",
                    "SpotifyProvider.startConnectPlayback",
                    "direct play succeeded",
                    mapOf("attempt" to attempt + 1),
                )
                // #endregion
                return
            }
            lastError = runCatching { response.bodyAsText() }.getOrNull()
            // #region agent log
            debugSpotifyLog(
                "H1",
                "SpotifyProvider.startConnectPlayback",
                "direct play failed",
                mapOf("attempt" to attempt + 1, "status" to response.status.value),
            )
            // #endregion
            if (response.status.value !in setOf(404, 502, 503)) {
                response.requireSuccess("Spotify Connect playback")
            }
            delay(250L * (attempt + 1))
        }
        error("Spotify Connect playback failed${lastError?.let { ": $it" }.orEmpty()}")
    }

    private suspend fun resolvePlaybackTarget(token: String): ConnectPlaybackTarget {
        val stored = tokens.read(ProviderId.SPOTIFY)
        val granted = stored?.scopes.orEmpty()
        val hasStreaming = "streaming" in granted
        // #region agent log
        debugSpotifyLog(
            "H4",
            "SpotifyProvider.resolvePlaybackTarget",
            "scope check",
            mapOf("hasStreaming" to hasStreaming, "scopeCount" to granted.size),
        )
        // #endregion

        if (hasStreaming || !webPlayback.requiresStreamingScope) {
            val device = runCatching { webPlayback.ensureDeviceReady() }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                // #region agent log
                debugSpotifyLog(
                    "H2",
                    "SpotifyProvider.resolvePlaybackTarget",
                    "web playback ensure failed",
                    mapOf("error" to (failure.message ?: "unknown"), "state" to webPlayback.state.value.toString()),
                )
                // #endregion
                null
            }
            if (device != null) {
                val deviceId = device.deviceId ?: device.deviceName?.let { name ->
                    waitForNamedConnectDevice(token, name)?.id
                }
                if (deviceId.isNullOrBlank()) {
                    webPlaybackFailureMessage()?.let { error(it) }
                    error(
                        "Kainos Player started librespot, but Spotify did not register its Connect device. " +
                            "Check ~/.universal-music-player/logs/librespot.log and try again.",
                    )
                }
                return ConnectPlaybackTarget(
                    deviceId = deviceId,
                    // Receiver startup only registers the device. Activate it before playback.
                    hasActiveDevice = false,
                    kainosLocal = true,
                )
            }
            if (webPlayback.state.value is SpotifyWebPlaybackState.ActivationRequired) {
                error("Spotify needs one-time browser audio activation. Use Enable Audio in the Kainos Player window.")
            }
            webPlaybackFailureMessage()?.let { error(it) }
        }

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
            val hint = if (!hasStreaming) {
                " Reconnect Spotify in Settings to enable Linux web playback (streaming scope)."
            } else {
                ""
            }
            error(
                "No Spotify Connect device found. Could not start Kainos web playback or detect Spotify." +
                    hint +
                    " Open Spotify on this computer (or another Premium device), then try again.",
            )
        }
        val preferred = devices.firstOrNull { it.isActive }
            ?: devices.firstOrNull { it.type.equals("Computer", ignoreCase = true) }
            ?: devices.first()
        return ConnectPlaybackTarget(
            deviceId = preferred.id!!,
            hasActiveDevice = devices.any { it.isActive },
            kainosLocal = false,
        )
    }

    private suspend fun listConnectDevices(token: String): List<SpotifyDevice> =
        http.get("$API/me/player/devices") { bearerAuth(token) }
            .successBody<SpotifyDevicesResponse>("Spotify devices")
            .devices
            .filter { !it.isRestricted && !it.id.isNullOrBlank() }

    private suspend fun waitForNamedConnectDevice(token: String, name: String): SpotifyDevice? {
        repeat(12) { attempt ->
            val devices = listConnectDevices(token)
            devices.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
            if (attempt < 11) delay(1_000)
        }
        // Refresh a dead-child failure before returning the registration error.
        webPlayback.ensureDeviceReady()
        return null
    }

    private fun webPlaybackFailureMessage(): String? = when (val current = webPlayback.state.value) {
        is SpotifyWebPlaybackState.Failed -> when (val reason = current.reason) {
            SpotifyWebPlaybackFailure.LibrespotNotFound ->
                "librespot is not installed. Run scripts/install-librespot.sh, then try again."
            SpotifyWebPlaybackFailure.LibrespotAuthenticationRequired ->
                "Spotify in-app playback needs a one-time librespot sign-in. Open Settings and select Set up in-app playback."
            is SpotifyWebPlaybackFailure.LibrespotExited ->
                reason.detail ?: "librespot stopped before its Spotify Connect device was ready."
            else -> null
        }
        else -> null
    }

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

    /** Used by the desktop Web Playback host token endpoint. */
    suspend fun validAccessToken(): String = accessToken()

    suspend fun missingStreamingScope(): Boolean {
        val scopes = tokens.read(ProviderId.SPOTIFY)?.scopes.orEmpty()
        return scopes.isNotEmpty() && "streaming" !in scopes
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
        if (tokenResponse.status == HttpStatusCode.BadRequest) {
            val body = runCatching { tokenResponse.bodyAsText() }.getOrNull().orEmpty()
            if (body.contains("invalid_grant")) {
                tokens.clear(ProviderId.SPOTIFY)
                _state.value = ProviderState.AUTH_REQUIRED
                error("Spotify needs to be reconnected. Open Settings and connect Spotify again.")
            }
        }
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
        when (loadProfileStatus()) {
            ProfileStatus.Ok -> Unit
            ProfileStatus.QuotaExceeded -> error(SPOTIFY_QUOTA_MESSAGE)
            ProfileStatus.Failed -> error("Spotify profile could not be loaded. Check your connection and try again.")
        }
    }

    private suspend fun loadProfileStatus(): ProfileStatus {
        val token = tokens.read(ProviderId.SPOTIFY)?.accessToken ?: return ProfileStatus.Failed
        val response = http.get("$API/me") { bearerAuth(token) }
        if (response.status.value == 429) {
            val body = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            return if ("QUOTA_EXCEEDED" in body) {
                ProfileStatus.QuotaExceeded
            } else {
                ProfileStatus.QuotaExceeded // treat other 429s the same for profile
            }
        }
        if (!response.status.isSuccess()) {
            return ProfileStatus.Failed
        }
        val me = response.body<SpotifyUser>()
        // Spotify's current profile response no longer guarantees the legacy product field.
        // Player endpoints enforce Premium eligibility, so a valid user session may attempt Connect playback.
        premium = me.id.isNotBlank()
        return ProfileStatus.Ok
    }
}

private enum class ProfileStatus { Ok, QuotaExceeded, Failed }

private const val SPOTIFY_QUOTA_MESSAGE =
    "Spotify development quota exceeded for this developer account. " +
        "Wait for the quota window to reset, avoid reloading the full liked library repeatedly, " +
        "or request Extended Quota in the Spotify Developer Dashboard."


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
    if (status.value == 429) {
        val retryAfter = headers[HttpHeaders.RetryAfter]
        val retryHint = retryAfter?.let(::spotifyRetryAfterHint).orEmpty()
        if (detail != null && "QUOTA_EXCEEDED" in detail) {
            error(SPOTIFY_QUOTA_MESSAGE + retryHint)
        }
        error("$operation was rate limited by Spotify.$retryHint")
    }
    error("$operation failed (${status.value})${detail?.let { ": $it" }.orEmpty()}")
}

private fun spotifyRetryAfterHint(value: String): String {
    val seconds = value.toLongOrNull()
    if (seconds == null) return " Spotify asked clients to retry after $value."
    val approximate = when {
        seconds >= 86_400 -> "about ${(seconds + 43_199) / 86_400} days"
        seconds >= 3_600 -> "about ${(seconds + 1_799) / 3_600} hours"
        seconds >= 60 -> "about ${(seconds + 29) / 60} minutes"
        else -> "$seconds seconds"
    }
    return " Spotify asked clients to retry after $seconds seconds ($approximate)."
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
    val kainosLocal: Boolean = false,
)

// #region agent log
private fun debugSpotifyLog(
    hypothesisId: String,
    location: String,
    message: String,
    data: Map<String, Any?>,
) {
    println("DBG[$hypothesisId] $location: $message $data")
}
// #endregion
