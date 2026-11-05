package com.universalmusic.player.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.db.DbKainosPlaylistStore
import com.universalmusic.player.data.db.DbUserLibraryStore
import com.universalmusic.player.data.db.KainosDatabase
import com.universalmusic.player.data.db.KainosStorage
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.platform.JvmDeviceIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Properties
import kotlin.test.Test

/**
 * Seeds two paired desktop profiles with Home pins through the real database stores, and prints
 * what a stopped profile holds. Only runs from the live proof harness.
 *
 * KAINOS_HOME_PINS_PROOF_SETUP=<new dir> KAINOS_HOME_PINS_PROOF_PORT=<hub port>: create profiles `a` (hub) and `b` (client).
 * KAINOS_HOME_PINS_PROOF_DUMP=<profile dir>: print that profile's pins, unpins and order stamp.
 */
class HomePinsProofFixtureTest {
    @Test
    fun prepare() {
        val rootPath = System.getenv("KAINOS_HOME_PINS_PROOF_SETUP")
        assumeTrue("Run through the Home pins proof harness", rootPath != null)
        val root = File(rootPath!!)
        require(!root.exists()) { "Refuse to overwrite a profile" }
        val a = root.resolve("a").apply { mkdirs() }
        val b = root.resolve("b").apply { mkdirs() }
        val aKey = JvmDeviceIdentity(a.resolve("home-lan")).material()
        val bKey = JvmDeviceIdentity(b.resolve("home-lan")).material()
        val hub = PairedPeer("pins-a", "Proof hub A", aKey.fingerprint, "127.0.0.1",
            System.getenv("KAINOS_HOME_PINS_PROOF_PORT").toInt(), 1)
        val client = PairedPeer("pins-b", "Proof client B", bKey.fingerprint, pairedAtMs = 1)
        val common = AppSettings(homeLanSyncEnabled = true, localMusicFoldersConfigured = true)
        a.resolve("settings.json").writeText(Json.encodeToString(common.copy(homeLanSyncRole = HomeLanSyncRole.HUB,
            homeLanSyncDeviceId = hub.deviceId, homeLanSyncPeers = listOf(client))))
        b.resolve("settings.json").writeText(Json.encodeToString(common.copy(homeLanSyncRole = HomeLanSyncRole.CLIENT,
            homeLanSyncDeviceId = client.deviceId, homeLanSyncHub = hub)))
        seed(a, hub.deviceId, "proof-spotify-account-1", listOf("A Morning", "A Evening"), "/proof/A folder", "A account mix")
        seed(b, client.deviceId, "proof-spotify-account-2", listOf("B Road trip"), "/proof/B folder", "B account mix")
    }

    @Test
    fun dump() {
        val profile = System.getenv("KAINOS_HOME_PINS_PROOF_DUMP")
        assumeTrue("Run through the Home pins proof harness", profile != null)
        runBlocking {
            val snapshot = DbUserLibraryStore(storage(File(profile!!))).read()
            println("PROOF account=${snapshot.spotifyAccountId}")
            snapshot.homePins.forEach { println("PROOF pin ${it.kind} ${it.title} ${it.targetId} stamp=${it.syncStamp}") }
            snapshot.homePinTombstones.forEach { println("PROOF unpin ${it.kind} ${it.targetId} stamp=${it.stamp}") }
            println("PROOF order=${snapshot.homePinOrderStamp}")
        }
    }

    private fun seed(profile: File, deviceId: String, account: String, playlists: List<String>, folder: String, spotify: String) =
        runBlocking {
            val storage = storage(profile)
            val library = LibraryRepository(store = DbUserLibraryStore(storage), deviceIdProvider = { deviceId })
            val lists = KainosPlaylistRepository(store = DbKainosPlaylistStore(storage), deviceIdProvider = { deviceId })
            library.load(null)
            lists.load()
            library.setSpotifyAccountId(account)
            for (title in playlists) {
                val playlist = lists.create(title)
                library.pinHome(HomePinKind.KAINOS_PLAYLIST, playlist.id, title, subtitle = "Kainos playlist")
            }
            library.pinHome(HomePinKind.LOCAL_FOLDER, folder, folder.substringAfterLast('/'), subtitle = "Local folder")
            val spotifyId = spotify.replace(' ', '-').lowercase()
            library.pinHome(HomePinKind.PROVIDER_PLAYLIST, "spotify-playlist:$spotifyId", spotify,
                subtitle = "Spotify playlist", providerEntityId = spotifyId, provider = "SPOTIFY")
            lists.flush()
            library.flush()
        }

    private fun storage(profile: File): KainosStorage {
        val url = "jdbc:sqlite:" + profile.resolve("kainos.db").absolutePath
        return KainosStorage(
            openDriver = {
                JdbcSqliteDriver(url, Properties().apply {
                    put("foreign_keys", "true")
                    put("journal_mode", "WAL")
                }, KainosDatabase.Schema)
            },
            clock = System::currentTimeMillis,
        )
    }
}
