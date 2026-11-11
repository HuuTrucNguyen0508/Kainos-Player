package com.universalmusic.player.data.db

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.spotify.SavedLikedSongs
import com.universalmusic.player.data.spotify.SavedLikedTrack
import com.universalmusic.player.data.spotify.SavedSpotifyLibrary
import com.universalmusic.player.data.spotify.SavedSpotifyPlaylist
import com.universalmusic.player.data.spotify.SpotifyLibraryStore
import com.universalmusic.player.domain.failure.StorageArea
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class DbSpotifyLibraryStore(storage: KainosStorage) : SpotifyLibraryStore {
    private val storage = AreaStorage(storage, StorageArea.LIBRARY)

    override suspend fun storedAccount(): String? = storage.read { db ->
        db.spotifyLibraryQueries.selectAccounts().executeAsList().firstOrNull()
    }

    override suspend fun load(accountId: String): SavedSpotifyLibrary? = storage.read { db ->
        val q = db.spotifyLibraryQueries
        val state = q.selectState(accountId).executeAsOneOrNull() ?: return@read null
        val liked = q.selectLiked(accountId).executeAsList().mapNotNull { row ->
            readStorageRow(strict = false) {
                SavedLikedTrack(row.spotify_track_id, row.added_at, decodeTrack(row.track_json), row.unshowable_before.toInt())
            }
        }
        val playlists = q.selectPlaylists(accountId).executeAsList().map { row ->
            SavedSpotifyPlaylist(
                playlist = Playlist(
                    canonicalId = "spotify-playlist:${row.playlist_id}",
                    title = row.title,
                    description = row.description,
                    artwork = row.artwork_url?.let(::Artwork),
                    ownerName = row.owner_name,
                    trackCount = row.track_count?.toInt(),
                    source = ProviderEntityRef(ProviderId.SPOTIFY, row.playlist_id),
                ),
                snapshotId = row.snapshot_id,
                tracksSnapshotId = row.tracks_snapshot_id,
            )
        }
        SavedSpotifyLibrary(
            accountId = accountId,
            liked = SavedLikedSongs(
                tracks = liked,
                total = state.liked_total?.toInt(),
                unmapped = state.liked_unmapped.toInt(),
                checkedAtMs = state.liked_checked_at_ms,
            ),
            playlists = playlists,
            playlistsCheckedAtMs = state.playlists_checked_at_ms,
        )
    }

    override suspend fun saveLiked(accountId: String, liked: SavedLikedSongs): Unit = storage.write { db ->
        val q = db.spotifyLibraryQueries
        db.keepOnly(accountId)
        val state = q.selectState(accountId).executeAsOneOrNull()
        q.upsertState(
            accountId,
            liked.total?.toLong(),
            liked.unmapped.toLong(),
            liked.checkedAtMs,
            state?.playlists_checked_at_ms,
        )
        q.deleteLiked(accountId)
        val savedAt = liked.checkedAtMs ?: 0L
        liked.tracks.forEachIndexed { position, entry ->
            q.insertLiked(
                accountId,
                position.toLong(),
                entry.spotifyTrackId,
                entry.addedAt,
                encodeTrack(entry.track, savedAt),
                entry.unshowableBefore.toLong(),
            )
        }
    }

    override suspend fun savePlaylists(
        accountId: String,
        playlists: List<SavedSpotifyPlaylist>,
        checkedAtMs: Long,
    ): Unit = storage.write { db ->
        val q = db.spotifyLibraryQueries
        db.keepOnly(accountId)
        val state = q.selectState(accountId).executeAsOneOrNull()
        q.upsertState(
            accountId,
            state?.liked_total,
            state?.liked_unmapped ?: 0L,
            state?.liked_checked_at_ms,
            checkedAtMs,
        )
        val savedAt = q.selectPlaylists(accountId).executeAsList().associate { it.playlist_id to it.tracks_saved_at_ms }
        q.deletePlaylists(accountId)
        playlists.distinctBy { it.id }.forEachIndexed { position, saved ->
            val playlist = saved.playlist
            q.insertPlaylist(
                accountId,
                saved.id,
                position.toLong(),
                saved.snapshotId,
                playlist.title,
                playlist.description,
                playlist.artwork?.url,
                playlist.ownerName,
                playlist.trackCount?.toLong(),
                saved.tracksSnapshotId,
                savedAt[saved.id],
            )
        }
        q.deleteOrphanPlaylistTracks(accountId)
    }

    override suspend fun playlistTracks(accountId: String, playlistId: String): List<Track>? = storage.read { db ->
        val q = db.spotifyLibraryQueries
        val playlist = q.selectPlaylists(accountId).executeAsList().firstOrNull { it.playlist_id == playlistId }
        if (playlist?.tracks_snapshot_id == null) return@read null
        q.selectPlaylistTracks(accountId, playlistId).executeAsList().mapNotNull { json ->
            readStorageRow(strict = false) { decodeTrack(json) }
        }
    }

    override suspend fun savePlaylistTracks(
        accountId: String,
        playlistId: String,
        snapshotId: String?,
        tracks: List<Track>,
        savedAtMs: Long,
    ): Unit = storage.write { db ->
        val q = db.spotifyLibraryQueries
        // A playlist that left the library (or another account) meanwhile keeps no songs.
        val listed = q.selectPlaylists(accountId).executeAsList().any { it.playlist_id == playlistId }
        if (!listed) return@write
        q.deletePlaylistTracks(accountId, playlistId)
        tracks.forEachIndexed { position, track ->
            q.insertPlaylistTrack(accountId, playlistId, position.toLong(), encodeTrack(track, savedAtMs))
        }
        // An empty snapshot id still marks the songs as stored.
        q.setPlaylistTracksSnapshot(snapshotId ?: "", savedAtMs, accountId, playlistId)
    }

    override suspend fun clear(): Unit = storage.write { db -> db.clearSpotifyLibrary() }

    /** One account's library at a time: writing another account's rows first drops the old one. */
    private fun KainosDatabase.keepOnly(accountId: String) {
        val stored = spotifyLibraryQueries.selectAccounts().executeAsList()
        if (stored.any { it != accountId }) clearSpotifyLibrary()
    }

    private fun KainosDatabase.clearSpotifyLibrary() {
        spotifyLibraryQueries.deleteAllPlaylistTracks()
        spotifyLibraryQueries.deleteAllPlaylists()
        spotifyLibraryQueries.deleteAllLiked()
        spotifyLibraryQueries.deleteAll()
    }

    private fun encodeTrack(track: Track, savedAtMs: Long): String = storageJson.encodeToString(
        StoredSpotifyTrack(
            track = track.toPersisted(savedAtMs),
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
            year = track.year,
            playable = track.sourceFor(ProviderId.SPOTIFY)?.isPlayable ?: true,
        ),
    )

    private fun decodeTrack(json: String): Track {
        val stored = storageJson.decodeFromString<StoredSpotifyTrack>(json)
        val track = stored.track.toDomain()
        return track.copy(
            trackNumber = stored.trackNumber,
            discNumber = stored.discNumber,
            year = stored.year,
            sources = track.sources.map { if (it.provider == ProviderId.SPOTIFY) it.copy(isPlayable = stored.playable) else it },
        )
    }
}

/**
 * A saved Spotify song: the shared track record, plus what Spotify lists and that record leaves out
 * (album position, release year, and whether the song plays in this market), so the Library sorts
 * and plays a saved song as it does a freshly read one.
 */
@Serializable
private data class StoredSpotifyTrack(
    val track: PersistedTrack,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val playable: Boolean = true,
)
