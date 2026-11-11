package com.universalmusic.player.ui.screens

import com.universalmusic.player.domain.matching.TrackNormalizer
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track

/** A page of songs the app can open: an album, an artist or a playlist, from wherever it lives. */
internal sealed interface CollectionRef {
    /** Two refs with the same key are the same page; opening the page already on top does nothing. */
    val key: String
    val title: String

    /** An album of local files, keyed by the scanner's album id. */
    data class LocalAlbum(val albumId: String, override val title: String) : CollectionRef {
        override val key get() = "local-album:$albumId"
    }

    data class SpotifyAlbum(val albumId: String, override val title: String, val artwork: Artwork? = null) : CollectionRef {
        override val key get() = "spotify-album:$albumId"
    }

    /** Everything in the library credited to [name]: local files, plus hearted streams when not Local files only. */
    data class LibraryArtist(val name: String) : CollectionRef {
        override val title get() = name
        override val key get() = "library-artist:${foldedArtist(name)}"
    }

    data class SpotifyArtist(val artistId: String, val name: String, val artwork: Artwork? = null) : CollectionRef {
        override val title get() = name
        override val key get() = "spotify-artist:$artistId"
    }

    data class KainosPlaylist(val playlistId: String, override val title: String) : CollectionRef {
        override val key get() = "kainos-playlist:$playlistId"
    }

    /** A Spotify or YouTube playlist, read a page at a time. */
    data class ProviderPlaylist(val playlist: Playlist) : CollectionRef {
        override val title get() = playlist.title
        override val key get() = "provider-playlist:${playlist.canonicalId}"
    }
}

internal fun foldedArtist(name: String): String = TrackNormalizer.fold(name.trim())

private const val SPOTIFY_ALBUM = "spotify-album:"
private const val SPOTIFY_ARTIST = "spotify-artist:"

/**
 * The album page Go to album opens. A Spotify song opens its Spotify album; a local file its local
 * album. Songs with no album (most YouTube uploads) have none.
 */
internal fun Track.albumPage(): CollectionRef? {
    val album = album?.takeIf { it.title.isNotBlank() } ?: return null
    if (album.canonicalId.startsWith(SPOTIFY_ALBUM)) {
        return CollectionRef.SpotifyAlbum(album.canonicalId.removePrefix(SPOTIFY_ALBUM), album.title, album.artwork ?: artwork)
    }
    return if (sources.any { it.provider == ProviderId.LOCAL }) CollectionRef.LocalAlbum(album.canonicalId, album.title) else null
}

/** One page per credited artist: Spotify's own page for Spotify artists, the library's for the rest. */
internal fun Track.artistPages(): List<CollectionRef> = artists
    .filter { it.name.isNotBlank() }
    .map { it.page() }
    .distinctBy { it.key }

internal fun ArtistRef.page(): CollectionRef =
    if (canonicalId.startsWith(SPOTIFY_ARTIST)) {
        CollectionRef.SpotifyArtist(canonicalId.removePrefix(SPOTIFY_ARTIST), name, artwork)
    } else {
        CollectionRef.LibraryArtist(name)
    }

internal fun Artist.page(): CollectionRef =
    if (canonicalId.startsWith(SPOTIFY_ARTIST)) {
        CollectionRef.SpotifyArtist(canonicalId.removePrefix(SPOTIFY_ARTIST), name, artwork)
    } else {
        CollectionRef.LibraryArtist(name)
    }

internal fun Album.page(): CollectionRef =
    if (canonicalId.startsWith(SPOTIFY_ALBUM)) {
        CollectionRef.SpotifyAlbum(canonicalId.removePrefix(SPOTIFY_ALBUM), title, artwork)
    } else {
        CollectionRef.LocalAlbum(canonicalId, title)
    }
