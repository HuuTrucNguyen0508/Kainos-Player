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
import com.universalmusic.player.platform.sha256Bytes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

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
    private val config: AppConfig,
    private val clock: () -> Long = { currentTimeMillis() },
) : AuthenticatingProvider {
    override val providerId: ProviderId = ProviderId.SPOTIFY

    private val _state = MutableStateFlow(ProviderState.AUTH_REQUIRED)
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var pendingVerifier: String? = null
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
        runCatching { refreshIfNeeded(stored) }
            .onSuccess {
                runCatching { loadProfile() }
                _state.value = ProviderState.AVAILABLE
            }
            .onFailure {
                _state.value = ProviderState.AUTH_REQUIRED
            }
    }

    override suspend fun beginLogin(): AuthSession {
        val clientId = config.spotifyClientId ?: error("Spotify client ID is not configured")
        val verifier = randomUrlSafe(64)
        pendingVerifier = verifier
        val challenge = pkceChallenge(verifier)
        val url = buildString {
            append(AUTH_URL)
            append("?response_type=code")
            append("&client_id=").append(encode(clientId))
            append("&redirect_uri=").append(encode(config.spotifyRedirectUri))
            append("&scope=").append(encode(SCOPES.joinToString(" ")))
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(encode(challenge))
            append("&state=").append(randomUrlSafe(16))
        }
        return AuthSession(url, config.spotifyRedirectUri)
    }

    override suspend fun completeLogin(redirectUri: String) {
        val code = queryParam(redirectUri, "code") ?: error("Spotify login did not return an authorization code")
        val verifier = pendingVerifier ?: error("No Spotify login is in progress")
        val clientId = config.spotifyClientId ?: error("Spotify client ID is not configured")
        val response = http.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", config.spotifyRedirectUri)
                append("client_id", clientId)
                append("code_verifier", verifier)
            },
        ).body<SpotifyTokenResponse>()
        persist(response)
        pendingVerifier = null
        loadProfile()
        _state.value = ProviderState.AVAILABLE
    }

    override suspend fun logout() {
        tokens.clear(ProviderId.SPOTIFY)
        premium = false
        _state.value = if (config.hasSpotifyCredentials) ProviderState.AUTH_REQUIRED else ProviderState.NOT_CONFIGURED
    }

    override suspend fun isAuthenticated(): Boolean = tokens.read(ProviderId.SPOTIFY) != null && _state.value == ProviderState.AVAILABLE

    override suspend fun search(query: String): SearchResult {
        val token = accessToken()
        val response = http.get("$API/search") {
            bearerAuth(token)
            parameter("q", query)
            parameter("type", "track,album,artist,playlist")
            parameter("limit", 20)
        }.body<SpotifySearchResponse>()
        return SearchResult(
            tracks = response.tracks?.items?.map { it.toDomain(premium) }.orEmpty(),
            albums = response.albums?.items?.map { it.toDomain(premium) }.orEmpty(),
            artists = response.artists?.items?.map { it.toDomain() }.orEmpty(),
            playlists = response.playlists?.items?.map { it.toDomain(premium) }.orEmpty(),
        )
    }

    override suspend fun getTrack(id: String): Track? {
        val token = accessToken()
        return http.get("$API/tracks/$id") { bearerAuth(token) }.body<SpotifyTrack>().toDomain(premium)
    }

    override suspend fun getAlbum(id: String): Album? {
        val token = accessToken()
        return http.get("$API/albums/$id") { bearerAuth(token) }.body<SpotifyAlbum>().toDomain(premium)
    }

    override suspend fun getArtist(id: String): Artist? {
        val token = accessToken()
        return http.get("$API/artists/$id") { bearerAuth(token) }.body<SpotifyArtist>().toDomain()
    }

    override suspend fun getPlaylist(id: String): Playlist? {
        val token = accessToken()
        return http.get("$API/playlists/$id") { bearerAuth(token) }.body<SpotifyPlaylist>().toDomain(premium)
    }

    override suspend fun getStream(track: Track): PlaybackSource? {
        val source = track.sourceFor(ProviderId.SPOTIFY) ?: return null
        return if (premium) source.copy(isPlayable = true) else source.copy(isPlayable = false)
    }

    override suspend fun getLibraryTracks(): List<Track> {
        val token = accessToken()
        val response = http.get("$API/me/tracks") {
            bearerAuth(token)
            parameter("limit", 50)
        }.body<SpotifyPaging<SpotifySavedTrack>>()
        return response.items.map { it.track.toDomain(premium) }
    }

    override suspend fun getUserPlaylists(): List<Playlist> {
        val token = accessToken()
        val response = http.get("$API/me/playlists") {
            bearerAuth(token)
            parameter("limit", 50)
        }.body<SpotifyPaging<SpotifyPlaylist>>()
        return response.items.map { it.toDomain(premium) }
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
        val response = http.put("$API/me/player/play") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(SpotifyPlayRequest(uris = listOf("spotify:track:$spotifyTrackId")))
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.body<String>() }.getOrNull()
            error(body ?: "Spotify Connect playback failed (${response.status}). Start Spotify on a device first.")
        }
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
        val response = http.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", stored.refreshToken)
                append("client_id", clientId)
            },
        ).body<SpotifyTokenResponse>()
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
        val me = http.get("$API/me") { bearerAuth(token) }.body<SpotifyUser>()
        premium = me.product.equals("premium", ignoreCase = true)
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun pkceChallenge(verifier: String): String {
    val hash = sha256Bytes(verifier.encodeToByteArray())
    return Base64.UrlSafe.encode(hash).trimEnd('=')
}

internal fun randomUrlSafe(length: Int): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    return buildString(length) {
        repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }
}

internal fun encode(value: String): String = encodeUrl(value)

internal fun queryParam(uri: String, key: String): String? {
    val query = uri.substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty()) return null
    return query.split('&').firstNotNullOfOrNull { part ->
        val name = part.substringBefore('=')
        val value = part.substringAfter('=', "")
        if (name == key) value else null
    }
}
