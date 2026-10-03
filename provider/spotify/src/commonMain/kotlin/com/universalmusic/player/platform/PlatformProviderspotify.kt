package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.Playlist

expect suspend fun fetchLibrespotDiscoverWeekly(playlistId: String? = null): Playlist?

expect fun requiresExplicitSpotifyDevice(): Boolean

/** Best-effort: open Spotify on this device so it can register with Connect. */

expect suspend fun ensureSpotifyConnectClientAvailable(): Boolean

expect fun listenForOAuthRedirect(port: Int, path: String = "/callback"): String

/** Opens authorization after a desktop callback listener is ready. Android returns null and uses its app link. */

expect suspend fun authenticateSpotify(authorizationUrl: String, redirectUri: String): String?

expect fun usesLocalOAuthListener(): Boolean

/** Absolute path of the platform default music folder, or blank when not applicable. */
