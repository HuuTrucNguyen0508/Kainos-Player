package com.universalmusic.player.data.catalog

import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.AlbumRef
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local demonstration catalog used when no music service is connected.
 * Streams are royalty-free SoundHelix examples — not Spotify/YouTube audio.
 */
class SampleCatalogProvider : MusicProvider {
    override val providerId: ProviderId = ProviderId.SAMPLE
    override val state: StateFlow<ProviderState> = MutableStateFlow(ProviderState.AVAILABLE)

    override suspend fun search(query: String): SearchResult {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return SearchResult()
        return SearchResult(
            tracks = tracks.filter { it.matches(needle) },
            albums = albums.filter { it.title.lowercase().contains(needle) || it.artists.any { artist -> artist.name.lowercase().contains(needle) } },
            artists = artists.filter { it.name.lowercase().contains(needle) },
            playlists = playlists.filter { it.title.lowercase().contains(needle) },
        )
    }

    override suspend fun getTrack(id: String): Track? = tracks.firstOrNull { it.canonicalId == id }

    override suspend fun getAlbum(id: String): Album? = albums.firstOrNull { it.canonicalId == id }

    override suspend fun getArtist(id: String): Artist? = artists.firstOrNull { it.canonicalId == id }

    override suspend fun getPlaylist(id: String): Playlist? = playlists.firstOrNull { it.canonicalId == id }

    override suspend fun getStream(track: Track): PlaybackSource? =
        track.sources.firstOrNull { it.provider == ProviderId.SAMPLE && it.isPlayable }

    override suspend fun getCapabilities(): ProviderCapabilities = ProviderCapabilities(
        search = true,
        metadata = true,
        playlists = true,
        library = true,
        playback = true,
        backgroundPlayback = true,
        losslessPlayback = false,
    )

    override suspend fun getLibraryTracks(): List<Track> = tracks

    override suspend fun getUserPlaylists(): List<Playlist> = playlists

    val homeAlbums: List<Album> get() = albums
    val homeArtists: List<Artist> get() = artists
    val homePlaylists: List<Playlist> get() = playlists
    val allTracks: List<Track> get() = tracks

    private fun Track.matches(needle: String): Boolean =
        title.lowercase().contains(needle) ||
            artists.any { it.name.lowercase().contains(needle) } ||
            album?.title?.lowercase()?.contains(needle) == true
}

private fun art(seed: String) = Artwork("https://picsum.photos/seed/$seed/640/640")

private val helix = listOf(
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-7.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-9.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-10.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-11.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-12.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-13.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-14.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-15.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-16.mp3",
)

private fun sampleSource(id: String, url: String, bitrate: Int = 192) = PlaybackSource(
    provider = ProviderId.SAMPLE,
    providerTrackId = id,
    streamUrl = url,
    quality = AudioQuality(QualityTier.HIGH, codec = "mp3", bitrateKbps = bitrate),
    isPlayable = true,
    handle = PlaybackHandle.Url(url),
)

private fun informational(provider: ProviderId, id: String, bitrate: Int, tier: QualityTier, playable: Boolean) =
    PlaybackSource(
        provider = provider,
        providerTrackId = id,
        quality = AudioQuality(tier, bitrateKbps = bitrate),
        isPlayable = playable,
        handle = PlaybackHandle.ProviderPlayback(provider, id),
    )

private fun demoTrack(
    id: String,
    title: String,
    artist: String,
    album: String,
    durationMs: Long,
    urlIndex: Int,
    isrc: String? = null,
    explicit: Boolean = false,
    year: Int = 2024,
    spotifyBitrate: Int = 320,
    youtubeBitrate: Int = 256,
): Track {
    val artistRef = ArtistRef("sample-artist-${artist.lowercase()}", artist)
    val albumRef = AlbumRef("sample-album-${album.lowercase()}", album, art(album), year)
    val url = helix[urlIndex % helix.size]
    return Track(
        canonicalId = "sample:$id",
        title = title,
        artists = listOf(artistRef),
        album = albumRef,
        durationMs = durationMs,
        artwork = art(id),
        explicit = explicit,
        isrc = isrc,
        sources = listOf(
            sampleSource(id, url),
            informational(ProviderId.SPOTIFY, "sp-$id", spotifyBitrate, QualityTier.HIGH, playable = false),
            informational(ProviderId.YOUTUBE_MUSIC, "yt-$id", youtubeBitrate, QualityTier.HIGH, playable = false),
        ),
    )
}

