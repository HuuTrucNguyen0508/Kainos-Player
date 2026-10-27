package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.pinnedHomeLanHttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.time.TimeMark
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The remote routes over real pinned mutual TLS against [DesktopHomeLanSyncHub] on loopback. */
class RemoteLinkLoopbackTest {
    private val directory: File = Files.createTempDirectory("kainos-remote-").toFile()
    private val pc = JvmDeviceIdentity(directory.resolve("pc")).material()
    private val phone = JvmDeviceIdentity(directory.resolve("phone")).material()
    private val stranger = JvmDeviceIdentity(directory.resolve("stranger")).material()
    private val port = ServerSocket(0).use { it.localPort }
    private val phonePeer = PairedPeer("kainos-phone", "Phone", phone.fingerprint, pairedAtMs = 1L)
    private val host = RemoteLinkHost(hubName = { "Desk PC" })
    private val hubPlayer = RecordingPlayer("PC song")
    private var guiPlayer: RemotePlayer? = hubPlayer
    private val hub = DesktopHomeLanSyncHub(
        HomeLanHubConfig(
            deviceId = "kainos-pc",
            port = port,
            peers = { listOf(phonePeer) },
            pairing = PairingHost({ pc.fingerprint }, { "kainos-pc" }, onPaired = {}),
            onHearts = { it },
            onPlaylists = { it },
            vault = null,
            heartedVaultFileNames = { null },
            remote = host,
            guiPlayer = { guiPlayer },
        ),
        pc,
        bindHost = "127.0.0.1",
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        runBlocking { hub.stop() }
        directory.deleteRecursively()
    }

    private fun transport(identity: JvmDeviceIdentity.Material = phone) =
        HttpRemoteLinkTransport(HubEndpoint("127.0.0.1", port), pinnedHomeLanHttpClient(pc.fingerprint, identity.keyManagers()))

    private fun poll(generation: Long, epoch: String? = null, ackSeq: Long = 0, knownHubVersion: Long = -1) = RemotePollRequest(
        deviceId = "kainos-phone",
        linkId = "loopback",
        generation = generation,
        epoch = epoch,
        ackSeq = ackSeq,
        knownHubVersion = knownHubVersion,
        status = RemoteStatus(title = "Phone song", isPlaying = true),
    )

    @Test
    fun aHeldPollCarriesACommandTheGuiQueuesAndTheAckConfirmsIt(): Unit = runBlocking {
        hub.start()
        host.updateHubStatus(RemoteStatus(title = "PC song", isPlaying = true), hasPlayer = true)
        transport().use { link ->
            val first = link.poll(poll(1))
            assertEquals("Desk PC", first.hubName)
            assertEquals("PC song", first.hubStatus?.title)
            assertTrue(first.hubHasPlayer)
            assertEquals("Phone song", host.peer("kainos-phone").status?.title)

            val held = async { link.poll(poll(2, first.epoch, knownHubVersion = first.hubVersion)) }
            withTimeout(5_000) { host.changes.first { host.peer("kainos-phone").connected } }
            delay(200)
            val sent = async { host.send("kainos-phone", RemoteCommand.SeekTo(9_000)) }
            val delivered = withTimeout(5_000) { held.await() }
            assertEquals(listOf(RemoteCommand.SeekTo(9_000)), delivered.commands.map { it.command })

            link.poll(poll(3, first.epoch, ackSeq = delivered.commands.single().seq, knownHubVersion = -5))
            assertEquals(RemoteSendOutcome.DELIVERED, withTimeout(5_000) { sent.await() })
        }
    }

