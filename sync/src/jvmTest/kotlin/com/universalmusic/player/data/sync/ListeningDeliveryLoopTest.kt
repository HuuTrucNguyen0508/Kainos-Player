package com.universalmusic.player.data.sync

import com.universalmusic.player.data.session.ListeningPairingIdentity
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.PublicationRevision
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
        target = { DeliveryTarget(hubPeer, "kainos-phone", CertFingerprint.parse("bb".repeat(32))!!) },
        connect = { hub },
        clock = { 1_000 },
    )

    @Test
    fun targetFailureBecomesARetry() = runBlocking {
        val loop = ListeningDelivery(PendingStore(queue), { throw IOException("settings unavailable") }, { FakeHub() })
        assertEquals(DeliveryOutcome.RETRY, loop.deliverPending())
    }

    @Test
    fun refusalReadFailureBecomesARetry() = runBlocking {
        val store = object : ListeningExchangeStore by PendingStore(queue) {
            override suspend fun refusedPairing(): ListeningPairingIdentity? = throw IOException("database unavailable")
        }
        assertEquals(DeliveryOutcome.RETRY, delivery(store, FakeHub()).deliverPending())
    }

    @Test
    fun refusalWriteFailureBecomesARetry() = runBlocking {
        val store = object : ListeningExchangeStore by PendingStore(queue) {
            override suspend fun setRefusedPairing(pairing: ListeningPairingIdentity?) { throw IOException("database unavailable") }
        }
        assertEquals(DeliveryOutcome.RETRY, delivery(store, FakeHub { throw ListeningHubRefused("HTTP 401") }).deliverPending())
    }

    @Test
    fun clearingAnOldRefusalFailureBecomesARetry() = runBlocking {
        val base = PendingStore(queue)
        base.setRefusedPairing(DeliveryTarget(hubPeer, "old-phone", CertFingerprint.parse("bb".repeat(32))!!).pairingIdentity)
        val store = object : ListeningExchangeStore by base {
            override suspend fun setRefusedPairing(pairing: ListeningPairingIdentity?) { throw IOException("database unavailable") }
        }
        assertEquals(DeliveryOutcome.RETRY, delivery(store, FakeHub()).deliverPending())
    }

    @Test
    fun cancellationStillEscapesThePass() = runBlocking {
        val loop = ListeningDelivery(PendingStore(queue), { throw CancellationException("stopping") }, { FakeHub() })
        kotlin.test.assertFailsWith<CancellationException> { loop.deliverPending() }
        Unit
    }

    @Test
    fun throwingPendingCheckDoesNotKillTheRunner() = runTest {
        val base = PendingStore(queue)
        var unavailable = true
        val store = object : ListeningExchangeStore by base {
            override suspend fun refusedPairing(): ListeningPairingIdentity? {
                if (unavailable) throw IOException("database unavailable")
                return base.refusedPairing()
            }
        }
        val hub = FakeHub()
        val runner = ListeningDeliveryRunner(backgroundScope, delivery(store, hub), { true }, {}, isAppForeground = { false })
        runner.request()
        runCurrent()
        unavailable = false
        advanceTimeBy(LISTENING_RETRY_MS + 1)
        assertEquals(listOf(1L), hub.reports)
    }

    @Test
    fun throwingSchedulerDoesNotKillTheRunnerOrBypassDurableScheduling() = runTest {
        var attempts = 0
        val hub = FakeHub()
        val runner = ListeningDeliveryRunner(backgroundScope, delivery(PendingStore(queue), hub), { true }, {
            if (++attempts == 1) throw IOException("WorkManager unavailable")
        }, isAppForeground = { false })
        runner.request()
        runCurrent()
        assertEquals(emptyList(), hub.reports)
        advanceTimeBy(LISTENING_RETRY_MS + 1)
        assertEquals(2, attempts)
        assertEquals(listOf(1L), hub.reports)
    }

    @Test
    fun foregroundFetchIsAttemptedOncePerEventWithoutAPendingRevision() = runTest {
        val store = PendingStore(queue)
        store.markDelivered(hubPeer.deviceId, PublicationRevision(1))
        var attempts = 0
        val loop = ListeningDelivery(store,
            { DeliveryTarget(hubPeer, "kainos-phone", CertFingerprint.parse("bb".repeat(32))!!) },
            { attempts++; throw IOException("hub is off") })
        val runner = ListeningDeliveryRunner(backgroundScope, loop, { true }, {}, isAppForeground = { true })
        runner.request(exchangeOnForeground = true)
        runCurrent()
        advanceTimeBy(10 * LISTENING_RETRY_MS)
        assertEquals(1, attempts)
        runner.request(exchangeOnForeground = true)
        runCurrent()
        assertEquals(2, attempts)
    }

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
            assertEquals(true, loop.canDeliverPending())
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
            assertEquals(true, loop.canDeliverPending())
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
            assertEquals(false, delivery(phone.exchange, hub).canDeliverPending())
            assertEquals(null, phone.exchange.deliveredRevision("kainos-pc"))
        }
    }

    @Test
    fun refusalSurvivesPublicationsAndProcessRestartUntilThePairingChanges() = runBlocking {
        val file = directory.resolve("phone.db")
        val hub = FakeHub { throw ListeningHubRefused("HTTP 403") }
        ListeningDevice(file).use { phone ->
            phone.publish(queue, current = 0, activeAtMs = 100)
            assertEquals(DeliveryOutcome.REFUSED, delivery(phone.exchange, hub).deliverPending())
            phone.publish(queue, current = 1, activeAtMs = 200)
            assertEquals(DeliveryOutcome.REFUSED, delivery(phone.exchange, hub).deliverPending())
            assertEquals(DeliveryOutcome.REFUSED, delivery(phone.exchange, hub).deliverPending(exchangeOnForeground = true))
            assertEquals(listOf(1L), hub.reports)
        }
        ListeningDevice(file).use { phone ->
            assertEquals(DeliveryOutcome.REFUSED, delivery(phone.exchange, hub).deliverPending())
            assertEquals(listOf(1L), hub.reports)
            val repaired = FakeHub()
            val loop = ListeningDelivery(phone.exchange,
                { DeliveryTarget(hubPeer.copy(pairedAtMs = 2L), "kainos-phone", CertFingerprint.parse("bb".repeat(32))!!) }, { repaired })
            assertEquals(DeliveryOutcome.DELIVERED, loop.deliverPending())
            assertEquals(listOf(2L), repaired.reports)
        }
    }

    @Test
    fun changingEitherCertificateClearsThePersistedRefusal() = runBlocking {
        val original = DeliveryTarget(hubPeer, "kainos-phone", CertFingerprint.parse("bb".repeat(32))!!)
        val changed = listOf(
            original.copy(hub = hubPeer.copy(fingerprint = CertFingerprint.parse("cc".repeat(32))!!)),
            original.copy(ownFingerprint = CertFingerprint.parse("dd".repeat(32))!!),
        )
        changed.forEachIndexed { index, repaired ->
            ListeningDevice(directory.resolve("phone-$index.db")).use { phone ->
                phone.publish(queue, current = 0, activeAtMs = 100)
                val refused = FakeHub { throw ListeningHubRefused("certificate mismatch") }
                assertEquals(DeliveryOutcome.REFUSED, delivery(phone.exchange, refused).deliverPending())
                assertEquals(original.pairingIdentity, phone.exchange.refusedPairing())
                val hub = FakeHub()
                val loop = ListeningDelivery(phone.exchange, { repaired }, { hub })
                assertEquals(DeliveryOutcome.DELIVERED, loop.deliverPending())
                assertEquals(null, phone.exchange.refusedPairing())
                assertEquals(listOf(1L), hub.reports)
            }
        }
    }

    @Test
    fun backgroundWorkIsDurablyScheduledBeforeTheFirstNetworkAttempt() = runTest {
        val events = mutableListOf<String>()
        val persisted = CompletableDeferred<Unit>()
        val hub = FakeHub { events += "network" }
        val runner = ListeningDeliveryRunner(backgroundScope, delivery(PendingStore(queue), hub), { false }, {
            events += "scheduling"
            persisted.await()
            events += "persisted"
        }, isAppForeground = { false })
        runner.request()
        runCurrent()
        assertEquals(listOf("scheduling"), events)
        persisted.complete(Unit)
        runCurrent()
        assertEquals(listOf("scheduling", "persisted", "network"), events)
    }

    @Test
    fun theRunnerRetriesEveryThirtySecondsInTheForegroundAndHandsOffInTheBackground() = runTest {
        val store = PendingStore(queue)
        val hub = FakeHub { throw IOException("hub is off") }
        var foreground = true
        var scheduled = 0
        val runner = ListeningDeliveryRunner(backgroundScope, delivery(store, hub), { foreground }, { scheduled++ }, isAppForeground = { foreground })

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
