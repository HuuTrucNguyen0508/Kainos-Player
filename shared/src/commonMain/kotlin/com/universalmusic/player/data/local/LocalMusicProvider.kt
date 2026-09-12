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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalMusicProvider(
    private val source: LocalTrackSource,
    private val cache: LocalLibraryScanCache? = null,
    private val configKey: () -> String = { "" },
    private val embeddedArtwork: LocalEmbeddedArtworkExtractor? = null,
) : MusicProvider {
    override val providerId: ProviderId = ProviderId.LOCAL

    private val mutableState = MutableStateFlow(ProviderState.AVAILABLE)
    override val state: StateFlow<ProviderState> = mutableState.asStateFlow()

    /** Latest platform snapshot; embedded artwork is filled in here and persisted back to [cache]. */
    private val scanned = MutableStateFlow<List<LocalTrack>>(emptyList())
    private val mutableLibraryTracks = MutableStateFlow<List<Track>>(emptyList())
    val libraryTracks: StateFlow<List<Track>> = mutableLibraryTracks.asStateFlow()

    /** Apply a persisted scan snapshot without marking the provider as loading. */
    fun applyCachedTracks(tracks: List<LocalTrack>) {
        if (tracks.isEmpty()) return
        publish(tracks.distinctBy(LocalTrack::id))
        if (mutableState.value != ProviderState.LOADING) {
            mutableState.value = ProviderState.AVAILABLE
        }
    }

    private fun publish(tracks: List<LocalTrack>): List<Track> {
        val withArt = tracks.withCachedEmbeddedArtwork()
        scanned.value = withArt
        return withArt.map(LocalTrack::toDomain).also { mutableLibraryTracks.value = it }
    }

    /** Cheap pass: attach covers already extracted on a previous run. */
    private fun List<LocalTrack>.withCachedEmbeddedArtwork(): List<LocalTrack> {
        val extractor = embeddedArtwork ?: return this
        return map { track ->
            if (track.artworkUri != null) track
            else extractor.cachedArtworkUri(track.id)?.let { track.copy(artworkUri = it) } ?: track
        }
    }

    /**
     * Embedded cover for one track, extracted now if needed. Updates the library list and the
     * scan cache so rows and later launches show it without reopening the file.
     */
    suspend fun resolveEmbeddedArtwork(track: Track): Artwork? {
        val extractor = embeddedArtwork ?: return null
        track.artwork?.let { return it }
        val source = track.sourceFor(ProviderId.LOCAL) ?: return null
        val location = (source.handle as? PlaybackHandle.Url)?.url ?: source.streamUrl ?: return null
        val uri = extractor.extractArtworkUri(source.providerTrackId, location) ?: return null
        if (applyEmbeddedArtwork(source.providerTrackId, uri)) persistScanned()
        return Artwork(uri)
    }

    /**
     * Background pass over tracks that still have no cover. Sequential and cancellable; a
     * 500-track library over SAF takes a minute or two the first time, then everything is cached.
     */
    suspend fun enrichEmbeddedArtwork() {
        val extractor = embeddedArtwork ?: return
        val pending = scanned.value.filter { it.artworkUri == null }
        if (pending.isEmpty()) return
        var dirty = 0
        for (track in pending) {
            currentCoroutineContext().ensureActive()
            val uri = runCatching { extractor.extractArtworkUri(track.id, track.location) }
                .getOrElse { if (it is CancellationException) throw it else null }
                ?: continue
            if (applyEmbeddedArtwork(track.id, uri)) dirty++
            if (dirty >= PERSIST_EVERY) {
                persistScanned()
                dirty = 0
            }
        }
        if (dirty > 0) persistScanned()
    }

    private fun applyEmbeddedArtwork(trackId: String, uri: String): Boolean {
        var changed = false
        val next = scanned.value.map { track ->
            if (track.id == trackId && track.artworkUri == null) {
                changed = true
                track.copy(artworkUri = uri)
            } else {
                track
            }
        }
        if (!changed) return false
        scanned.value = next
        mutableLibraryTracks.value = next.map(LocalTrack::toDomain)
        return true
    }

    private suspend fun persistScanned() {
        val snapshot = scanned.value
        if (snapshot.isEmpty()) return
        runCatching { cache?.write(configKey(), snapshot) }
    }

    /** Load cache for the current config key, if any. */
    suspend fun hydrateFromCache() {
        val key = configKey()
        val cached = cache?.read(key).orEmpty()
        applyCachedTracks(cached)
    }

    /** Replaces the cached library with the latest complete platform snapshot. */
    suspend fun refresh(): List<Track> {
        mutableState.value = ProviderState.LOADING
        return try {
            source.scan()
                .distinctBy(LocalTrack::id)
                .let(::publish)
                .also {
                    persistScanned()
                    mutableState.value = ProviderState.AVAILABLE
                }
        } catch (cancelled: CancellationException) {
            // Keep previous tracks / AVAILABLE when a scan is cancelled by a newer refresh.
            if (mutableLibraryTracks.value.isNotEmpty()) {
                mutableState.value = ProviderState.AVAILABLE
            } else {
                mutableState.value = ProviderState.UNAVAILABLE
            }
            throw cancelled
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

    private companion object {
        const val PERSIST_EVERY = 20
    }
}

private fun LocalTrack.toDomain(): Track {
    val artwork = artworkUri?.let(::Artwork)
    val artistRefs = artists
        .filter(String::isNotBlank)
        .distinct()
        .map { ArtistRef(canonicalId = localArtistCanonicalId(it), name = it) }
    val albumRef = album
        ?.takeIf(String::isNotBlank)
        ?.let {
            AlbumRef(
                canonicalId = localAlbumCanonicalId(it, artists, albumGroupKey),
                title = it,
                artwork = artwork,
            )
        }
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
