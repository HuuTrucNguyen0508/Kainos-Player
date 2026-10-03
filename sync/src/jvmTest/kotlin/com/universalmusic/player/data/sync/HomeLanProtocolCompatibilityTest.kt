package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.PlaylistsSyncDocument
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.createPinnedHomeLanHttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
            val (pairing, tls) = pairing(directory)
            val library = LibraryRepository(deviceIdProvider = { "pc" })
            library.toggleFavorite(track)
            val playlists = KainosPlaylistRepository(deviceIdProvider = { "pc" })
            playlists.create("Local", listOf(track))
            val audio = byteArrayOf(1, 2, 3, 4)
            val vaultRoot = directory.resolve("vault").apply { mkdirs() }
            vaultRoot.resolve("song.flac").writeBytes(audio)
            val vault = JvmHomeLanVaultStore({ vaultRoot.absolutePath }, directory.resolve("vault-state.json").toPath())
            hub = DesktopHomeLanSyncHub(pairing,
                { library.mergeAndPersistSyncState(it); library.exportHeartsSyncDocument() },
                { playlists.mergeAndPersistSyncState(it); playlists.exportSyncDocument() },
                vault, tls, bindHost = "127.0.0.1")
            hub.start()
            HttpHomeLanSyncClient(pairing.copy(deviceId = "phone"), LibraryRepository(),
                KainosPlaylistRepository(), createPinnedHomeLanHttpClient(tls.certSha256Hex)).use { client ->
                assertEquals("localkey:$contentKey", client.syncHearts().getOrThrow().ops.single().canonicalId)
                assertEquals("localkey:$contentKey", client.syncPlaylists().getOrThrow()
                    .playlists.single().entries.single().track.canonicalId)
                assertNotNull(client.fetchRemoteVaultIndex(VaultIndexDocument("phone")).getOrThrow()
                    .entries.single().contentKey)
                assertContentEquals(audio, client.downloadBlob("song.flac", 0, audio.size).getOrThrow())
            }
            createPinnedHomeLanHttpClient(tls.certSha256Hex).use { http ->
                suspend fun legacyPost(path: String, body: String) = http.post("https://127.0.0.1:${pairing.hubPort}$path") {
                    bearerAuth(pairing.sharedSecretHex)
                    setBody(body)
                }.bodyAsText()
                val hearts = json.decodeFromString<HeartsSyncDocument>(legacyPost(HOME_LAN_SYNC_PATH_HEARTS,
                    json.encodeToString(HeartsSyncDocument("old", null))))
                assertEquals("localfile:song.flac", hearts.ops.single().canonicalId)
                assertNull(hearts.ops.single().localIdentity)
                val oldPlaylists = json.decodeFromString<PlaylistsSyncDocument>(legacyPost(HOME_LAN_SYNC_PATH_PLAYLISTS,
                    json.encodeToString(PlaylistsSyncDocument("old"))))
                assertEquals("localfile:song.flac", oldPlaylists.playlists.single().entries.single().track.canonicalId)
                val key = SyncPayloadCrypto.keyFromSharedSecret(pairing.sharedSecretHex)
                val indexBody = json.encodeToString(VaultIndexDocument("old"))
                val encrypted = http.post("https://127.0.0.1:${pairing.hubPort}$HOME_LAN_SYNC_PATH_VAULT_INDEX") {
                    bearerAuth(pairing.sharedSecretHex)
                    header(HOME_LAN_SYNC_HEADER_ENC, HOME_LAN_SYNC_ENC_AES_GCM)
                    setBody(SyncPayloadCrypto.encryptToBase64(indexBody.encodeToByteArray(), key))
                }.bodyAsText()
                val oldIndex = json.decodeFromString<VaultIndexDocument>(
                    SyncPayloadCrypto.decryptFromBase64(encrypted, key).decodeToString())
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
            val (pairing, tls) = pairing(directory)
            val seen = mutableListOf<String>()
            val key = SyncPayloadCrypto.keyFromSharedSecret(pairing.sharedSecretHex)
            val legacy = embeddedServer(Netty, environment = applicationEnvironment {}, configure = {
                sslConnector(tls.keyStore, tls.keyAlias, { tls.keyStorePassword }, { tls.privateKeyPassword }) {
                    host = "127.0.0.1"; port = pairing.hubPort
                }
            }) {
                routing {
                    for (path in listOf(HOME_LAN_SYNC_PATH_HEARTS, HOME_LAN_SYNC_PATH_PLAYLISTS, HOME_LAN_SYNC_PATH_VAULT_INDEX)) {
                        post(path) {
                            val body = call.receiveText()
                            val plain = if (path == HOME_LAN_SYNC_PATH_VAULT_INDEX)
                                SyncPayloadCrypto.decryptFromBase64(body, key).decodeToString() else body
                            seen += plain
                            val reply = when (path) {
                                HOME_LAN_SYNC_PATH_HEARTS -> json.encodeToString(HeartsSyncDocument("old", null))
                                HOME_LAN_SYNC_PATH_PLAYLISTS -> json.encodeToString(PlaylistsSyncDocument("old"))
                                else -> SyncPayloadCrypto.encryptToBase64(plain.encodeToByteArray(), key)
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
                HttpHomeLanSyncClient(pairing, library, playlists,
                    createPinnedHomeLanHttpClient(tls.certSha256Hex)).use { client ->
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

    private fun pairing(directory: java.io.File): Pair<HomeLanSyncPairing, HomeLanHubTlsMaterial> {
        val secret = "aabbccddeeff00112233445566778899"
        val tls = generateHomeLanHubTls(directory, secret)
        val port = ServerSocket(0).use { it.localPort }
        return HomeLanSyncPairing(deviceId = "pc", sharedSecretHex = secret, hubHost = "127.0.0.1",
            hubPort = port, hubCertSha256Hex = tls.certSha256Hex, encryptPayloads = true) to tls
    }
}
