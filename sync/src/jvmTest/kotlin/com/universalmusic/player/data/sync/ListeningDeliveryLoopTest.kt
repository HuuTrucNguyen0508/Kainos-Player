package com.universalmusic.player.data.sync

import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.LocalListeningPublication
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerReceipt
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueHash
import com.universalmusic.player.data.session.StoredPeerListening
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ListeningDeliveryLoopTest {
    private val directory: File = Files.createTempDirectory("kainos-listening-delivery-").toFile()
    private val hubPeer = PairedPeer("kainos-pc", "PC", CertFingerprint.parse("aa".repeat(32))!!, "127.0.0.1", 1, 1L)
    private val queue = items("a", "b")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun delivery(store: ListeningExchangeStore, hub: ListeningHubClient) = ListeningDelivery(
        store = store,
        target = { DeliveryTarget(hubPeer, "kainos-phone") },
        connect = { hub },
        clock = { 1_000 },
    )

    @Test
    fun aLateAnswerForOneRevisionLeavesTheNextPending() = runBlocking {
        ListeningDevice(directory.resolve("phone.db")).use { phone ->
            phone.publish(queue, current = 0, activeAtMs = 100)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val hub = FakeHub { revision ->
                when (revision) {
                    1L -> { entered.complete(Unit); release.await() }
                    else -> throw IOException("hub went away")
                }
            }
            val loop = delivery(phone.exchange, hub)

            val outcome = async(Dispatchers.Default) { loop.deliverPending() }
            entered.await()
            phone.publish(queue, current = 1, activeAtMs = 200)
            release.complete(Unit)

            assertEquals(DeliveryOutcome.RETRY, outcome.await())
            assertEquals(listOf(1L, 2L), hub.reports)
            assertEquals(PublicationRevision(1), phone.exchange.deliveredRevision("kainos-pc"))
            assertEquals(true, loop.hasPending())
        }
    }

    @Test
    fun aForegroundRetryAndAWorkerTogetherDeliverEachRevisionInOnePass() = runBlocking {
        ListeningDevice(directory.resolve("phone.db")).use { phone ->
            phone.publish(queue, current = 0, activeAtMs = 100)
            val hub = FakeHub(needsQueue = true) { delay(50) }
            val foreground = delivery(phone.exchange, hub)
            val worker = delivery(phone.exchange, hub)

            val outcomes = listOf(foreground, worker)
                .map { async(Dispatchers.Default) { it.deliverPending() } }
                .awaitAll()

            assertEquals(setOf(DeliveryOutcome.DELIVERED, DeliveryOutcome.IDLE), outcomes.toSet())
            assertEquals(listOf(1L, 1L), hub.reports, "one report, the queue, then the same report again")
            assertEquals(listOf(portable(queue).hash.hex), hub.uploads)
        }
    }

    @Test
    fun aPendingRevisionIsDeliveredAfterTheProcessRestarts() = runBlocking {
        val file = directory.resolve("phone.db")
        ListeningDevice(file).use { phone ->
            phone.publish(queue, current = 0, activeAtMs = 100)
            val unreachable = FakeHub { throw IOException("no route to host") }
            assertEquals(DeliveryOutcome.RETRY, delivery(phone.exchange, unreachable).deliverPending())
        }

        ListeningDevice(file).use { restarted ->
            val hub = FakeHub()
            val loop = delivery(restarted.exchange, hub)
            assertEquals(true, loop.hasPending())
            assertEquals(DeliveryOutcome.DELIVERED, loop.deliverPending())
            assertEquals(DeliveryOutcome.IDLE, loop.deliverPending())
            assertEquals(listOf(1L), hub.reports)
            assertEquals(PublicationRevision(1), restarted.exchange.deliveredRevision("kainos-pc"))
        }
    }

    @Test
    fun aHubThatNoLongerTrustsThisDeviceIsNotRetried() = runBlocking {
        ListeningDevice(directory.resolve("phone.db")).use { phone ->
            phone.publish(queue, current = 0, activeAtMs = 100)
            val hub = FakeHub { throw ListeningHubRefused("Listening report refused: HTTP 401") }
            assertEquals(DeliveryOutcome.REFUSED, delivery(phone.exchange, hub).deliverPending())
            assertEquals(true, delivery(phone.exchange, hub).hasPending())
        }
    }

    @Test
    fun theRunnerRetriesEveryThirtySecondsInTheForegroundAndHandsOffInTheBackground() = runTest {
        val store = PendingStore(queue)
        val hub = FakeHub { throw IOException("hub is off") }
        var foreground = true
        var scheduled = 0
        val runner = ListeningDeliveryRunner(backgroundScope, delivery(store, hub), { foreground }, { scheduled++ })

        runner.request()
        runCurrent()
        assertEquals(1, hub.reports.size)
        advanceTimeBy(LISTENING_RETRY_MS + 1)
        assertEquals(2, hub.reports.size)

        foreground = false
        advanceTimeBy(LISTENING_RETRY_MS + 1)
        assertEquals(3, hub.reports.size)
        assertEquals(1, scheduled)
        advanceTimeBy(10 * LISTENING_RETRY_MS)
        assertEquals(3, hub.reports.size, "no in-process timer while in the background")

        runner.request()
        runCurrent()
        assertEquals(4, hub.reports.size)
    }
}

private class FakeHub(
    private val needsQueue: Boolean = false,
    private val onReport: suspend (Long) -> Unit = {},
) : ListeningHubClient {
    val reports: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val uploads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var hasQueue = !needsQueue

    override suspend fun report(report: ListeningReport): ListeningReply {
        reports += report.revision
        onReport(report.revision)
        return ListeningReply(needQueue = !hasQueue)
    }

    override suspend fun uploadQueue(queue: PortableListeningQueue): Boolean {
        uploads += queue.hash.hex
        hasQueue = true
        return true
    }

    override suspend fun fetchQueue(hash: QueueHash): PortableListeningQueue? = null

    override fun close() = Unit
}

/** Holds one undelivered revision without a database, so the runner test can use virtual time. */
private class PendingStore(items: List<com.universalmusic.player.data.session.PersistedQueueItem>) : ListeningExchangeStore {
    private val export = LocalListeningExport.Available(
        LocalListeningPublication(PublicationRevision(1), 100, ListeningState.create(portable(items), items[0].id, 0, 100, false)),
    )
    private var delivered: PublicationRevision? = null

    override suspend fun readLocalExport(): LocalListeningExport = export
    override suspend fun readLocalQueue(hash: QueueHash): PortableListeningQueue? = null
    override suspend fun receivePeer(peer: PeerListening): PeerReceipt = PeerReceipt(stored = true, needsQueue = false)
    override suspend fun attachPeerQueue(deviceId: String, queue: PortableListeningQueue) = false
    override suspend fun readPeer(deviceId: String): StoredPeerListening? = null
    override suspend fun deliveredRevision(hubDeviceId: String) = delivered
    override suspend fun markDelivered(hubDeviceId: String, revision: PublicationRevision): Boolean {
        delivered = revision
        return true
    }
}
