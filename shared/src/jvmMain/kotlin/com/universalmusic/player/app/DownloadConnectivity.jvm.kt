package com.universalmusic.player.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal actual fun monitorDownloadConnectivity(scope: CoroutineScope, changed: () -> Unit) {
    scope.launch {
        while (true) {
            delay(60_000)
            changed()
        }
    }
}
