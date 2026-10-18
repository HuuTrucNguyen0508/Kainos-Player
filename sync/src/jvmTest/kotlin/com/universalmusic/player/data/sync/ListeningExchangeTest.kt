package com.universalmusic.player.data.sync

import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.StoredPeerListening
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.pinnedHomeLanHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        target = { DeliveryTarget(hubAsPeer, "kainos-phone") },
        connect = { client() },
        clock = { phoneNow },
        mutex = Mutex(),
    )

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

    private fun phoneState(current: Int, at: Long, positionMs: Long = 42_000, playing: Boolean = true) =
        PeerListeningState.Available(portable(phoneItems).hash, portable(phoneItems).entries[current], positionMs, at, playing)

    private fun pcState(at: Long) =
        PeerListeningState.Available(portable(pcItems).hash, portable(pcItems).entries[0], 30_000, at, false)

    private companion object {
        const val HUB_NOW = 10_000_000L
    }
}
