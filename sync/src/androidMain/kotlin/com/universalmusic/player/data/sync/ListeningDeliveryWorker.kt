package com.universalmusic.player.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.universalmusic.player.platform.createListeningExchangeStore
import com.universalmusic.player.platform.createSettingsStore
import com.universalmusic.player.platform.initAndroidPlatform
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

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ListeningDeliveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, LISTENING_RETRY_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

private suspend fun backgroundDelivery(context: Context): ListeningDelivery {
    initAndroidPlatform(context)
    val settings = createSettingsStore().read()
    return ListeningDelivery(
        store = createListeningExchangeStore(),
        target = { settings.listeningDeliveryTarget(isDesktop = false) },
        connect = ::openPairedListeningHub,
    )
}
