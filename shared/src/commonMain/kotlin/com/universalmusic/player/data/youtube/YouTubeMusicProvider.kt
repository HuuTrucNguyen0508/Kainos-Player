package com.universalmusic.player.data.youtube

import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.AlbumRef
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderCapabilities
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
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
 * Official YouTube Data API v3 adapter.
 *
 * There is no supported public YouTube Music catalog/playback API for third-party
 * players. This provider therefore exposes search and metadata only. Playback is
 * reported as unsupported rather than using unofficial InnerTube clients.
 */
class YouTubeMusicProvider(
    private val http: HttpClient,
    private val config: AppConfig,
) : MusicProvider {
    override val providerId: ProviderId = ProviderId.YOUTUBE_MUSIC
    private val _state = MutableStateFlow(
        if (config.hasYouTubeCredentials) ProviderState.AVAILABLE else ProviderState.NOT_CONFIGURED,
    )
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    override suspend fun search(query: String): SearchResult {
        val key = config.youtubeDataApiKey ?: error("YouTube Data API key is not configured")
        _state.value = ProviderState.LOADING
        return try {
            val response = http.get("https://www.googleapis.com/youtube/v3/search") {
                parameter("part", "snippet")
                parameter("q", query)
                parameter("type", "video,playlist")
                parameter("maxResults", 20)
                parameter("videoCategoryId", "10")
                parameter("key", key)
            }.body<YouTubeSearchResponse>()
            _state.value = ProviderState.AVAILABLE
            val tracks = response.items.filter { it.id.videoId != null }.map { it.toTrack() }
            val playlists = response.items.filter { it.id.playlistId != null }.map { it.toPlaylist() }
            SearchResult(tracks = tracks, playlists = playlists)
        } catch (error: Throwable) {
            _state.value = ProviderState.UNAVAILABLE
            throw error
        }
    }

    override suspend fun getTrack(id: String): Track? {
        val key = config.youtubeDataApiKey ?: return null
        val response = http.get("https://www.googleapis.com/youtube/v3/videos") {
            parameter("part", "snippet,contentDetails")
            parameter("id", id)
            parameter("key", key)
        }.body<YouTubeSearchResponse>()
        return response.items.firstOrNull()?.toTrack()
    }

    override suspend fun getAlbum(id: String): Album? = null

    override suspend fun getArtist(id: String): Artist? = null

    override suspend fun getPlaylist(id: String): Playlist? {
        val key = config.youtubeDataApiKey ?: return null
        val response = http.get("https://www.googleapis.com/youtube/v3/playlists") {
            parameter("part", "snippet,contentDetails")
            parameter("id", id)
            parameter("key", key)
        }.body<YouTubeSearchResponse>()
        return response.items.firstOrNull()?.toPlaylist()
    }

    override suspend fun getStream(track: Track): PlaybackSource? = track.sourceFor(ProviderId.YOUTUBE_MUSIC)

    override suspend fun getCapabilities(): ProviderCapabilities = ProviderCapabilities(
        search = config.hasYouTubeCredentials,
        metadata = config.hasYouTubeCredentials,
        playlists = config.hasYouTubeCredentials,
        library = false,
        playback = false,
        backgroundPlayback = false,
        losslessPlayback = false,
    )

    override suspend fun isAuthenticated(): Boolean = config.hasYouTubeCredentials
}

@Serializable
private data class YouTubeSearchResponse(
    val items: List<YouTubeItem> = emptyList(),
)

@Serializable
private data class YouTubeItem(
    val id: YouTubeId = YouTubeId(),
    val snippet: YouTubeSnippet? = null,
    val contentDetails: YouTubeContentDetails? = null,
)

@Serializable
private data class YouTubeId(
    val videoId: String? = null,
    val playlistId: String? = null,
    val kind: String? = null,
)

@Serializable
private data class YouTubeSnippet(
    val title: String = "",
    val channelTitle: String? = null,
    val thumbnails: YouTubeThumbnails? = null,
    val description: String? = null,
)

@Serializable
private data class YouTubeThumbnails(
    val high: YouTubeThumbnail? = null,
    val medium: YouTubeThumbnail? = null,
    val default: YouTubeThumbnail? = null,
)

@Serializable
private data class YouTubeThumbnail(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
private data class YouTubeContentDetails(
    val duration: String? = null,
    val itemCount: Int? = null,
)

private fun YouTubeItem.toTrack(): Track {
    val id = id.videoId ?: snippet?.title ?: "unknown"
    val thumb = snippet?.thumbnails?.high ?: snippet?.thumbnails?.medium ?: snippet?.thumbnails?.default
    val artistName = snippet?.channelTitle ?: "YouTube"
    return Track(
        canonicalId = "yt:$id",
        title = snippet?.title ?: id,
        artists = listOf(ArtistRef("yt-artist-$artistName", artistName)),
        album = AlbumRef("yt-album-$id", "YouTube Music", thumb?.toArtwork()),
        durationMs = contentDetails?.duration?.let(::parseIsoDuration),
        artwork = thumb?.toArtwork(),
        sources = listOf(
            PlaybackSource(
                provider = ProviderId.YOUTUBE_MUSIC,
                providerTrackId = id,
                quality = null,
                isPlayable = false,
                handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, id),
            ),
        ),
    )
}

private fun YouTubeItem.toPlaylist(): Playlist {
    val id = id.playlistId ?: "unknown"
    val thumb = snippet?.thumbnails?.high ?: snippet?.thumbnails?.medium
    return Playlist(
        canonicalId = "yt-playlist:$id",
        title = snippet?.title ?: id,
        description = snippet?.description,
        artwork = thumb?.toArtwork(),
        ownerName = snippet?.channelTitle,
        trackCount = contentDetails?.itemCount,
        source = ProviderEntityRef(ProviderId.YOUTUBE_MUSIC, id),
    )
}

private fun YouTubeThumbnail.toArtwork(): Artwork = Artwork(url, width, height)

private fun parseIsoDuration(value: String): Long? {
    val match = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(value) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: 0
    val minutes = match.groupValues[2].toLongOrNull() ?: 0
    val seconds = match.groupValues[3].toLongOrNull() ?: 0
    return ((hours * 3600) + (minutes * 60) + seconds) * 1000
}
