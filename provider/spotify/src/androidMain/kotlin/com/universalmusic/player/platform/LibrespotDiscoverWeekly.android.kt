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
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.dealer.ApiClient
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
        withMetadataSession("Discover Weekly") { session ->
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
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        PlaybackTrace.failure("Spotify", "Discover Weekly librespot fetch failed", failure)
        null
    }
}

actual suspend fun fetchLibrespotTrackRadio(trackId: String, excludeCanonicalIds: Set<String>): List<Track> =
    withContext(Dispatchers.IO) {
        require(trackId.isNotBlank() && trackId.all { it.isLetterOrDigit() }) { "bad Spotify track id" }
        check(librespotCredentials().let { it.isFile && it.length() > 0L }) {
            "no librespot credentials (connect Spotify playback first)"
        }
        withMetadataSession("Spotify radio") { session ->
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

/**
 * Runs [block] on the playback receiver's connected session, which already holds an AP connection,
 * or on a short-lived session when no receiver is up. A fresh session per call needed its own AP
 * connect, which Spotify refused on mobile data while the receiver stayed connected (every Discover
 * Weekly fetch from 2026-11-04 to 11-09 failed so). Refusals, resets, a receiver rebuilt mid-call
 * and Spotify 5xx answers are retried with backoff; each attempt takes the session again.
 */
private suspend fun <T> withMetadataSession(label: String, block: suspend (Session) -> T): T =
    retryTransient(label, isTransient = ::isTransientMetadataFailure) {
        val shared = receiverHost?.metadataSession()
        if (shared == null) {
            withFreshSession(label, block)
        } else {
            PlaybackTrace.log("Librespot", "$label: reading through the receiver's session")
            try {
                block(shared)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (receiverHost?.metadataSession() !== shared) throw ReceiverSessionReplaced(failure)
                throw failure
            }
        }
    }

private suspend fun <T> withFreshSession(label: String, block: suspend (Session) -> T): T {
    PlaybackTrace.log("Librespot", "$label: no connected receiver; opening a metadata session")
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

/** The receiver closed the session a call was using; its successor (or a fresh one) gets the retry. */
private class ReceiverSessionReplaced(cause: Throwable) :
    IOException("the playback receiver was rebuilt during the call", cause)

private fun isTransientMetadataFailure(failure: Throwable): Boolean =
    failure is ReceiverSessionReplaced ||
        isTransientNetworkFailure(failure) ||
        (failure is ApiClient.StatusCodeException && failure.code >= 500)

private fun Playlist4ApiProto.SelectedListContent.trackUris(): List<String> =
    (if (hasContents()) contents.itemsList else emptyList())
        .mapNotNull { item -> item.takeIf { it.hasUri() }?.uri }
        .filter { it.startsWith("spotify:track:") }

/**
 * Track metadata is one spclient request per track. Reading [METADATA_PARALLELISM] at a time turns a
 * 30-track Discover Weekly from 30 sequential round trips into 5 on a high-latency mobile link.
 * A track that fails is left out, but when every one fails the first failure is thrown so the
 * caller can retry (a dead connection fails them all).
 */
private suspend fun Session.tracksFor(uris: List<String>): List<Track> = coroutineScope {
    val gate = Semaphore(METADATA_PARALLELISM)
    val results = uris.map { uri ->
        async(Dispatchers.IO) {
            gate.withPermit {
                try {
                    Result.success(trackFor(uri))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
            }
        }
    }.awaitAll()
    val tracks = results.mapNotNull { it.getOrNull() }
    if (tracks.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    tracks
}

private fun Session.trackFor(uri: String): Track? {
    val trackId = runCatching { TrackId.fromUri(uri) }.getOrNull() ?: return null
    val meta = api().getMetadata4Track(trackId)
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
    return Track(
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

private const val METADATA_PARALLELISM = 6
