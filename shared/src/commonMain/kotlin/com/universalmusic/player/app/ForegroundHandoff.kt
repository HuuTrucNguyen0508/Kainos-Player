package com.universalmusic.player.app

internal class ForegroundHandoff(private val request: () -> Unit) {
    private val lock = Any()
    private var enabled = false
    private var exchanged = false

    fun enable() = synchronized(lock) {
        if (!enabled) {
            enabled = true
            request()
        }
    }

    fun onForeground() = synchronized(lock) {
        exchanged = false
        if (enabled) request()
    }

    fun onExchangeCompleted() = synchronized(lock) {
        exchanged = true
        if (enabled) request()
    }

    fun onPeerChanged() = synchronized(lock) {
        if (enabled && exchanged) request()
    }
}
