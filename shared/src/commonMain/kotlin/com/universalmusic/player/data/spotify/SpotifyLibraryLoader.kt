package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.provider.MusicProvider
import kotlinx.coroutines.CancellationException

internal data class SpotifyLibraryResult(
    val tracks: Result<List<Track>>,
    val playlists: Result<List<Playlist>>,
)

/** Each library section can still load when the other endpoint fails. */
internal suspend fun loadSpotifyLibrary(provider: MusicProvider): SpotifyLibraryResult {
    val tracks = libraryRequest { provider.getLibraryTracks() }
    val playlists = libraryRequest { provider.getUserPlaylists() }
    return SpotifyLibraryResult(tracks, playlists)
}

private suspend fun <T> libraryRequest(request: suspend () -> T): Result<T> = try {
    Result.success(request())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
