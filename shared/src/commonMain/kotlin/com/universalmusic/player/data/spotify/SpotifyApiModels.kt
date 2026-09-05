package com.universalmusic.player.data.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SpotifyPaging<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
)

@Serializable
internal data class SpotifySearchResponse(
    val tracks: SpotifyPaging<SpotifyTrack>? = null,
    val albums: SpotifyPaging<SpotifyAlbum>? = null,
    val artists: SpotifyPaging<SpotifyArtist>? = null,
    val playlists: SpotifyPaging<SpotifyPlaylist>? = null,
)

@Serializable
internal data class SpotifyTrack(
    val id: String,
    val name: String,
    val duration_ms: Long? = null,
    val explicit: Boolean = false,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    val external_ids: SpotifyExternalIds? = null,
    val uri: String? = null,
    val is_playable: Boolean? = null,
)

@Serializable
internal data class SpotifyExternalIds(
    val isrc: String? = null,
)

@Serializable
internal data class SpotifyAlbum(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val images: List<SpotifyImage> = emptyList(),
    val release_date: String? = null,
    val tracks: SpotifyPaging<SpotifyTrack>? = null,
)

@Serializable
internal data class SpotifyArtist(
    val id: String,
    val name: String,
    val images: List<SpotifyImage> = emptyList(),
)

@Serializable
internal data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val owner: SpotifyOwner? = null,
    val tracks: SpotifyPlaylistTracks? = null,
)

@Serializable
internal data class SpotifyPlaylistTracks(
    val total: Int? = null,
    val items: List<SpotifyPlaylistTrack>? = null,
)

@Serializable
internal data class SpotifyPlaylistTrack(
    val track: SpotifyTrack? = null,
)

@Serializable
internal data class SpotifyOwner(
    val display_name: String? = null,
)

@Serializable
internal data class SpotifyImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
internal data class SpotifySavedTrack(
    val track: SpotifyTrack,
)

@Serializable
internal data class SpotifyUser(
    val id: String,
    val display_name: String? = null,
    val product: String? = null,
)

@Serializable
internal data class SpotifyTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    val scope: String? = null,
)

@Serializable
internal data class SpotifyPlayRequest(
    val uris: List<String>,
)
