package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import com.universalmusic.player.data.session.ListeningExchangeStore
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.StoredPeerListening
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.pinnedHomeLanHttpClient
import kotlinx.coroutines.flow.emptyFlow
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListeningExchangeTest {
    private val directory: File = Files.createTempDirectory("kainos-listening-exchange-").toFile()
    private val pcIdentity = JvmDeviceIdentity(directory.resolve("pc-id")).material()
    private val phoneIdentity = JvmDeviceIdentity(directory.resolve("phone-id")).material()
    private val port = ServerSocket(0).use { it.localPort }
    private val phonePeer = PairedPeer("kainos-phone", "Phone", phoneIdentity.fingerprint, pairedAtMs = 1L)
    private val tabletPeer = PairedPeer("kainos-tablet", "Tablet", JvmDeviceIdentity(directory.resolve("tablet-id")).material().fingerprint, pairedAtMs = 2L)
    private val hubAsPeer = PairedPeer("kainos-pc", "PC", pcIdentity.fingerprint, "127.0.0.1", port, 1L)
    private val pc = ListeningDevice(directory.resolve("pc.db"))
    private val phone = ListeningDevice(directory.resolve("phone.db"))
    private val hub = listeningHub(pcIdentity, port, listOf(phonePeer, tabletPeer), pc.exchange, clock = { HUB_NOW })
    private val phoneItems = items("a", "b")
    private val pcItems = items("p1", "p2")

    @AfterTest
    fun cleanUp() {
        runBlocking { hub.stop() }
        pc.close()
        phone.close()
        directory.deleteRecursively()
    }

    private fun http(identity: JvmDeviceIdentity.Material = phoneIdentity): HttpClient =
        pinnedHomeLanHttpClient(pcIdentity.fingerprint, identity.keyManagers())

    private fun client(identity: JvmDeviceIdentity.Material = phoneIdentity) =
        HttpListeningHubClient(HubEndpoint("127.0.0.1", port), http(identity))

    private fun phoneDelivery(phoneNow: Long) = ListeningDelivery(
        store = phone.exchange,
        target = { DeliveryTarget(hubAsPeer, "kainos-phone", phoneIdentity.fingerprint) },
        connect = { client() },
        clock = { phoneNow },
        mutex = Mutex(),
    )

    @Test
    fun shuffleFlagRoundTripsBothWaysOverTlsAndUpdatesWithoutAnotherQueue() = runBlocking {
        hub.start()
        pc.publish(pcItems, current = 0, activeAtMs = HUB_NOW - 1_000, shuffle = true)
        phone.publish(phoneItems, current = 1, activeAtMs = HUB_NOW, shuffle = true)
        assertEquals(DeliveryOutcome.DELIVERED, phoneDelivery(HUB_NOW).deliverPending())
        val phoneState = pc.exchange.readPeer("kainos-phone")!!.listening.state as PeerListeningState.Available
        val pcState = phone.exchange.readPeer("kainos-pc")!!.listening.state as PeerListeningState.Available
        assertEquals(true, phoneState.shuffle)
        assertEquals(true, pcState.shuffle)
        val originalQueue = pc.exchange.readPeer("kainos-phone")!!.queue

        phone.publish(phoneItems, current = 1, activeAtMs = HUB_NOW + 1_000, shuffle = false)
        client().use { hubClient ->
            val report = phone.exchange.readLocalExport()!!.toReport("kainos-phone", HUB_NOW + 1_000)
            assertEquals(false, hubClient.report(report).needQueue)
        }
        val disabled = pc.exchange.readPeer("kainos-phone")!!
        assertEquals(false, (disabled.listening.state as PeerListeningState.Available).shuffle)
        assertEquals(PublicationRevision(2), disabled.listening.revision)
        assertEquals(originalQueue, disabled.queue)
    }

    @Test
    fun legacyReportWithoutShuffleDecodesAsOff() {
        val old = """{"queueHash":"${portable(phoneItems).hash.hex}","positionMs":0,"positionAgeMs":0,"isPlaying":false}"""
        val state = Json.decodeFromString<ListeningReportState>(old)
        assertEquals(false, state.shuffle)
        assertEquals(false, (ListeningReport("phone", 1, 0, state).toPeer(HUB_NOW)!!.state as PeerListeningState.Available).shuffle)
    }

    @Test
    fun aPhoneTenMinutesAheadIsOrderedByHowLongAgoItWasActive() = runBlocking {
        hub.start()
        pc.publish(pcItems, current = 0, activeAtMs = HUB_NOW - 60_000, positionMs = 30_000)
        val phoneNow = HUB_NOW + 600_000
        phone.publish(phoneItems, current = 1, activeAtMs = phoneNow - 120_000, positionMs = 42_000, playing = true)

        assertEquals(DeliveryOutcome.DELIVERED, phoneDelivery(phoneNow).deliverPending())

        assertEquals(
            StoredPeerListening(
                PeerListening("kainos-phone", PublicationRevision(1), 9_880_000, phoneState(current = 1, at = 9_880_000)),
                portable(phoneItems),
            ),
            pc.exchange.readPeer("kainos-phone"),
        )
        assertEquals(
            PeerListening("kainos-pc", PublicationRevision(1), 10_540_000, pcState(at = 10_540_000)),
            phone.exchange.readPeer("kainos-pc")?.listening,
        )
    }

    @Test
    fun aPhoneTenMinutesBehindIsOrderedByHowLongAgoItWasActive() = runBlocking {
        hub.start()
        pc.publish(pcItems, current = 0, activeAtMs = HUB_NOW - 60_000, positionMs = 30_000)
        val phoneNow = HUB_NOW - 600_000
        phone.publish(phoneItems, current = 1, activeAtMs = phoneNow - 5_000, positionMs = 42_000, playing = true)

        assertEquals(DeliveryOutcome.DELIVERED, phoneDelivery(phoneNow).deliverPending())

        assertEquals(
            PeerListening("kainos-phone", PublicationRevision(1), 9_995_000, phoneState(current = 1, at = 9_995_000)),
            pc.exchange.readPeer("kainos-phone")?.listening,
        )
        assertEquals(
            PeerListening("kainos-pc", PublicationRevision(1), 9_340_000, pcState(at = 9_340_000)),
            phone.exchange.readPeer("kainos-pc")?.listening,
        )
    }

    @Test
    fun aRepeatedRevisionStillNeedsTheQueueUntilItArrives() = runBlocking {
        hub.start()
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW - 1_000)
        val report = phone.exchange.readLocalExport()!!.toReport("kainos-phone", HUB_NOW)

        client().use { hubClient ->
            assertEquals(ListeningReply(needQueue = true), hubClient.report(report))
            assertEquals(ListeningReply(needQueue = true), hubClient.report(report))
            assertNull(pc.exchange.readPeer("kainos-phone")?.queue)

            assertEquals(true, hubClient.uploadQueue(portable(phoneItems)))
            assertEquals(ListeningReply(needQueue = false), hubClient.report(report))
        }
        assertEquals(
            StoredPeerListening(
                PeerListening("kainos-phone", PublicationRevision(1), 9_999_000, phoneState(current = 0, at = 9_999_000, positionMs = 0, playing = false)),
                portable(phoneItems),
            ),
            pc.exchange.readPeer("kainos-phone"),
        )
    }

    @Test
    fun aQueueWhoseHashDoesNotMatchIsRejectedAndNothingIsStored() = runBlocking {
        hub.start()
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW)
        client().use { it.report(phone.exchange.readLocalExport()!!.toReport("kainos-phone", HUB_NOW)) }

        val status = http().use { http ->
            http.put("https://127.0.0.1:$port$HOME_LAN_SYNC_PATH_V3_QUEUE/${portable(phoneItems).hash.hex}") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody(portable(items("a", "x")).encodeJson())
            }.status.value
        }

        assertEquals(400, status)
        assertNull(pc.exchange.readPeer("kainos-phone")?.queue)
    }

    @Test
    fun aReportNamingAnotherPairedDeviceIsForbidden() = runBlocking {
        hub.start()
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW)
        val report = phone.exchange.readLocalExport()!!.toReport("kainos-tablet", HUB_NOW)

        val status = http().use { http ->
            http.post("https://127.0.0.1:$port$HOME_LAN_SYNC_PATH_V3_LISTENING") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(report))
            }.status.value
        }

        assertEquals(403, status)
        assertNull(pc.exchange.readPeer("kainos-tablet"))
        assertNull(pc.exchange.readPeer("kainos-phone"))
    }

    @Test
    fun aReportFromTheHubsOwnIdentityIsNotFiledAsAPeer() = runBlocking {
        hub.start()
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW)

        val reply = client(pcIdentity).use { it.report(phone.exchange.readLocalExport()!!.toReport("kainos-pc", HUB_NOW)) }

        assertEquals(ListeningReply(needQueue = false), reply)
        assertNull(pc.exchange.readPeer("kainos-pc"))
    }

    @Test
    fun aNewerUnavailableExportReplacesTheOlderStateWithoutAQueue() = runBlocking {
        hub.start()
        val delivery = phoneDelivery(HUB_NOW)
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW - 2_000)
        assertEquals(DeliveryOutcome.DELIVERED, delivery.deliverPending())
        phone.publishUnavailable(phoneItems, activeAtMs = HUB_NOW - 1_000)

        assertEquals(DeliveryOutcome.DELIVERED, delivery.deliverPending())

        assertEquals(
            StoredPeerListening(
                PeerListening("kainos-phone", PublicationRevision(2), 9_999_000, PeerListeningState.Unavailable(ProjectionFailure.UNSUPPORTED_TRACK)),
                queue = null,
            ),
            pc.exchange.readPeer("kainos-phone"),
        )
    }

    @Test
    fun theHubServesItsOwnQueueOnlyForItsCurrentHash() = runBlocking {
        hub.start()
        pc.publish(pcItems, current = 0, activeAtMs = HUB_NOW)

        client().use { hubClient ->
            assertEquals(portable(pcItems), hubClient.fetchQueue(portable(pcItems).hash))
            assertNull(hubClient.fetchQueue(portable(phoneItems).hash))
        }
    }

    @Test
    fun foregroundFetchesANewerHubExportAfterThePhoneRevisionWasDelivered() = runExchangeTest {
        hub.start()
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW)
        pc.publish(pcItems, current = 0, activeAtMs = HUB_NOW - 1_000)
        assertEquals(DeliveryOutcome.DELIVERED, phoneDelivery(HUB_NOW).deliverPending())
        pc.publish(pcItems, current = 1, activeAtMs = HUB_NOW)

        foregroundService(backgroundScope).onAppForeground()
        runCurrent()
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                while (phone.exchange.readPeer("kainos-pc")?.listening?.revision != PublicationRevision(2)) {
                    delay(10)
                }
            }
        }
        assertEquals(PublicationRevision(1), phone.exchange.deliveredRevision("kainos-pc"))
        assertEquals(portable(pcItems).entries[1],
            (phone.exchange.readPeer("kainos-pc")!!.listening.state as PeerListeningState.Available).current)
    }

    @Test
    fun foregroundFetchesTheHubExportBeforeThePhoneHasPublishedAnything() = runExchangeTest {
        hub.start()
        pc.publish(pcItems, current = 0, activeAtMs = HUB_NOW)

        foregroundService(backgroundScope).onAppForeground()
        runCurrent()
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                while (phone.exchange.readPeer("kainos-pc") == null) delay(10)
            }
        }
        assertEquals(PublicationRevision(1), phone.exchange.readPeer("kainos-pc")?.listening?.revision)
        assertNull(pc.exchange.readPeer("kainos-phone"))
    }

    @Test
    fun aFetchOnlyRequestFromAnUnpairedDeviceIsRefused() = runBlocking {
        hub.start()
        pc.publish(pcItems, current = 0, activeAtMs = HUB_NOW)
        val stranger = JvmDeviceIdentity(directory.resolve("stranger-id")).material()
        val status = http(stranger).use {
            it.get("https://127.0.0.1:$port$HOME_LAN_SYNC_PATH_V3_LISTENING") { expectSuccess = false }.status.value
        }
        assertEquals(401, status)
    }

    @Test
    fun aForeignCertificateAtTheStaleAddressAndNoDiscoveryIsRetryable() = runBlocking {
        hub.start()
        val pairedHub = hubAsPeer.copy(fingerprint = CertFingerprint.parse("ff".repeat(32))!!)
        val http = pinnedHomeLanHttpClient(pairedHub.fingerprint, phoneIdentity.keyManagers())
        val loop = ListeningDelivery(phone.exchange,
            { DeliveryTarget(pairedHub, "kainos-phone", phoneIdentity.fingerprint) },
            { openListeningHub(it, http, HubBrowser { emptyFlow() }) }, mutex = Mutex())
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW)
        assertEquals(DeliveryOutcome.RETRY, loop.deliverPending())
        assertNull(phone.exchange.refusedPairing())
    }

    @Test
    fun aResolvedHubs401LatchesRefusal() = runBlocking {
        hub.start()
        val stranger = JvmDeviceIdentity(directory.resolve("unpaired-id")).material()
        val http = http(stranger)
        val loop = ListeningDelivery(phone.exchange,
            { DeliveryTarget(hubAsPeer, "kainos-phone", phoneIdentity.fingerprint) },
            { openListeningHub(it, http, HubBrowser { emptyFlow() }) }, mutex = Mutex())
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW)
        assertEquals(DeliveryOutcome.REFUSED, loop.deliverPending())
        assertEquals(DeliveryTarget(hubAsPeer, "kainos-phone", phoneIdentity.fingerprint).pairingIdentity, phone.exchange.refusedPairing())
    }

    @Test
    fun aSuccessfulSyncClearsRefusalAndThePairAgainStatus() = runExchangeTest {
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW)
        val service = foregroundService(backgroundScope, listeningClient = { FakeHub { throw ListeningHubRefused("HTTP 401") } },
            syncClient = { FakeVaultClient(ByteArray(0)) })
        service.requestListeningDelivery()
        awaitRefusal(service)
        assertEquals(HOME_LAN_NOT_TRUSTED, service.status.value.lastError)
        kotlin.test.assertNotNull(phone.exchange.refusedPairing())
        withContext(Dispatchers.IO) { service.syncNow().getOrThrow() }
        assertNull(phone.exchange.refusedPairing())
        assertNull(service.status.value.lastError)
    }

    @Test
    fun aSuccessfulHealthProbeClearsRefusalEvenIfTheMainSyncFails() = runExchangeTest {
        val refusedPairing = DeliveryTarget(hubAsPeer, "kainos-phone", phoneIdentity.fingerprint).pairingIdentity
        phone.exchange.setRefusedPairing(refusedPairing)
        val base = FakeVaultClient(ByteArray(0))
        val service = foregroundService(backgroundScope, syncClient = { object : HomeLanSyncClient by base {
            override suspend fun syncHearts() = Result.failure<HeartsSyncDocument>(java.io.IOException("temporary heart failure"))
        } })
        kotlin.test.assertTrue(withContext(Dispatchers.IO) { service.syncNow().isFailure })
        assertNull(phone.exchange.refusedPairing())
    }

    @Test
    fun aThrowingBackgroundSchedulerDoesNotEscapeTheService() = runExchangeTest {
        phone.publish(phoneItems, current = 0, activeAtMs = HUB_NOW)
        var attempts = 0
        val service = foregroundService(backgroundScope, scheduler = { attempts++; throw java.io.IOException("scheduler unavailable") })
        service.scheduleListeningDeliveryIfPending()
        service.scheduleListeningDeliveryIfPending()
        assertEquals(2, attempts)
    }

    @Test
    fun cancellingAnExchangeWaitsForRealIoBeforeTheNextDelivery() {
        val enteredIo = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        val deliveryMutex = Mutex()
        val executor = Executors.newSingleThreadExecutor()
        val exchange = executor.submit {
            runExchangeTest {
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelling.countDown()
                    }
                }
                val store = object : ListeningExchangeStore by PendingStore(phoneItems) {
                    override suspend fun deliveredRevision(hubDeviceId: String): PublicationRevision? = withContext(Dispatchers.IO) {
                        enteredIo.countDown()
                        check(releaseIo.await(5, TimeUnit.SECONDS))
                        null
                    }
                }
                val delivery = ListeningDelivery(store,
                    { DeliveryTarget(hubAsPeer, "kainos-phone", phoneIdentity.fingerprint) },
                    { FakeHub() }, mutex = deliveryMutex)
                ListeningDeliveryRunner(backgroundScope, delivery, { true }, {}, isAppForeground = { true }).request()
                runCurrent()
                withContext(Dispatchers.IO) { assertTrue(enteredIo.await(5, TimeUnit.SECONDS)) }
            }
        }
        try {
            assertTrue(cancelling.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { exchange.get(100, TimeUnit.MILLISECONDS) }
            releaseIo.countDown()
            exchange.get(5, TimeUnit.SECONDS)
            runBlocking {
                withTimeout(1_000) {
                    val next = ListeningDelivery(PendingStore(phoneItems), { null }, { error("No target") }, mutex = deliveryMutex)
                    assertEquals(DeliveryOutcome.IDLE, next.deliverPending())
                }
            }
        } finally {
            releaseIo.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private suspend fun awaitRefusal(service: HomeLanSyncService) = withContext(Dispatchers.IO) {
        withTimeout(5_000) {
            while (service.status.value.lastError != HOME_LAN_NOT_TRUSTED) delay(10)
        }
    }

    private fun foregroundService(
        scope: CoroutineScope,
        listeningClient: (PairedPeer) -> ListeningHubClient = { client() },
        syncClient: (PairedPeer) -> HomeLanSyncClient = { error("No library sync needed") },
        scheduler: suspend () -> Unit = {},
    ): HomeLanSyncService {
        var settings = AppSettings(homeLanSyncEnabled = true, homeLanSyncRole = HomeLanSyncRole.CLIENT,
            homeLanSyncDeviceId = "kainos-phone", homeLanSyncHub = hubAsPeer)
        return HomeLanSyncService(scope, { settings }, { settings = it(settings) },
            LibraryRepository(), KainosPlaylistRepository(), hubFactory = { error("Phone has no hub") },
            clientFactory = syncClient, pairingClientFactory = { _, _ -> error("No pairing needed") },
            identity = phoneIdentity.let { FixedDeviceIdentity(it.fingerprint.hex) },
            vaultStore = JvmHomeLanVaultStore({ directory.toString() }, directory.resolve("vault.json").toPath()),
            refreshLocalLibrary = {}, isDesktop = false, clock = { HUB_NOW },
            listening = phone.exchange, openListeningHub = listeningClient, scheduleListeningDelivery = scheduler).also { it.hydrateFromSettings(settings) }
    }

    private fun phoneState(current: Int, at: Long, positionMs: Long = 42_000, playing: Boolean = true) =
        PeerListeningState.Available(portable(phoneItems).hash, portable(phoneItems).entries[current], positionMs, at, playing)

    private fun pcState(at: Long) =
        PeerListeningState.Available(portable(pcItems).hash, portable(pcItems).entries[0], 30_000, at, false)

    private companion object {
        const val HUB_NOW = 10_000_000L
    }
}

private fun runExchangeTest(block: suspend TestScope.() -> Unit) = runTest {
    try {
        block()
    } finally {
        // Real IO can finish after runTest's automatic background cancellation drains the scheduler.
        // Join here so its delivery-lock cleanup still has a running scheduler and an open database.
        withContext(NonCancellable) { backgroundScope.coroutineContext.job.cancelAndJoin() }
    }
}