private val tracks = listOf(
    demoTrack("eitrp", "Northbound Signal", "Atlas Line", "Night Trains", 251_000, 0, isrc = "QZSAMPLE0001"),
    demoTrack("lotus", "Paper Lanterns", "Mira Vale", "Paper Lanterns", 214_000, 1, isrc = "QZSAMPLE0002"),
    demoTrack("harbor", "Harbor Lights", "Cedar & Copper", "Civic Evening", 198_000, 2, isrc = "QZSAMPLE0003"),
    demoTrack("glass", "Glasshouse", "Ivy Circuit", "Warm Static", 267_000, 3, isrc = "QZSAMPLE0004"),
    demoTrack("coral", "Coral Radio", "Low Tide Club", "After Hours", 233_000, 4, isrc = "QZSAMPLE0005"),
    demoTrack("kiln", "Kiln", "Red Oak Room", "Fireside", 189_000, 5, isrc = "QZSAMPLE0006"),
    demoTrack("silver", "Silver Thread", "Mira Vale", "Paper Lanterns", 246_000, 6, isrc = "QZSAMPLE0007"),
    demoTrack("violet", "Violet Hour", "Atlas Line", "Night Trains", 274_000, 7, isrc = "QZSAMPLE0008"),
    demoTrack("loom", "The Loom", "Ivy Circuit", "Warm Static", 221_000, 8, isrc = "QZSAMPLE0009"),
    demoTrack("ember", "Ember Waltz", "Red Oak Room", "Fireside", 205_000, 9, isrc = "QZSAMPLE0010"),
    demoTrack("drift", "Driftwood", "Low Tide Club", "After Hours", 258_000, 10, isrc = "QZSAMPLE0011"),
    demoTrack("civic", "Civic Evening", "Cedar & Copper", "Civic Evening", 192_000, 11, isrc = "QZSAMPLE0012"),
    demoTrack("lotus-live", "Paper Lanterns - Live", "Mira Vale", "Paper Lanterns", 241_000, 12, isrc = "QZSAMPLE0013"),
    demoTrack("glass-remix", "Glasshouse - Remix", "Ivy Circuit", "Warm Static", 272_000, 13, isrc = "QZSAMPLE0014"),
    demoTrack("harbor-acoustic", "Harbor Lights - Acoustic", "Cedar & Copper", "Civic Evening", 186_000, 14, isrc = "QZSAMPLE0015"),
    demoTrack("signal-radio", "Northbound Signal - Radio Edit", "Atlas Line", "Night Trains", 201_000, 15, isrc = "QZSAMPLE0016"),
)

private val artists = tracks
    .flatMap { it.artists }
    .distinctBy { it.canonicalId }
    .map { Artist(it.canonicalId, it.name, it.artwork ?: art(it.name)) }

private val albums = tracks
    .groupBy { it.album?.canonicalId }
    .mapNotNull { (_, albumTracks) ->
        val first = albumTracks.first()
        val album = first.album ?: return@mapNotNull null
        Album(
            canonicalId = album.canonicalId,
            title = album.title,
            artists = first.artists,
            artwork = album.artwork,
            year = album.year,
            tracks = albumTracks,
            sources = listOf(ProviderEntityRef(ProviderId.SAMPLE, album.canonicalId)),
        )
    }

private val playlists = listOf(
    Playlist(
        canonicalId = "sample-playlist-evening",
        title = "Evening lines",
        description = "Quiet room, warm speakers.",
        artwork = art("evening"),
        ownerName = "Kainos Player",
        trackCount = 6,
        tracks = tracks.take(6),
        source = ProviderEntityRef(ProviderId.SAMPLE, "evening"),
    ),
    Playlist(
        canonicalId = "sample-playlist-focus",
        title = "Focus kiln",
        description = "Instrumental motion without a hard edge.",
        artwork = art("focus"),
        ownerName = "Kainos Player",
        trackCount = 5,
        tracks = tracks.drop(6).take(5),
        source = ProviderEntityRef(ProviderId.SAMPLE, "focus"),
    ),
)
