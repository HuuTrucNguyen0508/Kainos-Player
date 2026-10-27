package com.universalmusic.player.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val noDesktopEnv = MutableStateFlow<Skin?>(null).asStateFlow()

actual fun watchDesktopEnv(scope: CoroutineScope): StateFlow<Skin?> = noDesktopEnv
