package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId

/** Locate Spotify Discover Weekly in a loaded playlist list. */
fun findDiscoverWeekly(playlists: List<Playlist>): Playlist? =
    playlists.firstOrNull { it.isDiscoverWeekly() }

fun Playlist.isDiscoverWeekly(): Boolean {
    if (source.provider != ProviderId.SPOTIFY) return false
    if (title.equals("Discover Weekly", ignoreCase = true)) return true
    val description = description.orEmpty()
    return description.contains("weekly mixtape", ignoreCase = true) ||
        description.contains("Discover Weekly", ignoreCase = true)
}
