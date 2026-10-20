package com.universalmusic.player.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

expect fun watchDesktopEnv(scope: CoroutineScope): StateFlow<Skin?>
