package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.hubPinTrustManager
import com.universalmusic.player.platform.pinnedHomeLanHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.plugins.expectSuccess
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HomeLanMutualTlsTest {
    private val directory: File = Files.createTempDirectory("kainos-mtls-").toFile()
    private val pc = JvmDeviceIdentity(directory.resolve("pc")).material()
    private val phone = JvmDeviceIdentity(directory.resolve("phone")).material()
    private val stranger = JvmDeviceIdentity(directory.resolve("stranger")).material()
    private val port = ServerSocket(0).use { it.localPort }
    private val peers = mutableListOf(PairedPeer("kainos-phone", "Phone", phone.fingerprint, pairedAtMs = 1L))
    private val pairing = PairingHost({ pc.fingerprint }, { "kainos-pc" }, onPaired = { peers += it })
    private val pcLibrary = LibraryRepository(deviceIdProvider = { "kainos-pc" })
    private val hub = DesktopHomeLanSyncHub(
        HomeLanHubConfig(
            deviceId = "kainos-pc",
            port = port,
            peers = { peers.toList() },
            pairing = pairing,
            onHearts = { pcLibrary.mergeAndPersistSyncState(it); pcLibrary.exportHeartsSyncDocument() },
            onPlaylists = { it },
            vault = null,
            heartedVaultFileNames = { null },
        ),
        pc,
        bindHost = "127.0.0.1",
    )
    private val hubAsPeer = PairedPeer("kainos-pc", "PC", pc.fingerprint, "127.0.0.1", port, 1L)

    @AfterTest
    fun cleanUp() {
        runBlocking { hub.stop() }
        directory.deleteRecursively()
    }

    private fun client(identity: JvmDeviceIdentity.Material?, pin: CertFingerprint = pc.fingerprint): HttpClient =
        pinnedHomeLanHttpClient(pin, identity?.keyManagers())

    private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post("https://127.0.0.1:$port$path") {
        expectSuccess = false
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun aPairedClientSyncs() = runBlocking {
        hub.start()
        HttpHomeLanSyncClient(hubAsPeer, LibraryRepository(), KainosPlaylistRepository(), client(phone)).use { sync ->
            assertEquals("kainos-pc", sync.probeHub().getOrThrow().deviceId)
            assertEquals(emptyList(), sync.syncHearts().getOrThrow().ops)
        }
    }

    @Test
    fun anUnpairedClientGets401OnSyncRoutes() = runBlocking {
        hub.start()
        client(stranger).use { http ->
            assertEquals(401, http.postJson(HOME_LAN_SYNC_PATH_V2_HEARTS, "{}").status.value)
        }
    }

    @Test
    fun aClientWithoutACertificateGets401EvenOnPairStart() = runBlocking {
        hub.start()
        client(null).use { http ->
            val start = """{"deviceId":"x","displayName":"x","commitHex":"${"00".repeat(32)}"}"""
            assertEquals(401, http.postJson(HOME_LAN_SYNC_PATH_PAIR_START, start).status.value)
            assertEquals(401, http.postJson(HOME_LAN_SYNC_PATH_V2_HEARTS, "{}").status.value)
        }
    }

    @Test
    fun aClientPinningAnotherHubFailsTheHandshake() = runBlocking {
        hub.start()
        val failure = HttpHomeLanSyncClient(hubAsPeer, LibraryRepository(), KainosPlaylistRepository(),
            client(phone, pin = stranger.fingerprint)).use { it.probeHub() }
        assertContains(failure.exceptionOrNull()?.message.orEmpty(), "Hub certificate pin mismatch")
    }

    @Test
    fun aStrangerPairsOverTlsAndThenSyncs() = runBlocking {
        hub.start()
        val endpoint = HubEndpoint("127.0.0.1", port)
        HttpHomeLanPairingClient(endpoint, client(stranger)).use { pairingClient ->
            val nonce = ByteArray(PAIRING_NONCE_BYTES) { 7 }
            val started = pairingClient.start(PairStartRequest("kainos-tablet", "Tablet", pairingCommit(nonce).toHex()))
            val busy = assertFailsWith<PairingRefusedException> {
                HttpHomeLanPairingClient(endpoint, client(phone)).use {
                    it.start(PairStartRequest("kainos-phone", "Phone", pairingCommit(nonce).toHex()))
                }
            }
            assertEquals(409, busy.httpStatus)
            pairingClient.reveal(PairRevealRequest(started.attemptId, nonce.toHex()))
            val phoneCode = pairingCode(pc.fingerprint, stranger.fingerprint, started.nonceHex.hexBytes(32)!!, nonce)
            assertEquals(phoneCode, pairing.incoming.value?.code)
            pairingClient.confirm(started.attemptId)
            assertEquals(PairState.PENDING, pairingClient.status(started.attemptId).state)
            assertEquals(PairingReply.Ok(Unit), pairing.confirmFromPc(started.attemptId))
            assertEquals(PairStatusResponse(PairState.PAIRED, hubDeviceId = "kainos-pc"), pairingClient.status(started.attemptId))
        }
        assertEquals(listOf("kainos-phone", "kainos-tablet"), peers.map { it.deviceId })
        assertEquals(stranger.fingerprint, peers.last().fingerprint)
        HttpHomeLanSyncClient(hubAsPeer, LibraryRepository(), KainosPlaylistRepository(), client(stranger)).use { sync ->
            assertEquals("kainos-pc", sync.probeHub().getOrThrow().deviceId)
        }
    }

    @Test
    fun everyPairingPostRejectsAnOversizedContentLengthBeforeReadingTheBody() = runBlocking {
        hub.start()
        for (path in pairingPosts) {
            assertEquals(413, rawPairingPost(path, "Content-Length: 8192\r\n", ""), path)
        }
        pairStrangerAfterRejectedRequests()
    }

    @Test
    fun everyPairingPostRejectsAnOversizedChunkBeforeTheStreamEnds() = runBlocking {
        hub.start()
        for (path in pairingPosts) {
            val chunk = "2000\r\n" + "x".repeat(8192) + "\r\n"
            assertEquals(413, rawPairingPost(path, "Transfer-Encoding: chunked\r\n", chunk), path)
        }
        pairStrangerAfterRejectedRequests()
    }

    private val pairingPosts = listOf(
        HOME_LAN_SYNC_PATH_PAIR_START,
        HOME_LAN_SYNC_PATH_PAIR_REVEAL,
        HOME_LAN_SYNC_PATH_PAIR_CONFIRM,
        HOME_LAN_SYNC_PATH_PAIR_CANCEL,
    )

    private fun rawPairingPost(path: String, framing: String, body: String): Int {
        val context = SSLContext.getInstance("TLS").apply {
            init(stranger.keyManagers(), arrayOf(hubPinTrustManager(pc.fingerprint)), null)
        }
        return (context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            socket.soTimeout = 3_000
            socket.startHandshake()
            val request = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\nConnection: close\r\n$framing\r\n$body"
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            socket.inputStream.bufferedReader(Charsets.US_ASCII).readLine().split(' ')[1].toInt()
        }
    }

    private suspend fun pairStrangerAfterRejectedRequests() {
        HttpHomeLanPairingClient(HubEndpoint("127.0.0.1", port), client(stranger)).use { http ->
            val nonce = ByteArray(PAIRING_NONCE_BYTES) { 7 }
            val started = http.start(PairStartRequest("kainos-tablet", "Tablet", pairingCommit(nonce).toHex()))
            http.reveal(PairRevealRequest(started.attemptId, nonce.toHex()))
            http.confirm(started.attemptId)
            assertEquals(PairingReply.Ok(Unit), pairing.confirmFromPc(started.attemptId))
            assertEquals(PairState.PAIRED, http.status(started.attemptId).state)
        }
        assertEquals(stranger.fingerprint, peers.last().fingerprint)
    }

    @Test
    fun theDeviceKeyAndItsPasswordAreOwnerOnly() {
        val ownerOnly = PosixFilePermissions.fromString("rw-------")
        for (name in listOf("device.p12", "device.p12.password")) {
            assertEquals(ownerOnly, Files.getPosixFilePermissions(directory.resolve("pc").resolve(name).toPath()), name)
        }
    }

    @Test
    fun resetCreatesANewFingerprintAndReloadKeepsIt() {
        val identity = JvmDeviceIdentity(directory.resolve("pc"))
        assertEquals(pc.fingerprint, identity.fingerprint())
        val renewed = identity.reset()
        assertEquals(false, renewed == pc.fingerprint)
        assertEquals(renewed, JvmDeviceIdentity(directory.resolve("pc")).fingerprint())
        theDeviceKeyAndItsPasswordAreOwnerOnly()
    }

    @Test
    fun resetFailsWhenTheReadableIdentityFilesCannotBeDeleted() {
        val identityDirectory = directory.resolve("pc").toPath()
        val identity = JvmDeviceIdentity(identityDirectory.toFile())
        val original = identity.fingerprint()
        val originalMode = Files.getPosixFilePermissions(identityDirectory)
        try {
            Files.setPosixFilePermissions(identityDirectory, PosixFilePermissions.fromString("r-x------"))
            assertFailsWith<IOException> { identity.reset() }
            assertEquals(original, identity.fingerprint())
            assertEquals(original, JvmDeviceIdentity(identityDirectory.toFile()).fingerprint())
        } finally {
            Files.setPosixFilePermissions(identityDirectory, originalMode)
        }
    }

}
