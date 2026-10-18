package com.universalmusic.player.app

internal class ForegroundHandoff(private val request: () -> Unit) {
    private val lock = Any()
    private var enabled = false
    private var exchangeSettled = false

    fun enable() = synchronized(lock) {
        if (!enabled) {
            enabled = true
            if (exchangeSettled) request()
        }
    }

    fun onForeground() = synchronized(lock) {
        exchangeSettled = false
    }

    fun onExchangeCompleted() = synchronized(lock) {
        exchangeSettled = true
        if (enabled) request()
    }

    fun onPeerChanged() = synchronized(lock) {
        if (enabled && exchangeSettled) request()
    }
}
