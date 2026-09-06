package com.universalmusic.player.data.spotify

import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.platform.createHttpClient
import com.universalmusic.player.platform.loadAppConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveSpotifyLibraryCheckTest {
    @Test
    fun savedAccountLibraryLoads() = runBlocking {
        val dir = Path.of(System.getProperty("user.home"), ".universal-music-player")
        val json = Json { ignoreUnknownKeys = true }
        val settings = json.decodeFromString<AppSettings>(Files.readString(dir.resolve("settings.json")))
        val stored = json.decodeFromString<Map<String, AuthTokens>>(Files.readString(dir.resolve("tokens.json")))
        var session = stored["SPOTIFY"]
        val store = object : TokenStore {
            override suspend fun read(provider: ProviderId) = session
            override suspend fun write(provider: ProviderId, tokens: AuthTokens) { session = tokens }
            override suspend fun clear(provider: ProviderId) { session = null }
        }
        val http = createHttpClient()
        try {
            val config = loadAppConfig().copy(spotifyClientId = settings.spotifyClientId)
            val provider = SpotifyProvider(http, store, config)
            val result = loadSpotifyLibrary(provider)
            println("Liked songs loaded: ${result.tracks.getOrNull()?.size ?: -1}")
            println("Playlists loaded: ${result.playlists.getOrNull()?.size ?: -1}")
            // Do not include API payloads or credentials in failure reports.
            assertTrue(result.tracks.isSuccess, "Live liked songs could not be decoded")
            assertTrue(result.playlists.isSuccess, "Live playlists could not be decoded")
        } finally { http.close() }
    }
}
