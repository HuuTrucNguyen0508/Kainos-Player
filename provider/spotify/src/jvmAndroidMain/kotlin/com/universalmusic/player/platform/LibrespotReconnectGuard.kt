package com.universalmusic.player.platform

/** librespot 0.2.0 stops (and interrupts) its own receiver before refreshing the AP pool. */
internal fun installLibrespotReconnectGuard(thread: Thread, recover: (Throwable) -> Unit) {
    if (thread.name != "session-packet-receiver" || thread.uncaughtExceptionHandler is ReconnectGuard) return
    thread.uncaughtExceptionHandler = ReconnectGuard(thread.uncaughtExceptionHandler, recover)
}

private class ReconnectGuard(
    private val delegate: Thread.UncaughtExceptionHandler,
    private val recover: (Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        if (error is InterruptedException &&
            error.stackTrace.any { it.className == "xyz.gianlu.librespot.core.ApResolver" && it.methodName == "request" } &&
            error.stackTrace.any { it.className == "xyz.gianlu.librespot.core.Session" && it.methodName == "reconnect" }
        ) recover(error) else delegate.uncaughtException(thread, error)
    }
}
