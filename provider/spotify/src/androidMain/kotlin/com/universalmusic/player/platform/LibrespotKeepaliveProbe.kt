package com.universalmusic.player.platform

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import xyz.gianlu.librespot.core.Session

/**
 * Diagnostic: the AP connection drops on the 2-minute keepalive tick on both cellular and Wi-Fi,
 * and this records what that connection did just before, to tell whether Spotify's keepalive went
 * unanswered or the connection was cut from outside. librespot exposes no hook for it, so [attach]
 * wraps the streams of its private `Session.conn` holder (librespot 0.2.0 field names). If those
 * fields move, the probe logs once and stays off; playback never depends on it.
 */
internal class LibrespotKeepaliveProbe(private val session: Session) {
    @Volatile private var traffic: ApTrafficLog? = null
    @Volatile private var reported = false

    /** Call after the session connects and after each librespot reconnect. */
    fun attach() {
        try {
            val holder = LibrespotApSocket.connField.get(session) ?: return
            val input = inField.get(holder) as DataInputStream
            if (input is ProbedInput) return
            val log = ApTrafficLog(SystemClock::elapsedRealtime)
            outField.set(holder, DataOutputStream(log.output(outField.get(holder) as DataOutputStream)))
            inField.set(holder, ProbedInput(log.input(input)))
            traffic = log
            reported = false
        } catch (failure: Throwable) {
            if (!unavailableLogged) {
                unavailableLogged = true
                PlaybackTrace.log("Librespot", "AP keepalive probe unavailable: $failure")
            }
        }
    }

    /** One line for the trace on the first drop of this connection; null after that. */
    fun dropReport(): String? {
        val log = traffic ?: return null
        if (reported) return null
        reported = true
        return "AP traffic before the drop: ${log.report()}; ${deviceState()}"
    }

    private class ProbedInput(stream: java.io.InputStream) : DataInputStream(stream)

    private companion object {
        @Volatile var unavailableLogged = false

        val inField by lazy { LibrespotApSocket.holderField("in") }
        val outField by lazy { LibrespotApSocket.holderField("out") }

        fun deviceState(): String {
            val power = runCatching {
                androidContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            }.getOrNull() ?: return "device state unknown"
            val screen = if (power.isInteractive) "screen on" else "screen off"
            val doze = if (power.isDeviceIdleMode) "doze" else "not dozing"
            val saver = if (power.isPowerSaveMode) "battery saver on" else "battery saver off"
            return "$screen, $doze, $saver"
        }
    }
}

/**
 * The socket of librespot's AP connection, from its private `Session.conn` holder (librespot 0.2.0
 * field names). Closing it makes librespot's packet reader fail and reconnect.
 */
internal object LibrespotApSocket {
    val connField by lazy { Session::class.java.getDeclaredField("conn").apply { isAccessible = true } }
    private val socketField by lazy { holderField("socket") }

    fun holderField(name: String) =
        Class.forName("xyz.gianlu.librespot.core.Session\$ConnectionHolder")
            .getDeclaredField(name)
            .apply { isAccessible = true }

    /** Null when the fields moved or the session holds no connection. */
    fun of(session: Session): Socket? = runCatching {
        connField.get(session)?.let { socketField.get(it) as Socket }
    }.getOrNull()
}
