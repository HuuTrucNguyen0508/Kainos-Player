package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.spotify.SpotifyRefusedException
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.PlaylistTracksPage
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackSort
import com.universalmusic.player.domain.model.inAlbumOrder
import com.universalmusic.player.platform.encodeUrl
import com.universalmusic.player.ui.components.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Where each kind of page gets its songs. Local albums, library artists and Kainos playlists follow
 * the app's own state; Spotify albums and artists come from the Web API; provider playlists page in.
 */
internal class CollectionCatalog(
    private val localTracks: StateFlow<List<Track>>,
    /** Hearted and remembered songs plus Spotify Liked: what Library lists beside local files. */
    private val streamedTracks: Flow<List<Track>>,
    private val localOnly: Flow<Boolean>,
    private val playlists: StateFlow<List<PersistedKainosPlaylist>>,
    private val playlistEditor: (playlistId: String) -> PlaylistEditor?,
    private val spotifyAlbum: suspend (String) -> Album,
    private val spotifyArtistTopTracks: suspend (String) -> List<Track>,
    private val spotifyArtistAlbums: suspend (String) -> List<Album>,
    private val providerPlaylistPage: suspend (ProviderEntityRef, String?) -> PlaylistTracksPage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CollectionPages {

    override fun open(ref: CollectionRef, scope: CoroutineScope): CollectionPresenter = when (ref) {
        is CollectionRef.LocalAlbum -> CollectionPresenter(
            header = CollectionHeader(CollectionKind.Album, ref.title, SourceKind.Local, "Local album"),
            source = CollectionSource.Live(localAlbum(ref)),
            scope = scope,
            dispatcher = ioDispatcher,
        )
        is CollectionRef.LibraryArtist -> CollectionPresenter(
            header = CollectionHeader(CollectionKind.Artist, ref.name, SourceKind.Local, "Artist in your library"),
            source = CollectionSource.Live(libraryArtist(ref)),
            scope = scope,
            dispatcher = ioDispatcher,
        )
        is CollectionRef.KainosPlaylist -> CollectionPresenter(
            header = CollectionHeader(CollectionKind.Playlist, ref.title, SourceKind.Kainos, "Kainos playlist"),
            source = CollectionSource.Live(kainosPlaylist(ref)),
            scope = scope,
            dispatcher = ioDispatcher,
            editor = playlistEditor(ref.playlistId),
        )
        is CollectionRef.SpotifyAlbum -> CollectionPresenter(
            header = CollectionHeader(
                CollectionKind.Album, ref.title, SourceKind.Spotify, "Spotify album",
                artwork = ref.artwork, webUrl = "https://open.spotify.com/album/${encodeUrl(ref.albumId)}",
            ),
            source = CollectionSource.Paged { spotifyAlbumPage(ref) },
            scope = scope,
            dispatcher = ioDispatcher,
        )
        is CollectionRef.SpotifyArtist -> CollectionPresenter(
            header = spotifyArtistHeader(ref),
            source = CollectionSource.Paged { spotifyArtistPage(ref) },
            scope = scope,
            dispatcher = ioDispatcher,
        )
        is CollectionRef.ProviderPlaylist -> {
            val playlist = ref.playlist
            val provider = playlist.source.provider
            CollectionPresenter(
                header = CollectionHeader(
                    kind = CollectionKind.Playlist,
                    title = playlist.title,
                    source = SourceKind.of(provider),
                    sourceLabel = "${provider.displayName} playlist",
                    artwork = playlist.artwork,
                    byline = playlist.ownerName?.takeIf { it.isNotBlank() }?.let { "By $it" },
                    webUrl = playlist.webUrl(),
                    trackCount = playlist.trackCount,
                ),
                source = CollectionSource.Paged { cursor ->
                    val page = providerPlaylistPage(playlist.source, cursor)
                    CollectionChunk(page.tracks, next = page.next, total = page.total)
                },
                scope = scope,
                dispatcher = ioDispatcher,
            )
        }
    }

    private fun localAlbum(ref: CollectionRef.LocalAlbum): Flow<CollectionChunk> = localTracks
        .map { all ->
            val tracks = all.filter { it.album?.canonicalId == ref.albumId }.inAlbumOrder()
            val album = tracks.firstOrNull()?.album
            val artists = album?.artists.orEmpty()
                .ifEmpty { tracks.flatMap(Track::artists).distinctBy { foldedArtist(it.name) } }
                .filter { it.name.isNotBlank() }
            CollectionChunk(
                tracks = tracks,
                header = CollectionHeader(
                    kind = CollectionKind.Album,
                    title = album?.title ?: ref.title,
                    source = SourceKind.Local,
                    sourceLabel = "Local album",
                    artwork = album?.artwork ?: tracks.firstNotNullOfOrNull { it.artwork },
                    byline = artists.joinToString(", ") { it.name }.ifBlank { null },
                    bylineRef = artists.singleOrNull()?.page(),
                    year = album?.year ?: tracks.firstNotNullOfOrNull { it.year },
                ),
                notice = if (tracks.isEmpty()) "This album is no longer in your library." else null,
            )
        }
        .distinctUntilChanged()
        .flowOn(dispatcher)

    private fun libraryArtist(ref: CollectionRef.LibraryArtist): Flow<CollectionChunk> {
        val folded = foldedArtist(ref.name)
        return combine(localTracks, streamedTracks, localOnly) { local, streamed, onlyLocal ->
            val catalog = if (onlyLocal) local else (local + streamed).distinctBy(Track::canonicalId)
            val tracks = TrackSort.YEAR_DESCENDING.sort(
                catalog.filter { track -> track.artists.any { foldedArtist(it.name) == folded } },
            )
            CollectionChunk(
                tracks = tracks,
                albums = albumsOf(tracks),
                header = CollectionHeader(
                    kind = CollectionKind.Artist,
                    title = tracks.firstNotNullOfOrNull { track -> track.artists.firstOrNull { foldedArtist(it.name) == folded }?.name } ?: ref.name,
                    source = SourceKind.Local,
                    sourceLabel = "Artist in your library",
                    artwork = tracks.firstNotNullOfOrNull { it.album?.artwork ?: it.artwork },
                ),
                notice = when {
                    tracks.isNotEmpty() -> null
                    onlyLocal -> "No local files by ${ref.name}. Turn off Local files only in Library to see hearted songs too."
                    else -> "Nothing by ${ref.name} is in your library."
                },
            )
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    private fun kainosPlaylist(ref: CollectionRef.KainosPlaylist): Flow<CollectionChunk> = playlists
        .map { all -> all.firstOrNull { it.id == ref.playlistId } }
        .distinctUntilChanged()
        .map { playlist ->
            if (playlist == null) {
                CollectionChunk(emptyList(), notice = "This playlist was deleted.", entryIds = null)
            } else {
                CollectionChunk(
                    tracks = playlist.entries.map { it.track.toDomain() },
                    entryIds = playlist.entries.map { it.entryId },
                    header = CollectionHeader(CollectionKind.Playlist, playlist.title, SourceKind.Kainos, "Kainos playlist"),
                )
            }
        }
        .flowOn(dispatcher)

    private suspend fun spotifyAlbumPage(ref: CollectionRef.SpotifyAlbum): CollectionChunk {
        val album = spotifyAlbum(ref.albumId)
        val tracks = album.tracks.inAlbumOrder()
        return CollectionChunk(
            tracks = tracks,
            total = tracks.size,
            header = CollectionHeader(
                kind = CollectionKind.Album,
                title = album.title,
                source = SourceKind.Spotify,
                sourceLabel = "Spotify album",
                artwork = album.artwork ?: ref.artwork,
                byline = album.artists.joinToString(", ") { it.name }.ifBlank { null },
                bylineRef = album.artists.singleOrNull()?.page(),
                year = album.year,
                webUrl = "https://open.spotify.com/album/${encodeUrl(ref.albumId)}",
            ),
        )
    }

    /**
     * Top songs and albums load side by side. If Spotify refuses one, the other still shows with a
     * note; if it refuses both, the page fails with the reason. Nothing is guessed from names.
     */
    private suspend fun spotifyArtistPage(ref: CollectionRef.SpotifyArtist): CollectionChunk = coroutineScope {
        val top = async { runCatching { spotifyArtistTopTracks(ref.artistId) } }
        val albums = async { runCatching { spotifyArtistAlbums(ref.artistId) } }
        val songs = top.await().onFailure { if (it is CancellationException) throw it }
        val discography = albums.await().onFailure { if (it is CancellationException) throw it }
        if (songs.isFailure && discography.isFailure) throw songs.exceptionOrNull()!!
        val missing = listOfNotNull(
            songs.exceptionOrNull()?.let { "top songs" },
            discography.exceptionOrNull()?.let { "albums" },
        )
        val refused = listOfNotNull(songs.exceptionOrNull(), discography.exceptionOrNull())
            .any { it is SpotifyRefusedException }
        CollectionChunk(
            tracks = songs.getOrDefault(emptyList()),
            albums = discography.getOrDefault(emptyList()),
            total = songs.getOrNull()?.size ?: 0,
            notice = missing.takeIf { it.isNotEmpty() }?.let {
                val what = it.joinToString(" and ")
                if (refused) "Spotify does not share this artist's $what with Kainos." else "This artist's $what did not load."
            },
        )
    }

    private fun spotifyArtistHeader(ref: CollectionRef.SpotifyArtist) = CollectionHeader(
        kind = CollectionKind.Artist,
        title = ref.name,
        source = SourceKind.Spotify,
        sourceLabel = "Spotify artist",
        artwork = ref.artwork,
        webUrl = "https://open.spotify.com/artist/${encodeUrl(ref.artistId)}",
    )

    companion object {
        fun from(container: AppContainer): CollectionCatalog = CollectionCatalog(
            localTracks = container.local.libraryTracks,
            streamedTracks = combine(container.library.savedTracks, container.spotifyTracks) { saved, liked -> saved + liked },
            localOnly = container.settings.map { it.libraryLocalOnly }.distinctUntilChanged(),
            playlists = container.kainosPlaylists.playlists,
            playlistEditor = { id ->
                container.kainosPlaylists.editor(id) {
                    val queue = container.player.queueState.value
                    queue.playbackOrder().mapNotNull { queue.items.getOrNull(it)?.track }
                }
            },
            spotifyAlbum = container.spotify::getAlbumWithTracks,
            spotifyArtistTopTracks = container.spotify::getArtistTopTracks,
            spotifyArtistAlbums = container.spotify::getArtistAlbums,
            providerPlaylistPage = container::loadProviderPlaylistPage,
        )
    }
}

/** Albums among [tracks], newest first, each holding the songs of [tracks] on it. */
internal fun albumsOf(tracks: List<Track>): List<Album> = tracks
    .filter { it.album != null && it.album!!.title.isNotBlank() }
    .groupBy { it.album!!.canonicalId }
    .map { (id, songs) ->
        val ordered = songs.inAlbumOrder()
        val ref = ordered.first().album!!
        Album(
            canonicalId = id,
            title = ref.title,
            artists = ref.artists.ifEmpty { ordered.flatMap(Track::artists).distinctBy { foldedArtist(it.name) } },
            artwork = ref.artwork ?: ordered.firstNotNullOfOrNull { it.artwork },
            year = ref.year ?: ordered.firstNotNullOfOrNull { it.year },
            tracks = ordered,
            sources = listOf(
                ProviderEntityRef(
                    if (id.startsWith("spotify-album:")) ProviderId.SPOTIFY else ProviderId.LOCAL,
                    id,
                ),
            ),
        )
    }
    .sortedWith(compareByDescending<Album> { it.year ?: Int.MIN_VALUE }.thenBy { it.title.lowercase() })

/** The editor a Kainos playlist page uses; [queue] reads the songs Add the current queue adds. */
internal fun KainosPlaylistRepository.editor(playlistId: String, queue: () -> List<Track>): PlaylistEditor = PlaylistEditor(
    remove = { entryId ->
        val entries = get(playlistId)?.entries.orEmpty()
        val index = entries.indexOfFirst { it.entryId == entryId }
        if (index < 0) null else {
            removeEntry(playlistId, entryId)
            entries[index] to index
        }
    },
    restore = { entry, index -> restoreEntry(playlistId, entry, index) },
    reorder = { ids -> reorderEntries(playlistId, ids) },
    addCurrentQueue = {
        val tracks = queue()
        if (tracks.isNotEmpty()) addTracks(playlistId, tracks)
        tracks.size
    },
)

internal fun com.universalmusic.player.domain.model.Playlist.webUrl(): String? {
    val id = encodeUrl(source.providerEntityId)
    return when (source.provider) {
        ProviderId.YOUTUBE_MUSIC -> "https://www.youtube.com/playlist?list=$id"
        ProviderId.SPOTIFY -> "https://open.spotify.com/playlist/$id"
        else -> null
    }
}
