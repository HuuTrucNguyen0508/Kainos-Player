package com.universalmusic.player.data.soundcloud

import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderCapabilities
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.SearchResult
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.provider.MusicProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Official SoundCloud API adapter.
 *
 * SoundCloud's public API access is application-approved. Without a client ID the
 * provider stays NOT_CONFIGURED. Streams are used only when the official API
 * returns a progressive HTTP URL.
 */
class SoundCloudProvider(
    private val http: HttpClient,
    private val config: AppConfig,
) : MusicProvider {
    override val providerId: ProviderId = ProviderId.SOUNDCLOUD
    private val _state = MutableStateFlow(
        if (config.hasSoundCloudCredentials) ProviderState.AVAILABLE else ProviderState.NOT_CONFIGURED,
    )
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    override suspend fun search(query: String): SearchResult {
        val clientId = config.soundCloudClientId ?: error("SoundCloud client ID is not configured")
        _state.value = ProviderState.LOADING
        return try {
            val tracks = http.get("https://api.soundcloud.com/tracks") {
                parameter("q", query)
                parameter("client_id", clientId)
                parameter("limit", 20)
            }.body<List<SoundCloudTrack>>()
            _state.value = ProviderState.AVAILABLE
            SearchResult(tracks = tracks.map { it.toDomain(clientId) })
        } catch (error: Throwable) {
            _state.value = ProviderState.UNAVAILABLE
            throw error
        }
    }

    override suspend fun getTrack(id: String): Track? {
        val clientId = config.soundCloudClientId ?: return null
        return http.get("https://api.soundcloud.com/tracks/$id") {
            parameter("client_id", clientId)
        }.body<SoundCloudTrack>().toDomain(clientId)
    }

    override suspend fun getAlbum(id: String): Album? = null

    override suspend fun getArtist(id: String): Artist? {
        val clientId = config.soundCloudClientId ?: return null
        val user = http.get("https://api.soundcloud.com/users/$id") {
            parameter("client_id", clientId)
        }.body<SoundCloudUser>()
        return Artist(
            canonicalId = "sc-artist:${user.id}",
            name = user.username,
            artwork = user.avatar_url?.let { Artwork(it) },
            sources = listOf(ProviderEntityRef(ProviderId.SOUNDCLOUD, user.id.toString())),
        )
    }

    override suspend fun getPlaylist(id: String): Playlist? {
        val clientId = config.soundCloudClientId ?: return null
        val playlist = http.get("https://api.soundcloud.com/playlists/$id") {
            parameter("client_id", clientId)
        }.body<SoundCloudPlaylist>()
        return playlist.toDomain(clientId)
    }

    override suspend fun getStream(track: Track): PlaybackSource? = track.sourceFor(ProviderId.SOUNDCLOUD)

    override suspend fun getCapabilities(): ProviderCapabilities = ProviderCapabilities(
        search = config.hasSoundCloudCredentials,
        metadata = config.hasSoundCloudCredentials,
        playlists = config.hasSoundCloudCredentials,
        library = false,
        playback = config.hasSoundCloudCredentials,
        backgroundPlayback = config.hasSoundCloudCredentials,
        losslessPlayback = false,
    )

    override suspend fun isAuthenticated(): Boolean = config.hasSoundCloudCredentials
}

@Serializable
private data class SoundCloudTrack(
    val id: Long,
    val title: String,
    val duration: Long? = null,
    val artwork_url: String? = null,
    val user: SoundCloudUser? = null,
    val stream_url: String? = null,
    val permalink_url: String? = null,
)

@Serializable
private data class SoundCloudUser(
    val id: Long,
    val username: String,
    val avatar_url: String? = null,
)

@Serializable
private data class SoundCloudPlaylist(
    val id: Long,
    val title: String,
    val description: String? = null,
    val artwork_url: String? = null,
    val user: SoundCloudUser? = null,
    val track_count: Int? = null,
    val tracks: List<SoundCloudTrack> = emptyList(),
)

private fun SoundCloudTrack.toDomain(clientId: String): Track {
    val stream = stream_url?.let { "$it?client_id=$clientId" }
    return Track(
        canonicalId = "soundcloud:$id",
        title = title,
        artists = listOf(ArtistRef("sc-artist-${user?.id}", user?.username ?: "SoundCloud")),
        durationMs = duration,
        artwork = artwork_url?.replace("-large", "-t500x500")?.let { Artwork(it) },
        sources = listOf(
            PlaybackSource(
                provider = ProviderId.SOUNDCLOUD,
                providerTrackId = id.toString(),
                streamUrl = stream,
                quality = if (stream != null) AudioQuality(QualityTier.STANDARD, codec = "mp3", bitrateKbps = 128) else null,
                isPlayable = stream != null,
                handle = if (stream != null) {
                    PlaybackHandle.Url(stream)
                } else {
                    PlaybackHandle.ProviderPlayback(ProviderId.SOUNDCLOUD, id.toString())
                },
            ),
        ),
    )
}

private fun SoundCloudPlaylist.toDomain(clientId: String): Playlist = Playlist(
    canonicalId = "soundcloud-playlist:$id",
    title = title,
    description = description,
    artwork = artwork_url?.let { Artwork(it) },
    ownerName = user?.username,
    trackCount = track_count,
    tracks = tracks.map { it.toDomain(clientId) },
    source = ProviderEntityRef(ProviderId.SOUNDCLOUD, id.toString()),
)
