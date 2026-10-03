package com.universalmusic.player.platform

actual fun createSpotifyWebPlaybackHost(tokenSupplier: SpotifyTokenSupplier): SpotifyWebPlaybackHost =
    if (System.getProperty("os.name", "").contains("Linux", ignoreCase = true)) {
        JvmLibrespotPlaybackHost()
    } else {
        JvmSpotifyWebPlaybackHost(tokenSupplier)
    }

actual suspend fun ensureSpotifyConnectClientAvailable(): Boolean = ensureSpotifyDesktopClientRunning()

actual fun requiresExplicitSpotifyDevice(): Boolean = false

actual fun listenForOAuthRedirect(port: Int, path: String): String = awaitOAuthRedirect(port, path)

actual suspend fun authenticateSpotify(authorizationUrl: String, redirectUri: String): String? =
    authenticateWithLoopbackServer(authorizationUrl, redirectUri)

actual fun usesLocalOAuthListener(): Boolean = true