    @Test
    fun theRealClientRunsAGuiCommandOnThePhonePlayer(): Unit = runBlocking {
        hub.start()
        val phonePlayer = RecordingPlayer("Phone song")
        val client = RemoteLinkClient(
            scope = scope,
            target = { DeliveryTarget(PairedPeer("kainos-pc", "Desk PC", pc.fingerprint, "127.0.0.1", port, 1L), "kainos-phone", phone.fingerprint) },
            connect = { transport() },
            player = phonePlayer,
        )
        client.run(MutableStateFlow(true))
        withTimeout(10_000) { client.state.first { it.connected } }
        withTimeout(5_000) { host.changes.first { host.peer("kainos-phone").connected } }

        assertEquals(RemoteSendOutcome.DELIVERED, host.send("kainos-phone", RemoteCommand.Pause))
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause), phonePlayer.applied)
        assertEquals("Phone song", host.peer("kainos-phone").status?.title)

        val applied = assertIs<RemoteClientSend.Applied>(client.send(RemoteCommand.Next))
        assertEquals("PC song", applied.reply.status?.title)
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Next), hubPlayer.applied)
        assertNotNull(hubPlayer.receivedDeadline)
    }

    @Test
    fun aHubWithoutAGuiPlayerAnswersCommandsWith409(): Unit = runBlocking {
        guiPlayer = null
        hub.start()
        transport().use { link ->
            assertFailsWith<RemoteHubHasNoPlayer> { link.command(RemoteCommand.Play) }
            assertFalse(link.poll(poll(1)).hubHasPlayer)
        }
        assertTrue(hubPlayer.applied.isEmpty())
    }

    @Test
    fun aTransferSnapshotPostedByThePhoneReachesTheWaitingGui(): Unit = runBlocking {
        hub.start()
        transport().use { link ->
            val first = link.poll(poll(1))
            val transfer = async { host.requestTransfer("kainos-phone") }
            val delivered = withTimeout(5_000) { link.poll(poll(2, first.epoch, knownHubVersion = first.hubVersion)) }
            val envelope = delivered.commands.single()
            assertEquals(RemoteCommand.PauseForTransfer, envelope.command)

            val snapshot = RemoteTransferReply(failure = RemoteTransferFailure.UNSUPPORTED_TRACK)
            link.postTransfer(first.epoch, envelope.seq, snapshot)
            assertEquals(RemoteHostTransfer.Received(snapshot), withTimeout(5_000) { transfer.await() })
        }
    }

    @Test
    fun artworkRoundTripsBothWays(): Unit = runBlocking {
        hub.start()
        val cover = ByteArray(300_000) { (it % 251).toByte() }
        host.updateHubStatus(RemoteStatus(title = "PC song", artworkKey = "feedface"), hasPlayer = true, artwork = "feedface" to cover)
        transport().use { link ->
            link.poll(poll(1))
            assertContentEquals(cover, link.fetchArtwork("feedface"))
            assertNull(link.fetchArtwork("00000000"))

            link.putArtwork("cafebabe", byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), host.peerArtwork("kainos-phone", "cafebabe"))
            assertFailsWith<IllegalStateException> { link.putArtwork("toolarge", ByteArray(REMOTE_ARTWORK_MAX_BYTES + 1)) }
        }
    }

    @Test
    fun strangersAndForgedDevicesAreRefused(): Unit = runBlocking {
        hub.start()
        transport(stranger).use { link -> assertFailsWith<ListeningHubRefused> { link.poll(poll(1)) } }
        transport().use { link -> assertFailsWith<ListeningHubRefused> { link.poll(poll(1).copy(deviceId = "kainos-tablet")) } }
        assertNull(host.peer("kainos-tablet").status)
    }

    @Test
    fun stoppingTheHubEndsAHeldPollPromptly(): Unit = runBlocking {
        hub.start()
        transport().use { link ->
            val first = link.poll(poll(1))
            val held = async { runCatching { link.poll(poll(2, first.epoch, knownHubVersion = first.hubVersion)) } }
            withTimeout(5_000) { host.changes.first { host.peer("kainos-phone").connected } }
            delay(200)

            val started = System.nanoTime()
            hub.stop()
            val ended = withTimeout(5_000) { held.await() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < REMOTE_POLL_HOLD_MS / 4, "stop took $elapsedMs ms")
            ended.getOrNull()?.let { assertTrue(it.superseded) }
        }
    }

    @Test
    fun aMalformedPollIsABadRequest(): Unit = runBlocking {
        hub.start()
        pinnedHomeLanHttpClient(pc.fingerprint, phone.keyManagers()).use { http ->
            val response = http.post("https://127.0.0.1:$port$HOME_LAN_SYNC_PATH_V3_REMOTE_POLL") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody("{not json")
            }
            assertEquals(400, response.status.value)
        }
    }

    private class RecordingPlayer(title: String) : RemotePlayer {
        override val state = MutableStateFlow<RemotePlayerState?>(RemotePlayerState(RemoteStatus(title = title), System.currentTimeMillis()))
        val applied = mutableListOf<RemoteCommand>()
        var receivedDeadline: TimeMark? = null

        override suspend fun apply(command: RemoteCommand, deadline: TimeMark?): RemoteCommandReply {
            receivedDeadline = deadline
            if (deadline?.hasPassedNow() == true) throw RemoteCommandExpired()
            applied += command
            return RemoteCommandReply(state.value?.status)
        }
    }
}
