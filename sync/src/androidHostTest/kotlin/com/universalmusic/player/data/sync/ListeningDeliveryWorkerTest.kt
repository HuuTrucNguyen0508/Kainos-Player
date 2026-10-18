package com.universalmusic.player.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.universalmusic.player.data.session.PublicationRevision
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListeningDeliveryWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hubPeer = PairedPeer("kainos-pc", "PC", CertFingerprint.parse("aa".repeat(32))!!, "127.0.0.1", 1, 1L)
    private val store = PendingStore(items("a", "b"))
    private var attempts = 0
    private var deliveryFailure: Exception? = null
    private val hub = FakeHub { if (++attempts == 1) throw IOException("hub is off") }

    private var finishGate: CompletableDeferred<Unit>? = null
    private var completedPass: CompletableDeferred<Unit>? = null

    @Before
    fun setUp() {
        val delivery = ListeningDelivery(store, { DeliveryTarget(hubPeer, "kainos-phone", CertFingerprint.parse("bb".repeat(32))!!) }, { hub }, mutex = Mutex())
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                ListeningDeliveryWorker(appContext, workerParameters) {
                    deliveryFailure?.let { throw it }
                    val result = delivery.deliverPending()
                    completedPass?.complete(Unit)
                    finishGate?.await()
                    result
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(factory).setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun aFailedDeliveryIsRetriedAndTheSecondAttemptSucceeds() = runBlocking {
        val workManager = WorkManager.getInstance(context)
        ListeningDeliveryWorker.enqueue(context)
        ListeningDeliveryWorker.enqueue(context)
        val queued = workManager.getWorkInfosForUniqueWork(ListeningDeliveryWorker.UNIQUE_NAME).get().single()
        assertEquals(NetworkType.CONNECTED, queued.constraints.requiredNetworkType)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!

        driver.setAllConstraintsMet(queued.id)
        val retried = workManager.getWorkInfoById(queued.id).get()!!
        assertEquals(WorkInfo.State.ENQUEUED, retried.state)
        assertEquals(1, retried.runAttemptCount)
        assertEquals(null, store.delivered)

        driver.setAllConstraintsMet(queued.id)
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(queued.id).get()!!.state)
        assertEquals(listOf(1L, 1L), hub.reports)
        assertEquals(PublicationRevision(1), store.delivered)
    }

    @Test
    fun anUnexpectedDeliveryExceptionRetriesAndCanRecover() = runBlocking {
        deliveryFailure = IOException("database temporarily unavailable")
        val manager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        ListeningDeliveryWorker.enqueue(context)
        val first = manager.getWorkInfosForUniqueWork(ListeningDeliveryWorker.UNIQUE_NAME).get().single()
        driver.setAllConstraintsMet(first.id)
        val retried = manager.getWorkInfoById(first.id).get()!!
        assertEquals(WorkInfo.State.ENQUEUED, retried.state)
        assertEquals(1, retried.runAttemptCount)
        deliveryFailure = null
        attempts = 1
        driver.setAllConstraintsMet(first.id)
        assertEquals(WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(first.id).get()!!.state)
    }

    @Test
    fun aPublicationScheduledWhileTheWorkerFinishesKeepsASuccessor() = runBlocking {
        attempts = 1
        finishGate = CompletableDeferred()
        completedPass = CompletableDeferred()
        val manager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        ListeningDeliveryWorker.enqueue(context)
        val first = manager.getWorkInfosForUniqueWork(ListeningDeliveryWorker.UNIQUE_NAME).get().single()
        driver.setAllConstraintsMet(first.id)
        withTimeout(5_000) { completedPass!!.await() }
        assertEquals(WorkInfo.State.RUNNING, manager.getWorkInfoById(first.id).get()!!.state)

        store.publishRevision(2)
        ListeningDeliveryWorker.enqueue(context)
        ListeningDeliveryWorker.enqueue(context)
        val chain = manager.getWorkInfosForUniqueWork(ListeningDeliveryWorker.UNIQUE_NAME).get()
        assertEquals(2, chain.size)
        val successor = chain.single { it.id != first.id }
        assertEquals(WorkInfo.State.BLOCKED, successor.state)
        finishGate!!.complete(Unit)
        withTimeout(5_000) {
            while (manager.getWorkInfoById(first.id).get()!!.state != WorkInfo.State.SUCCEEDED) delay(10)
        }
        driver.setAllConstraintsMet(successor.id)
        withTimeout(5_000) {
            while (manager.getWorkInfoById(successor.id).get()!!.state != WorkInfo.State.SUCCEEDED) delay(10)
        }
        assertEquals(listOf(1L, 2L), hub.reports)
        assertEquals(PublicationRevision(2), store.delivered)
    }
}
