package com.universalmusic.player.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.db.DbUserLibraryStore
import com.universalmusic.player.data.db.KainosDatabase
import com.universalmusic.player.data.db.KainosStorage
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.JvmDeviceIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Properties
import kotlin.test.Test

/**
 * Seeds two paired desktop profiles with Spotify-scoped app hearts under a fake account key,
 * through the real database store. Only runs from the live proof harness.
 *
 * KAINOS_SPOTIFY_HEARTS_PROOF_SETUP=<new dir> KAINOS_SPOTIFY_HEARTS_PROOF_PORT=<hub port>: create profiles `a` (hub) and `b` (client).
 * KAINOS_SPOTIFY_HEARTS_PROOF_SWITCH=<profile dir> KAINOS_SPOTIFY_HEARTS_PROOF_ACCOUNT=<id>: switch that profile's account and heart one track.
 */
class SpotifyHeartsProofFixtureTest {
    @Test
    fun prepare() {
        val rootPath = System.getenv("KAINOS_SPOTIFY_HEARTS_PROOF_SETUP")
        assumeTrue("Run through the Spotify hearts proof harness", rootPath != null)
        val root = File(rootPath!!)
        require(!root.exists()) { "Refuse to overwrite a profile" }
        val a = root.resolve("a").apply { mkdirs() }
        val b = root.resolve("b").apply { mkdirs() }
        val aKey = JvmDeviceIdentity(a.resolve("home-lan")).material()
        val bKey = JvmDeviceIdentity(b.resolve("home-lan")).material()
        val hub = PairedPeer("spotheart-a", "Proof hub A", aKey.fingerprint, "127.0.0.1",
            System.getenv("KAINOS_SPOTIFY_HEARTS_PROOF_PORT").toInt(), 1)
        val client = PairedPeer("spotheart-b", "Proof client B", bKey.fingerprint, pairedAtMs = 1)
        val common = AppSettings(homeLanSyncEnabled = true, localMusicFoldersConfigured = true)
        a.resolve("settings.json").writeText(Json.encodeToString(common.copy(homeLanSyncRole = HomeLanSyncRole.HUB,
            homeLanSyncDeviceId = hub.deviceId, homeLanSyncPeers = listOf(client))))
        b.resolve("settings.json").writeText(Json.encodeToString(common.copy(homeLanSyncRole = HomeLanSyncRole.CLIENT,
            homeLanSyncDeviceId = client.deviceId, homeLanSyncHub = hub)))
        seed(a, hub.deviceId, SAME_ACCOUNT, spotifyTrack("proofhub1", "Hub heart"))
        seed(b, client.deviceId, SAME_ACCOUNT, spotifyTrack("proofclient1", "Client heart"))
    }

    @Test
    fun switchAccount() {
        val profile = System.getenv("KAINOS_SPOTIFY_HEARTS_PROOF_SWITCH")
        assumeTrue("Run through the Spotify hearts proof harness", profile != null)
        seed(File(profile!!), "spotheart-b", System.getenv("KAINOS_SPOTIFY_HEARTS_PROOF_ACCOUNT")!!,
            spotifyTrack("proofother1", "Other account heart"))
    }

    private fun seed(profile: File, deviceId: String, account: String, track: Track) = runBlocking {
        val url = "jdbc:sqlite:" + profile.resolve("kainos.db").absolutePath
        val storage = KainosStorage(
            openDriver = {
                JdbcSqliteDriver(url, Properties().apply {
                    put("foreign_keys", "true")
                    put("journal_mode", "WAL")
                }, KainosDatabase.Schema)
            },
            clock = System::currentTimeMillis,
        )
        val library = LibraryRepository(store = DbUserLibraryStore(storage), deviceIdProvider = { deviceId })
        library.load(null)
        library.setSpotifyAccountId(account)
        library.toggleFavorite(track)
        library.flush()
    }

    private fun spotifyTrack(id: String, title: String) = Track(
        canonicalId = "spotify:$id",
        title = title,
        artists = listOf(ArtistRef("proof", "Proof Artist")),
        durationMs = 180_000,
        sources = listOf(PlaybackSource(ProviderId.SPOTIFY, id, streamUrl = null, isPlayable = true,
            handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, id, 180_000))),
    )

    private companion object {
        const val SAME_ACCOUNT = "proof-spotify-account-1"
    }
}
