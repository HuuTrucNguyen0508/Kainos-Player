package com.universalmusic.player.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.universalmusic.player.platform.androidContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android `dataSync` FGS wrapper for long vault transfers (targetSdk 36).
 */
object AndroidVaultSyncForeground {
    @Volatile
    private var activeLabel: String? = null

    @Volatile
    private var completion: CompletableDeferred<Unit>? = null

    suspend fun <T> run(label: String, block: suspend () -> T): T {
        val app = androidContext.applicationContext
        activeLabel = label
        val done = CompletableDeferred<Unit>()
        completion = done
        val intent = Intent(app, HomeLanVaultSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
        return try {
            block()
        } finally {
            done.complete(Unit)
            completion = null
            activeLabel = null
            app.stopService(intent)
        }
    }

    internal fun currentLabel(): String = activeLabel ?: "Home library sync"

    internal suspend fun awaitWorkFinished() {
        completion?.await()
    }
}

class HomeLanVaultSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var waitJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = buildNotification(AndroidVaultSyncForeground.currentLabel())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        waitJob?.cancel()
        waitJob = scope.launch {
            runCatching { AndroidVaultSyncForeground.awaitWorkFinished() }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        waitJob?.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Home library sync",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(label: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(label)
            .setContentText("Syncing music files over home Wi‑Fi")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "kainos_home_lan_vault_sync"
        const val NOTIFICATION_ID = 43822
    }
}
