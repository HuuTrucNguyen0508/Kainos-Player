package com.universalmusic.player.platform

import android.util.Log
import com.spotify.connectstate.Connect
import com.spotify.playlist4.Playlist4ApiProto
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
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.ImageId
import xyz.gianlu.librespot.metadata.PlaylistId
import xyz.gianlu.librespot.metadata.TrackId

/**
 * Fetch Discover Weekly (or any playlist id) through librespot-java spclient.
 * Web API returns 404 for algorithmic playlists with typical developer Client IDs.
 */
actual suspend fun fetchLibrespotDiscoverWeekly(playlistId: String?): Playlist? = withContext(Dispatchers.IO) {
    val id = playlistId?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
    if (!librespotCredentials().let { it.isFile && it.length() > 0L }) {
        Log.i(TAG, "no librespot credentials; skip Discover Weekly fetch")
        return@withContext null
    }
    try {
        withMetadataSession { session ->
            val list = session.api().getPlaylist(PlaylistId.fromUri("spotify:playlist:$id"))
            val attrs = if (list.hasAttributes()) list.attributes else null
            val name = attrs?.takeIf { it.hasName() }?.name?.takeIf { it.isNotBlank() } ?: "Discover Weekly"
            val description = attrs?.takeIf { it.hasDescription() }?.description
            val artwork = attrs?.takeIf { it.hasPicture() }?.picture?.let { picture ->
                Artwork("https://i.scdn.co/image/${picture.toByteArray().toHex()}")
            }
            val tracks = session.tracksFor(list.trackUris())
            Playlist(
                canonicalId = "spotify-playlist:$id",
                title = name,
                description = description,
                artwork = artwork,
                ownerName = "Spotify",
                trackCount = tracks.size,
                tracks = tracks,
                source = ProviderEntityRef(ProviderId.SPOTIFY, id),
            )
        }
    } catch (failure: Throwable) {
        Log.w(TAG, "Discover Weekly librespot fetch failed: ${failure.message}")
        null
    }
}

actual suspend fun fetchLibrespotTrackRadio(trackId: String, excludeCanonicalIds: Set<String>): List<Track> =
    withContext(Dispatchers.IO) {
        require(trackId.isNotBlank() && trackId.all { it.isLetterOrDigit() }) { "bad Spotify track id" }
        check(librespotCredentials().let { it.isFile && it.length() > 0L }) {
            "no librespot credentials (connect Spotify playback first)"
        }
        withMetadataSession { session ->
            // seed_to_playlist answers {"mediaItems":[{"uri":"spotify:playlist:…"}]}.
            val answer = session.api().getRadioForTrack(TrackId.fromUri("spotify:track:$trackId"))
            val radioUri = answer.getAsJsonArray("mediaItems")
                ?.mapNotNull { item -> item.asJsonObject.get("uri")?.asString }
                ?.firstOrNull { it.startsWith("spotify:playlist:") }
                ?: error("Spotify returned no radio for $trackId")
            val uris = session.api().getPlaylist(PlaylistId.fromUri(radioUri)).trackUris()
                .filter { "spotify:${it.removePrefix("spotify:track:")}" !in excludeCanonicalIds }
                .take(RADIO_METADATA_LIMIT)
            session.tracksFor(uris)
        }
    }

private fun librespotCredentials() = File(androidContext.filesDir, "librespot/credentials.json")

/** A short-lived librespot session on the stored credentials, closed after [block]. */
private fun <T> withMetadataSession(block: (Session) -> T): T {
    val conf = Session.Configuration.Builder()
        .setStoreCredentials(true)
        .setStoredCredentialsFile(librespotCredentials())
        .setCacheEnabled(false)
        .build()
    val session = Session.Builder(conf)
        .setPreferredLocale(Locale.getDefault().language)
        .setDeviceType(Connect.DeviceType.SMARTPHONE)
        .setDeviceName("$KAINOS_SPOTIFY_DEVICE_NAME Metadata")
        .stored()
        .create()
    return try {
        block(session)
    } finally {
        runCatching { session.close() }
    }
}

private fun Playlist4ApiProto.SelectedListContent.trackUris(): List<String> =
    (if (hasContents()) contents.itemsList else emptyList())
        .mapNotNull { item -> item.takeIf { it.hasUri() }?.uri }
        .filter { it.startsWith("spotify:track:") }

private fun Session.tracksFor(uris: List<String>): List<Track> = uris.mapNotNull { uri ->
    val trackId = runCatching { TrackId.fromUri(uri) }.getOrNull() ?: return@mapNotNull null
    val meta = runCatching { api().getMetadata4Track(trackId) }.getOrNull() ?: return@mapNotNull null
    val base62 = uri.removePrefix("spotify:track:")
    val artists = meta.artistList.mapNotNull { artist ->
        artist.name?.takeIf { it.isNotBlank() }?.let { ArtistRef("spotify-artist:${it.lowercase()}", it) }
    }
    val cover = when {
        meta.hasAlbum() && meta.album.hasCoverGroup() ->
            ImageId.biggestImage(meta.album.coverGroup)?.hexId()?.let { "https://i.scdn.co/image/$it" }
        meta.hasAlbum() && meta.album.coverCount > 0 -> {
            val image = meta.album.getCover(0)
            if (image.hasFileId()) {
                "https://i.scdn.co/image/${image.fileId.toByteArray().toHex()}"
            } else {
                null
            }
        }
        else -> null
    }
    Track(
        canonicalId = "spotify:$base62",
        title = meta.name,
        artists = artists,
        durationMs = meta.duration.takeIf { it > 0 }?.toLong(),
        artwork = cover?.let { Artwork(it) },
        sources = listOf(
            PlaybackSource(
                provider = ProviderId.SPOTIFY,
                providerTrackId = base62,
                quality = com.universalmusic.player.domain.model.spotifyPlaybackQuality,
                isPlayable = true,
                handle = PlaybackHandle.ProviderPlayback(
                    ProviderId.SPOTIFY,
                    base62,
                    durationMs = meta.duration.takeIf { it > 0 }?.toLong(),
                ),
            ),
        ),
    )
}

private fun ByteArray.toHex(): String = joinToString("") { b -> "%02x".format(b) }

private const val TAG = "KainosDiscover"

/** Track metadata is one request per track; a radio refill never needs more than a batch. */
private const val RADIO_METADATA_LIMIT = 25
