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
import com.universalmusic.player.platform.UnavailableYouTubeStreamResolver
import com.universalmusic.player.platform.YouTubeStreamResolver
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * YouTube Data API v3 for search/metadata, plus optional yt-dlp audio URL resolution
 * for in-app playback on desktop when a stream resolver is available.
 */
class YouTubeMusicProvider(
    private val http: HttpClient,
    private var config: AppConfig,
    private val streams: YouTubeStreamResolver = UnavailableYouTubeStreamResolver,
) : MusicProvider {
    override val providerId: ProviderId = ProviderId.YOUTUBE_MUSIC
    private val _state = MutableStateFlow(
        if (config.hasYouTubeCredentials) ProviderState.AVAILABLE else ProviderState.NOT_CONFIGURED,
    )
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    fun updateConfig(next: AppConfig) {
        config = next
        _state.value = if (next.hasYouTubeCredentials) ProviderState.AVAILABLE else ProviderState.NOT_CONFIGURED
    }

    override suspend fun search(query: String): SearchResult {
        if (query.isBlank()) return SearchResult()
        return request {
            // Category filters require type=video and cannot be combined with playlist search.
            val response = fetch("search") {
                parameter("part", "snippet")
                parameter("q", query.trim())
                parameter("type", "video,playlist")
                parameter("maxResults", 20)
            }
            val videos = response.items.filter { it.resourceId("videoId") != null }
            val ids = videos.mapNotNull { it.resourceId("videoId") }
            val details = if (ids.isEmpty()) emptyMap() else fetch("videos") {
                parameter("part", "snippet,contentDetails")
                parameter("id", ids.joinToString(","))
            }.items.associateBy { it.resourceId("videoId") }
            SearchResult(
                tracks = videos.map { (details[it.resourceId("videoId")] ?: it).toTrack(streams.isAvailable()) },
                playlists = response.items.filter { it.resourceId("playlistId") != null }.map { it.toPlaylist() },
            )
        }
    }

    override suspend fun getTrack(id: String): Track? = request {
        fetch("videos") {
            parameter("part", "snippet,contentDetails")
            parameter("id", id)
        }.items.firstOrNull()?.toTrack(streams.isAvailable())
    }

    override suspend fun getAlbum(id: String): Album? = null

    override suspend fun getArtist(id: String): Artist? = null

    override suspend fun getPlaylist(id: String): Playlist? = request {
        fetch("playlists") {
            parameter("part", "snippet,contentDetails")
            parameter("id", id)
        }.items.firstOrNull()?.toPlaylist()
    }

    private suspend fun fetch(endpoint: String, parameters: HttpRequestBuilder.() -> Unit): YouTubeSearchResponse {
        val key = config.youtubeDataApiKey?.takeIf { it.isNotBlank() }
            ?: error("YouTube Data API key is not configured")
        val response = http.get("https://www.googleapis.com/youtube/v3/$endpoint") {
            // Handle errors here so URLs containing the API key never reach the UI.
            expectSuccess = false
            parameter("key", key)
            parameters()
        }
        if (!response.status.isSuccess()) {
            val reason = runCatching { response.body<YouTubeErrorResponse>().error?.errors?.firstOrNull()?.reason }.getOrNull()
            _state.value = when {
                response.status.value == 429 || reason in listOf("quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded") -> ProviderState.RATE_LIMITED
                response.status.value == 401 || reason in listOf("keyInvalid", "accessNotConfigured", "ipRefererBlocked") -> ProviderState.AUTH_REQUIRED
                else -> ProviderState.UNAVAILABLE
            }
            error(when (_state.value) {
                ProviderState.RATE_LIMITED -> "YouTube quota or rate limit reached. Try again later."
                ProviderState.AUTH_REQUIRED -> "Check your YouTube API key and enable YouTube Data API v3 in Google Cloud."
                else -> "YouTube request failed (HTTP ${response.status.value}). Check API access and try again."
            })
        }
        return response.body()
    }

    private suspend fun <T> request(block: suspend () -> T): T {
        val previous = _state.value
        _state.value = ProviderState.LOADING
        try {
            return block().also { _state.value = ProviderState.AVAILABLE }
        } catch (cancelled: CancellationException) {
            _state.value = previous
            throw cancelled
        } catch (failure: Exception) {
            if (_state.value == ProviderState.LOADING) _state.value = ProviderState.UNAVAILABLE
            // Transport exceptions may include the credential-bearing request URL.
            val message = when (_state.value) {
                ProviderState.RATE_LIMITED -> "YouTube quota or rate limit reached. Try again later."
                ProviderState.AUTH_REQUIRED -> "Check your YouTube API key and enable YouTube Data API v3 in Google Cloud."
                else -> "YouTube is unavailable. Check your connection, API key, and API access."
            }
            throw IllegalStateException(message)
        }
    }

    override suspend fun getStream(track: Track): PlaybackSource? {
        val base = track.sourceFor(ProviderId.YOUTUBE_MUSIC) ?: return null
        if (!streams.isAvailable()) return base
        val resolved = streams.resolveAudioUrl(base.providerTrackId)
            ?: error("Could not resolve a YouTube audio stream for \"${track.title}\"")
        return base.copy(
            streamUrl = resolved.url,
            quality = resolved.quality ?: base.quality,
            isPlayable = true,
            handle = PlaybackHandle.Url(resolved.url),
        )
    }

    override suspend fun getCapabilities(): ProviderCapabilities {
        val configured = config.hasYouTubeCredentials
        val playback = configured && streams.isAvailable()
        return ProviderCapabilities(
            search = configured,
            metadata = configured,
            playlists = configured,
            library = false,
            playback = playback,
            backgroundPlayback = playback,
            losslessPlayback = false,
        )
    }

    override suspend fun isAuthenticated(): Boolean = config.hasYouTubeCredentials
}

@Serializable
private data class YouTubeSearchResponse(
    val items: List<YouTubeItem> = emptyList(),
)

@Serializable
private data class YouTubeItem(
    val id: JsonElement = JsonPrimitive(""),
    val snippet: YouTubeSnippet? = null,
    val contentDetails: YouTubeContentDetails? = null,
)

private fun YouTubeItem.resourceId(field: String): String? = when (val value = id) {
    is JsonPrimitive -> value.content.takeIf { it.isNotBlank() }
    is JsonObject -> value[field]?.jsonPrimitive?.content
    else -> null
}

@Serializable
private data class YouTubeErrorResponse(val error: YouTubeApiError? = null)

@Serializable
private data class YouTubeApiError(val errors: List<YouTubeErrorReason> = emptyList())

@Serializable
private data class YouTubeErrorReason(val reason: String? = null)

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

private fun YouTubeItem.toTrack(playable: Boolean): Track {
    val id = requireNotNull(resourceId("videoId")) { "YouTube video is missing its ID" }
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
                isPlayable = playable,
                handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, id),
            ),
        ),
    )
}

private fun YouTubeItem.toPlaylist(): Playlist {
    val id = requireNotNull(resourceId("playlistId")) { "YouTube playlist is missing its ID" }
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
