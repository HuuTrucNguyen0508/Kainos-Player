package com.universalmusic.player.platform

import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * Follows the mobile radio the status bar shows (5G, 4G, ...) and reports each change. Android 12
 * and later give this to any app without a permission; older versions stay silent and [label]
 * stays null.
 */
internal class CellularRadioWatch(
    private val telephony: TelephonyManager?,
    private val onChanged: (from: String, to: String) -> Unit,
) {
    @Volatile var label: String? = null
        private set
    private var callback: Any? = null

    fun start() = synchronized(this) {
        val telephony = telephony
        if (telephony == null || callback != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@synchronized
        val watch = DisplayInfoWatch()
        runCatching { telephony.registerTelephonyCallback(Runnable::run, watch) }
            .onSuccess { callback = watch }
            .onFailure { PlaybackTrace.log("Librespot", "mobile radio watch unavailable: $it") }
    }

    fun stop() = synchronized(this) {
        val watch = callback
        if (watch != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { telephony?.unregisterTelephonyCallback(watch as TelephonyCallback) }
        }
        callback = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class DisplayInfoWatch : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            val now = cellularRadioLabel(info.networkType, info.overrideNetworkType)
            val before = label
            label = now
            // Registration reports the current radio first; only later reports are changes.
            if (before != null && before != now) onChanged(before, now)
        }
    }
}
