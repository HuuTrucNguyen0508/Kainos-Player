package com.universalmusic.player.platform

import kotlinx.coroutines.CompletableDeferred

internal object SpotifyAuthRelay {
    var pending: CompletableDeferred<String?>? = null
}
