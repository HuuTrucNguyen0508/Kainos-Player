package com.universalmusic.player.data.sync

import kotlinx.coroutines.flow.emptyFlow

internal actual fun platformHubBrowser(): HubBrowser = HubBrowser { emptyFlow() }
