package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.playlist.toDomainPlaylist
import com.universalmusic.player.data.spotify.isDiscoverWeekly
import com.universalmusic.player.domain.matching.FuzzyMatcher
import com.universalmusic.player.domain.matching.TrackNormalizer
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackSort

internal data class LibraryArtistEntry(
    val artist: Artist,
    val tracks: List<Track>,
)

internal fun libraryNeedle(query: String): String = TrackNormalizer.fold(query.trim())

internal fun libraryQueueSongs(
    local: List<Track>,
    saved: List<Track>,
    spotify: List<Track>,
    sort: TrackSort,
    localOnly: Boolean,
    favoritesOnly: Boolean,
    favorites: Set<String>,
): List<Track> {
    val catalog = if (localOnly) {
        local
    } else {
        (local + saved + spotify).distinctBy(Track::canonicalId)
    }
    val filtered = catalog.filter { !favoritesOnly || it.canonicalId in favorites }
    return sort.sort(filtered)
}

internal fun libraryDisplaySongs(queueSongs: List<Track>, needle: String): List<Track> {
    if (needle.isEmpty()) return queueSongs
    val songSearchKeys = queueSongs.map { it.librarySearchKey() }
    // Ranked like fzf; ties keep the chosen sort order (sortedByDescending is stable).
    return queueSongs.indices
        .mapNotNull { i -> songSearchKeys[i].score(needle)?.let { i to it } }
        .sortedByDescending { it.second }
        .map { queueSongs[it.first] }
}

internal fun libraryLocalAlbums(
    localTracks: List<Track>,
    favoritesOnly: Boolean,
    favorites: Set<String>,
): List<Album> = localTracks
    .filter { it.album != null }
    .filter { !favoritesOnly || it.canonicalId in favorites }
    .groupBy { it.album!!.canonicalId }
    .map { (id, tracks) ->
        val ref = tracks.first().album!!
        Album(
            canonicalId = id,
            title = ref.title,
            artists = tracks.flatMap(Track::artists).distinctBy { it.canonicalId },
            artwork = ref.artwork,
            year = ref.year,
            tracks = tracks,
            sources = listOf(ProviderEntityRef(ProviderId.LOCAL, id)),
        )
    }
    .sortedBy { it.title.lowercase() }

internal fun libraryFilteredAlbums(albums: List<Album>, needle: String): List<Album> =
    if (needle.isEmpty()) albums else albums.filter { it.matchesLibraryQuery(needle) }

internal fun libraryLocalArtists(
    localTracks: List<Track>,
    favoritesOnly: Boolean,
    favorites: Set<String>,
): List<LibraryArtistEntry> = localTracks
    .filter { !favoritesOnly || it.canonicalId in favorites }
    .flatMap { track -> track.artists.map { artist -> artist to track } }
    .groupBy { (artist, _) -> artist.canonicalId }
    .map { (id, pairs) ->
        val ref = pairs.first().first
        LibraryArtistEntry(
            artist = Artist(
                canonicalId = id,
                name = ref.name,
                artwork = ref.artwork,
                sources = listOf(ProviderEntityRef(ProviderId.LOCAL, id)),
            ),
            tracks = pairs.map { it.second }.distinctBy(Track::canonicalId),
        )
    }
    .sortedBy { it.artist.name.lowercase() }

internal fun libraryFilteredArtists(
    artists: List<LibraryArtistEntry>,
    needle: String,
): List<LibraryArtistEntry> = if (needle.isEmpty()) {
    artists
} else {
    artists.filter { FuzzyMatcher.matches(needle, TrackNormalizer.fold(it.artist.name)) }
}

internal fun libraryFilteredKainosPlaylists(
    rows: List<PersistedKainosPlaylist>,
    needle: String,
    favoritesOnly: Boolean,
): List<Playlist> {
    if (favoritesOnly) return emptyList()
    val domain = rows.map { it.toDomainPlaylist() }
    return if (needle.isEmpty()) domain else domain.filter { it.matchesLibraryQuery(needle) }
}

internal fun libraryFilteredSpotifyPlaylists(
    playlists: List<Playlist>,
    needle: String,
    localOnly: Boolean,
    favoritesOnly: Boolean,
): List<Playlist> {
    if (localOnly || favoritesOnly) return emptyList()
    val filtered = if (needle.isEmpty()) playlists else playlists.filter { it.matchesLibraryQuery(needle) }
    return filtered.sortedByDescending { it.isDiscoverWeekly() }
}

internal fun libraryLocalCount(queueSongs: List<Track>): Int =
    queueSongs.count { it.sources.any { s -> s.provider == ProviderId.LOCAL } }

internal fun librarySpotifyCount(queueSongs: List<Track>): Int =
    queueSongs.count { it.sources.any { s -> s.provider == ProviderId.SPOTIFY } }

internal fun libraryPinnedKeys(pins: List<PersistedHomePin>): Set<String> =
    pins.map { "${it.kind.name}|${it.targetId}" }.toSet()

/** Folded title and full "title artists album" text, computed once per list for type-to-search. */
private class LibrarySearchKey(val title: String, val full: String) {
    fun score(needle: String): Int? {
        val score = FuzzyMatcher.score(needle, full) ?: return null
        // Prefer hits that land entirely in the title over ones spread across artist/album.
        return if (FuzzyMatcher.matches(needle, title)) score + TITLE_BONUS else score
    }

    private companion object {
        const val TITLE_BONUS = 50
    }
}

private fun Track.librarySearchKey(): LibrarySearchKey = LibrarySearchKey(
    title = TrackNormalizer.fold(title),
    full = TrackNormalizer.fold(
        listOfNotNull(title, artists.joinToString(" ") { it.name }, album?.title).joinToString(" "),
    ),
)

private fun Track.matchesLibraryQuery(needle: String): Boolean =
    needle.isEmpty() || librarySearchKey().score(needle) != null

private fun Album.matchesLibraryQuery(needle: String): Boolean {
    if (needle.isEmpty()) return true
    val text = TrackNormalizer.fold(listOf(title, artists.joinToString(" ") { it.name }).joinToString(" "))
    return FuzzyMatcher.matches(needle, text) || tracks.any { it.matchesLibraryQuery(needle) }
}

private fun Playlist.matchesLibraryQuery(needle: String): Boolean {
    if (needle.isEmpty()) return true
    val text = TrackNormalizer.fold(listOfNotNull(title, ownerName, description).joinToString(" "))
    return FuzzyMatcher.matches(needle, text)
}
