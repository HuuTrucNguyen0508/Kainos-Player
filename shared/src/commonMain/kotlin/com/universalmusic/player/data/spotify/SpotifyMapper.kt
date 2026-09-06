package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.AlbumRef
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track

internal fun SpotifyTrack.toDomain(premium: Boolean): Track {
    val artwork = album?.images?.toArtwork()
    return Track(
        canonicalId = "spotify:$id",
        title = name,
        artists = artists.map { it.toRef() },
        album = album?.toRef(),
        durationMs = duration_ms,
        artwork = artwork,
        explicit = explicit,
        isrc = external_ids?.isrc,
        sources = listOf(toSource(premium)),
    )
}

internal fun SpotifyTrack.toSource(premium: Boolean): PlaybackSource {
    // Web API does not report per-track format. Assume CD-quality Connect output (16-bit / 44.1 kHz).
    // Do not invent a lossy bitrate such as 320 kbps.
    @Suppress("UNUSED_PARAMETER")
    val unusedPremium = premium
    return PlaybackSource(
        provider = ProviderId.SPOTIFY,
        providerTrackId = id,
        quality = AudioQuality(
            tier = QualityTier.LOSSLESS,
            sampleRateHz = 44_100,
            bitDepth = 16,
        ),
        isPlayable = is_playable ?: true,
        handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, id),
    )
}

internal fun SpotifyAlbum.toDomain(premium: Boolean): Album = Album(
    canonicalId = "spotify-album:$id",
    title = name,
    artists = artists.map { it.toRef() },
    artwork = images.toArtwork(),
    year = release_date?.take(4)?.toIntOrNull(),
    tracks = tracks?.items?.map { it.toDomain(premium) }.orEmpty(),
    sources = listOf(ProviderEntityRef(ProviderId.SPOTIFY, id)),
)

internal fun SpotifyAlbum.toRef(): AlbumRef = AlbumRef(
    canonicalId = "spotify-album:$id",
    title = name,
    artwork = images.toArtwork(),
    year = release_date?.take(4)?.toIntOrNull(),
)

internal fun SpotifyArtist.toDomain(): Artist = Artist(
    canonicalId = "spotify-artist:$id",
    name = name,
    artwork = images.toArtwork(),
    sources = listOf(ProviderEntityRef(ProviderId.SPOTIFY, id)),
)

internal fun SpotifyArtist.toRef(): ArtistRef = ArtistRef(
    canonicalId = "spotify-artist:$id",
    name = name,
    artwork = images.toArtwork(),
)

internal fun SpotifyPlaylist.toDomain(premium: Boolean): Playlist = Playlist(
    canonicalId = "spotify-playlist:$id",
    title = name,
    description = description,
    artwork = images.orEmpty().toArtwork(),
    ownerName = owner?.display_name,
    trackCount = items?.total ?: tracks?.total,
    tracks = (items?.items ?: tracks?.items)?.mapNotNull { it.track?.toDomain(premium) }.orEmpty(),
    source = ProviderEntityRef(ProviderId.SPOTIFY, id),
)

internal fun List<SpotifyImage>.toArtwork(): Artwork? {
    val image = maxByOrNull { it.width ?: 0 } ?: firstOrNull() ?: return null
    return Artwork(image.url, image.width, image.height)
}
