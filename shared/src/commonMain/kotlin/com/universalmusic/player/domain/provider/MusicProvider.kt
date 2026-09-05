package com.universalmusic.player.domain.provider

import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderCapabilities
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.SearchResult
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

interface MusicProvider {
    val providerId: ProviderId
    val state: StateFlow<ProviderState>

    suspend fun search(query: String): SearchResult

    suspend fun getTrack(id: String): Track?

    suspend fun getAlbum(id: String): Album?

    suspend fun getArtist(id: String): Artist?

    suspend fun getPlaylist(id: String): Playlist?

    suspend fun getStream(track: Track): PlaybackSource?

    suspend fun getCapabilities(): ProviderCapabilities

    suspend fun getLibraryTracks(): List<Track> = emptyList()

    suspend fun getUserPlaylists(): List<Playlist> = emptyList()

    suspend fun isAuthenticated(): Boolean = false
}

interface AuthenticatingProvider : MusicProvider {
    suspend fun beginLogin(): AuthSession
    suspend fun completeLogin(redirectUri: String)
    suspend fun logout()
}

data class AuthSession(
    val authorizationUrl: String,
    val redirectScheme: String,
)
