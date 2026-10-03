package com.universalmusic.player.platform.librespot

import com.spotify.Authentication
import com.universalmusic.player.platform.authenticateSpotify
import com.universalmusic.player.platform.createHttpClient
import com.universalmusic.player.platform.encodeUrl
import com.universalmusic.player.platform.secureRandomBytes
import com.universalmusic.player.platform.sha256Bytes
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.Parameters
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import xyz.gianlu.librespot.mercury.MercuryRequests

private const val REDIRECT_URI = "http://127.0.0.1:5588/login"
private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
private const val AUTH_URL = "https://accounts.spotify.com/authorize"

private val CLIENT_ID: String = runCatching { MercuryRequests.KEYMASTER_CLIENT_ID }
    .getOrDefault("65b708073fc0480ea92a077233ca87bd")

private val SCOPES = listOf(
    "app-remote-control",
    "playlist-modify",
    "playlist-modify-private",
    "playlist-modify-public",
    "playlist-read",
    "playlist-read-collaborative",
    "playlist-read-private",
    "streaming",
    "ugc-image-upload",
    "user-follow-modify",
    "user-follow-read",
    "user-library-modify",
    "user-library-read",
    "user-modify",
    "user-modify-playback-state",
    "user-modify-private",
    "user-personalized",
    "user-read-birthdate",
    "user-read-currently-playing",
    "user-read-email",
    "user-read-play-history",
    "user-read-playback-position",
    "user-read-playback-state",
    "user-read-private",
    "user-read-recently-played",
    "user-top-read",
)

private val json = Json { ignoreUnknownKeys = true }

internal object LibrespotOAuth {
    suspend fun login(): Authentication.LoginCredentials {
        val verifier = randomUrlSafe(128)
        val challenge = pkceChallenge(verifier)
        val authUrl = buildString {
            append(AUTH_URL)
            append("?response_type=code")
            append("&client_id=").append(encodeUrl(CLIENT_ID))
            append("&redirect_uri=").append(encodeUrl(REDIRECT_URI))
            append("&code_challenge=").append(encodeUrl(challenge))
            append("&code_challenge_method=S256")
            append("&scope=").append(encodeUrl(SCOPES.joinToString(" ")))
        }
        val redirect = authenticateSpotify(authUrl, REDIRECT_URI)
            ?: error("Spotify login was cancelled or did not return a redirect")
        val code = queryParam(redirect, "code")
            ?: error("Spotify login did not return an authorization code")
        queryParam(redirect, "error")?.let { error("Spotify login failed: $it") }

        val http = createHttpClient()
        try {
            val response = http.submitForm(
                url = TOKEN_URL,
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("client_id", CLIENT_ID)
                    append("redirect_uri", REDIRECT_URI)
                    append("code", code)
                    append("code_verifier", verifier)
                },
            )
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error("Spotify token exchange failed (${response.status.value}): $body")
            }
            val accessToken = json.decodeFromString<TokenResponse>(body).accessToken
            return Authentication.LoginCredentials.newBuilder()
                .setTyp(Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
                .setAuthData(com.google.protobuf.ByteString.copyFromUtf8(accessToken))
                .build()
        } finally {
            http.close()
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun pkceChallenge(verifier: String): String {
    val hash = sha256Bytes(verifier.encodeToByteArray())
    return Base64.UrlSafe.encode(hash).trimEnd('=')
}

@OptIn(ExperimentalEncodingApi::class)
private fun randomUrlSafe(length: Int): String {
    require(length > 0)
    val bytes = secureRandomBytes((length * 3 + 3) / 4)
    return Base64.UrlSafe.encode(bytes).trimEnd('=').take(length)
}

private fun queryParam(uri: String, key: String): String? {
    val query = uri.substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty()) return null
    return query.split('&').firstNotNullOfOrNull { part ->
        val (name, value) = part.split('=', limit = 2).let {
            it.firstOrNull() to it.getOrNull(1)
        }
        if (name == key) value?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) } else null
    }
}

@kotlinx.serialization.Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
)
