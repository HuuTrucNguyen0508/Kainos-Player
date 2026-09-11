package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.Playlist

/** Extract a Spotify playlist id from a URL, URI, or raw id. */
fun parseSpotifyPlaylistId(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    Regex("""spotify:playlist:([a-zA-Z0-9]+)""").find(value)?.groupValues?.getOrNull(1)?.let { return it }
    Regex("""open\.spotify\.com/playlist/([a-zA-Z0-9]+)""").find(value)?.groupValues?.getOrNull(1)?.let { return it }
    if (value.matches(Regex("""[a-zA-Z0-9]{22,}"""))) return value
    return null
}

/**
 * Prefer an explicit saved playlist id, then a playlist named Discover Weekly in the
 * Web API library list (user-owned copies), then null.
 */
fun resolveDiscoverWeeklyCandidate(
    playlists: List<Playlist>,
    configuredPlaylistId: String?,
): String? {
    parseSpotifyPlaylistId(configuredPlaylistId.orEmpty())?.let { return it }
    return findDiscoverWeekly(playlists)?.source?.providerEntityId
}
