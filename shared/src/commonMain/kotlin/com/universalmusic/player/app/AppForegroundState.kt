package com.universalmusic.player.app

import com.universalmusic.player.platform.platformLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AppForegroundState(platform: String = platformLabel()) {
    private val _visible = MutableStateFlow(platform == "Linux")
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    var isVisible: Boolean
        get() = _visible.value
        set(value) {
            _visible.value = value
        }

    fun onReady(onForeground: () -> Unit) {
        if (isVisible) onForeground()
    }
}
