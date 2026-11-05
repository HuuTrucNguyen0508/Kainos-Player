package com.universalmusic.player.app

import kotlinx.coroutines.CoroutineScope

internal expect fun monitorDownloadConnectivity(scope: CoroutineScope, changed: () -> Unit)
