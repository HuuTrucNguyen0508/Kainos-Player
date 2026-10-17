package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.PlaylistsSyncDocument
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.pinnedHomeLanHttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real loopback HTTPS sockets and certificate pinning, without the user's pairing or library. */
class HomeLanProtocolCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val contentKey = "lc1:" + "a".repeat(64)
    private val track = Track("local:pc", "Song", emptyList(), localContentKey = contentKey,
        sources = listOf(PlaybackSource(ProviderId.LOCAL, "pc", isPlayable = true,
            handle = PlaybackHandle.Url("file:///music/song.flac"))))

    @Test
    fun newHubServesKeyedV2AndDowngradedV1OverPinnedHttps() = runBlocking {
        val directory = Files.createTempDirectory("kainos-protocol-").toFile()
        var hub: DesktopHomeLanSyncHub? = null
        try {
            val (pc, phone, port) = devices(directory)
            val library = LibraryRepository(deviceIdProvider = { "pc" })
            library.toggleFavorite(track)
            val playlists = KainosPlaylistRepository(deviceIdProvider = { "pc" })
            playlists.create("Local", listOf(track))
            val audio = byteArrayOf(1, 2, 3, 4)
            val vaultRoot = directory.resolve("vault").apply { mkdirs() }
            vaultRoot.resolve("song.flac").writeBytes(audio)
            val vault = JvmHomeLanVaultStore({ vaultRoot.absolutePath }, directory.resolve("vault-state.json").toPath())
            hub = hub(pc, phone, port,
                { library.mergeAndPersistSyncState(it); library.exportHeartsSyncDocument() },
                { playlists.mergeAndPersistSyncState(it); playlists.exportSyncDocument() },
                vault, bindHost = "127.0.0.1")
            hub.start()
            HttpHomeLanSyncClient(hubPeer(pc, port), LibraryRepository(),
                KainosPlaylistRepository(), client(pc, phone)).use { client ->
                assertEquals("localkey:$contentKey", client.syncHearts().getOrThrow().ops.single().canonicalId)
                assertEquals("localkey:$contentKey", client.syncPlaylists().getOrThrow()
                    .playlists.single().entries.single().track.canonicalId)
                assertNotNull(client.fetchRemoteVaultIndex(VaultIndexDocument("phone")).getOrThrow()
                    .entries.single().contentKey)
                assertContentEquals(audio, client.downloadBlob("song.flac", 0, audio.size).getOrThrow())
            }
            client(pc, phone).use { http ->
                suspend fun legacyPost(path: String, body: String) = http.post("https://127.0.0.1:$port$path") {
                    setBody(body)
                }.bodyAsText()
                val hearts = json.decodeFromString<HeartsSyncDocument>(legacyPost(HOME_LAN_SYNC_PATH_HEARTS,
                    json.encodeToString(HeartsSyncDocument("old", null))))
                assertEquals("localfile:song.flac", hearts.ops.single().canonicalId)
                assertNull(hearts.ops.single().localIdentity)
                val oldPlaylists = json.decodeFromString<PlaylistsSyncDocument>(legacyPost(HOME_LAN_SYNC_PATH_PLAYLISTS,
                    json.encodeToString(PlaylistsSyncDocument("old"))))
                assertEquals("localfile:song.flac", oldPlaylists.playlists.single().entries.single().track.canonicalId)
                val oldIndex = json.decodeFromString<VaultIndexDocument>(legacyPost(HOME_LAN_SYNC_PATH_VAULT_INDEX,
                    json.encodeToString(VaultIndexDocument("old"))))
                assertNull(oldIndex.entries.single().contentKey)
            }
        } finally {
            hub?.stop()
            directory.deleteRecursively()
        }
    }

    @Test
    fun newPinnedClientRetriesLegacyOnlyHubAndSendsBasenameIdentities() = runBlocking {
        val directory = Files.createTempDirectory("kainos-legacy-protocol-").toFile()
        try {
            val (pc, phone, hubPort) = devices(directory)
            val seen = mutableListOf<String>()
            val legacy = embeddedServer(Netty, environment = applicationEnvironment {}, configure = {
                sslConnector(pc.keyStore, JvmDeviceIdentity.KEY_ALIAS, { pc.password() }, { pc.password() }) {
                    host = "127.0.0.1"; port = hubPort
                }
            }) {
                routing {
                    for (path in listOf(HOME_LAN_SYNC_PATH_HEARTS, HOME_LAN_SYNC_PATH_PLAYLISTS, HOME_LAN_SYNC_PATH_VAULT_INDEX)) {
                        post(path) {
                            val body = call.receiveText()
                            seen += body
                            val reply = when (path) {
                                HOME_LAN_SYNC_PATH_HEARTS -> json.encodeToString(HeartsSyncDocument("old", null))
                                HOME_LAN_SYNC_PATH_PLAYLISTS -> json.encodeToString(PlaylistsSyncDocument("old"))
                                else -> body
                            }
                            call.respondText(reply, ContentType.Application.Json, HttpStatusCode.OK)
                        }
                    }
                }
            }.start(wait = false)
            try {
                val library = LibraryRepository()
                library.toggleFavorite(track)
                val playlists = KainosPlaylistRepository()
                playlists.create("Local", listOf(track))
                HttpHomeLanSyncClient(hubPeer(pc, hubPort), library, playlists, client(pc, phone)).use { client ->
                    client.syncHearts().getOrThrow()
                    client.syncPlaylists().getOrThrow()
                    client.fetchRemoteVaultIndex(VaultIndexDocument("phone",
                        entries = listOf(VaultFileEntry("song.flac", 4, 0, contentKey = contentKey)))).getOrThrow()
                }
                assertEquals(3, seen.size)
                assertEquals("localfile:song.flac", json.decodeFromString<HeartsSyncDocument>(seen[0]).ops.single().canonicalId)
                assertEquals("localfile:song.flac", json.decodeFromString<PlaylistsSyncDocument>(seen[1])
                    .playlists.single().entries.single().track.canonicalId)
                assertNull(json.decodeFromString<VaultIndexDocument>(seen[2]).entries.single().contentKey)
                assertFalse(seen.any { "localkey:" in it || contentKey in it })
            } finally { legacy.stop(0, 1_000) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun pinnedClientSyncsThroughAnAddressOutsideTheCertificateSans() = runBlocking {
        val directory = Files.createTempDirectory("kainos-non-san-").toFile()
        var hub: DesktopHomeLanSyncHub? = null
        try {
            val (pc, phone, port) = devices(directory)
            val pcLibrary = LibraryRepository(deviceIdProvider = { "pc" })
            pcLibrary.toggleFavorite(track)
            hub = hub(pc, phone, port,
                { pcLibrary.mergeAndPersistSyncState(it); pcLibrary.exportHeartsSyncDocument() },
                { it }, vault = null, bindHost = "0.0.0.0")
            hub.start()
            HttpHomeLanSyncClient(hubPeer(pc, port).copy(lastKnownHost = "127.0.0.2"), LibraryRepository(),
                KainosPlaylistRepository(), client(pc, phone)).use { client ->
                assertEquals("pc", client.probeHub().getOrThrow().deviceId)
                assertEquals("localkey:$contentKey", client.syncHearts().getOrThrow().ops.single().canonicalId)
            }
        } finally {
            hub?.stop()
            directory.deleteRecursively()
        }
    }

    @Test
    fun hubWithAnotherCertificateFailsWithThePinMismatchMessage() = runBlocking {
        val directory = Files.createTempDirectory("kainos-pin-mismatch-").toFile()
        var hub: DesktopHomeLanSyncHub? = null
        try {
            val (pc, phone, port) = devices(directory)
            hub = hub(pc, phone, port, { it }, { it }, vault = null, bindHost = "127.0.0.1")
            hub.start()
            val other = JvmDeviceIdentity(directory.resolve("other")).material()
            val result = HttpHomeLanSyncClient(hubPeer(pc, port), LibraryRepository(), KainosPlaylistRepository(),
                client(other, phone)).use { it.probeHub() }
            assertContains(result.exceptionOrNull()?.message.orEmpty(), "Hub certificate pin mismatch")
        } finally {
            hub?.stop()
            directory.deleteRecursively()
        }
    }

    @Test
    fun probeOfAHubThatNeverFinishesTlsGivesUpWithinTheBudget() = runBlocking {
        val directory = Files.createTempDirectory("kainos-silent-hub-").toFile()
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            try {
                val (pc, phone) = devices(directory)
                val accepted = Thread { runCatching { silent.accept() } }.apply { isDaemon = true; start() }
                val resolver = HubEndpointResolver(
                    browser = { kotlinx.coroutines.flow.emptyFlow() },
                    probe = { endpoint ->
                        HttpHomeLanSyncClient(hubPeer(pc, endpoint.port).copy(lastKnownHost = endpoint.host),
                            LibraryRepository(), KainosPlaylistRepository(), client(pc, phone)).use { it.probeHub() }.map { }
                    },
                )
                val startedAt = System.nanoTime()
                val result = resolver.resolve(HubEndpoint("127.0.0.1", silent.localPort), pc.fingerprint.hex)
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                assertIs<ResolveResult.HubNotFound>(result)
                assertTrue(elapsedMs in 3_000..3_900, "probe took $elapsedMs ms")
                accepted.join(1_000)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private data class Devices(val pc: JvmDeviceIdentity.Material, val phone: JvmDeviceIdentity.Material, val port: Int)

    private fun devices(directory: java.io.File) = Devices(
        JvmDeviceIdentity(directory.resolve("pc")).material(),
        JvmDeviceIdentity(directory.resolve("phone")).material(),
        ServerSocket(0).use { it.localPort },
    )

    private fun hubPeer(pc: JvmDeviceIdentity.Material, port: Int) =
        PairedPeer("pc", "Kainos on pc", pc.fingerprint, "127.0.0.1", port, pairedAtMs = 1L)

    private fun client(hub: JvmDeviceIdentity.Material, phone: JvmDeviceIdentity.Material) =
        pinnedHomeLanHttpClient(hub.fingerprint, phone.keyManagers())

    private fun hub(
        pc: JvmDeviceIdentity.Material,
        phone: JvmDeviceIdentity.Material,
        port: Int,
        onHearts: suspend (HeartsSyncDocument) -> HeartsSyncDocument,
        onPlaylists: suspend (PlaylistsSyncDocument) -> PlaylistsSyncDocument,
        vault: HomeLanVaultStore?,
        bindHost: String,
    ) = DesktopHomeLanSyncHub(
        HomeLanHubConfig(
            deviceId = "pc",
            port = port,
            peers = { listOf(PairedPeer("phone", "Phone", phone.fingerprint, pairedAtMs = 1L)) },
            pairing = PairingHost({ pc.fingerprint }, { "pc" }, onPaired = {}),
            onHearts = onHearts,
            onPlaylists = onPlaylists,
            vault = vault,
            heartedVaultFileNames = { null },
        ),
        pc,
        bindHost = bindHost,
    )
}
