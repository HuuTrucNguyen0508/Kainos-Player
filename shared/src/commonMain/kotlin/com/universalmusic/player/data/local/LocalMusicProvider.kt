package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.AlbumRef
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderCapabilities
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.SearchResult
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.provider.MusicProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalMusicProvider(
    private val source: LocalTrackSource,
) : MusicProvider {
    override val providerId: ProviderId = ProviderId.LOCAL

    private val mutableState = MutableStateFlow(ProviderState.AVAILABLE)
    override val state: StateFlow<ProviderState> = mutableState.asStateFlow()

    private val mutableLibraryTracks = MutableStateFlow<List<Track>>(emptyList())
    val libraryTracks: StateFlow<List<Track>> = mutableLibraryTracks.asStateFlow()

    /** Replaces the cached library with the latest complete platform snapshot. */
    suspend fun refresh(): List<Track> {
        mutableState.value = ProviderState.LOADING
        return try {
            source.scan()
                .distinctBy(LocalTrack::id)
                .map(LocalTrack::toDomain)
                .also {
                    mutableLibraryTracks.value = it
                    mutableState.value = ProviderState.AVAILABLE
                }
        } catch (error: Throwable) {
            mutableState.value = ProviderState.UNAVAILABLE
            throw error
        }
    }

    override suspend fun search(query: String): SearchResult {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return SearchResult()
        return SearchResult(tracks = libraryTracks.value.filter { it.matches(needle) })
    }

    override suspend fun getTrack(id: String): Track? = libraryTracks.value.firstOrNull { track ->
        track.canonicalId == id || track.sourceFor(ProviderId.LOCAL)?.providerTrackId == id
    }

    override suspend fun getAlbum(id: String): Album? = null

    override suspend fun getArtist(id: String): Artist? = null

    override suspend fun getPlaylist(id: String): Playlist? = null

    override suspend fun getStream(track: Track): PlaybackSource? =
        track.sourceFor(ProviderId.LOCAL)?.takeIf(PlaybackSource::isPlayable)

    override suspend fun getCapabilities(): ProviderCapabilities = ProviderCapabilities(
        search = true,
        metadata = true,
        playlists = false,
        library = true,
        playback = true,
        backgroundPlayback = true,
        losslessPlayback = true,
    )

    override suspend fun getLibraryTracks(): List<Track> = libraryTracks.value

    private fun Track.matches(needle: String): Boolean =
        title.lowercase().contains(needle) ||
            artists.any { it.name.lowercase().contains(needle) } ||
            album?.title?.lowercase()?.contains(needle) == true
}

private fun LocalTrack.toDomain(): Track {
    val artwork = artworkUri?.let(::Artwork)
    val artistRefs = artists
        .filter(String::isNotBlank)
        .distinct()
        .map { ArtistRef(canonicalId = "local-artist:${it.lowercase()}", name = it) }
    val albumRef = album
        ?.takeIf(String::isNotBlank)
        ?.let { AlbumRef(canonicalId = "local-album:${it.lowercase()}", title = it, artwork = artwork) }
    val localSource = PlaybackSource(
        provider = ProviderId.LOCAL,
        providerTrackId = id,
        streamUrl = location,
        quality = quality,
        isPlayable = true,
        handle = PlaybackHandle.Url(location),
    )
    return Track(
        canonicalId = "local:$id",
        title = title,
        artists = artistRefs,
        album = albumRef,
        durationMs = durationMs,
        artwork = artwork,
        explicit = explicit,
        isrc = isrc,
        sources = listOf(localSource),
    )
}
