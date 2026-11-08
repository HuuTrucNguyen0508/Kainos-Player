package com.universalmusic.player.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val noDesktopLook = MutableStateFlow(DesktopLook()).asStateFlow()

actual fun watchDesktopLook(scope: CoroutineScope): StateFlow<DesktopLook> = noDesktopLook
