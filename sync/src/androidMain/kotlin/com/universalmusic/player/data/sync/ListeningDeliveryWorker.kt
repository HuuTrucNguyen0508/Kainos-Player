package com.universalmusic.player.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.universalmusic.player.platform.createListeningExchangeStore
import com.universalmusic.player.platform.createSettingsStore
import com.universalmusic.player.platform.initAndroidPlatform
import com.universalmusic.player.platform.platformDeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class ListeningDeliveryWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val deliver: suspend () -> DeliveryOutcome,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, { backgroundDelivery(context).deliverPending() })

    override suspend fun doWork(): Result =
        if (deliver() == DeliveryOutcome.RETRY) Result.retry() else Result.success()

    companion object {
        const val UNIQUE_NAME = "listening-delivery"

        private val scheduling = Mutex()

        suspend fun enqueue(context: Context) = scheduling.withLock {
            withContext(Dispatchers.IO) {
                val manager = WorkManager.getInstance(context)
                val waiting = manager.getWorkInfosForUniqueWork(UNIQUE_NAME).get().any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                }
                if (!waiting) {
                    val request = OneTimeWorkRequestBuilder<ListeningDeliveryWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, LISTENING_RETRY_MS, TimeUnit.MILLISECONDS)
                        .build()
                    manager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
                }
            }
        }
    }
}

private suspend fun backgroundDelivery(context: Context): ListeningDelivery {
    initAndroidPlatform(context)
    return ListeningDelivery(
        store = createListeningExchangeStore(),
        target = { createSettingsStore().read().listeningDeliveryTarget(isDesktop = false, platformDeviceIdentity().fingerprint()) },
        connect = ::openPairedListeningHub,
    )
}
