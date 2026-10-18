package com.universalmusic.player.app

import com.universalmusic.player.platform.platformLabel

internal class AppForegroundState(platform: String = platformLabel()) {
    @kotlin.concurrent.Volatile
    var isVisible = false

    fun onReady(onForeground: () -> Unit) {
        if (isVisible) onForeground()
    }
}
