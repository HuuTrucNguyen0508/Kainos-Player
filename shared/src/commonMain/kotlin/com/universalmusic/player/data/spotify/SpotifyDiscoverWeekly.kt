package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId

/** Locate Spotify Discover Weekly in a loaded playlist list. */
fun findDiscoverWeekly(playlists: List<Playlist>): Playlist? =
    playlists.firstOrNull { it.isDiscoverWeekly() }

fun Playlist.isDiscoverWeekly(): Boolean =
    source.provider == ProviderId.SPOTIFY &&
        title.equals("Discover Weekly", ignoreCase = true)
