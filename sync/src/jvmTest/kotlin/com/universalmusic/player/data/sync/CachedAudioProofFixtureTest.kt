package com.universalmusic.player.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.cache.DefaultHeartedAudioCache
import com.universalmusic.player.data.cache.FileHeartedAudioCacheDisk
import com.universalmusic.player.data.cache.HeartedAudioCacheEntry
import com.universalmusic.player.data.cache.HeartedAudioCacheIndex
import com.universalmusic.player.data.db.DbUserLibraryStore
import com.universalmusic.player.data.db.KainosDatabase
import com.universalmusic.player.data.db.KainosStorage
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.JvmDeviceIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import kotlin.test.Test

/**
 * Seeds two paired desktop profiles that both heart three YouTube tracks, with real cached files on the
 * hub only, through the real database and cache stores. Only runs from the live proof harness.
 *
 * KAINOS_CACHED_AUDIO_PROOF_SETUP=<new dir> KAINOS_CACHED_AUDIO_PROOF_PORT=<hub port>
 * KAINOS_CACHED_AUDIO_PROOF_FILES=<song>,<skipped>,<long>: create profiles `a` (hub) and `b` (client).
 * KAINOS_CACHED_AUDIO_PROOF_DUMP=<data dir>: print that profile's cache entries with hashes and staged files.
 */
class CachedAudioProofFixtureTest {
    @Test
    fun prepare() {
        val rootPath = System.getenv("KAINOS_CACHED_AUDIO_PROOF_SETUP")
        assumeTrue("Run through the cached audio proof harness", rootPath != null)
        val root = File(rootPath!!)
        require(!root.exists()) { "Refuse to overwrite a profile" }
        val a = root.resolve("a").apply { mkdirs() }
        val b = root.resolve("b").apply { mkdirs() }
        val aKey = JvmDeviceIdentity(a.resolve("home-lan")).material()
        val bKey = JvmDeviceIdentity(b.resolve("home-lan")).material()
        val hub = PairedPeer("cached-a", "Proof hub A", aKey.fingerprint, "127.0.0.1",
            System.getenv("KAINOS_CACHED_AUDIO_PROOF_PORT").toInt(), 1)
        val client = PairedPeer("cached-b", "Proof client B", bKey.fingerprint, pairedAtMs = 1)
        val common = AppSettings(homeLanSyncEnabled = true, localMusicFoldersConfigured = true)
        a.resolve("settings.json").writeText(Json.encodeToString(common.copy(homeLanSyncRole = HomeLanSyncRole.HUB,
            homeLanSyncDeviceId = hub.deviceId, homeLanSyncPeers = listOf(client))))
        b.resolve("settings.json").writeText(Json.encodeToString(common.copy(homeLanSyncRole = HomeLanSyncRole.CLIENT,
            homeLanSyncDeviceId = client.deviceId, homeLanSyncHub = hub)))
        val files = System.getenv("KAINOS_CACHED_AUDIO_PROOF_FILES").split(',').map(::File)
        val tracks = listOf(track("proofsong01", "Proof cached song"), track("proofskip01", "Proof skipped song"),
            track("prooflong01", "Proof long song"))
        heart(a, hub.deviceId, tracks)
        heart(b, client.deviceId, tracks)
        runBlocking {
            val cache = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(a.resolve("audio-cache").toPath()))
            tracks.zip(files).forEach { (track, source) ->
                val target = File(cache.audioDirectoryPath(), UUID.randomUUID().toString().replace("-", "") + "." + source.extension)
                source.copyTo(target)
                cache.put(HeartedAudioCacheEntry(track.canonicalId, track.canonicalId.removePrefix("yt:"),
                    target.toURI().toASCIIString(), qualityTier = QualityTier.HIGH.name, qualityCodec = "aac",
                    qualityBitrateKbps = 128, qualitySampleRateHz = 44_100, sizeBytes = target.length(),
                    downloadedAtMs = System.currentTimeMillis()))
            }
        }
    }

    @Test
    fun dump() {
        val profile = System.getenv("KAINOS_CACHED_AUDIO_PROOF_DUMP")
        assumeTrue("Run through the cached audio proof harness", profile != null)
        val root = File(profile!!).resolve("audio-cache")
        // Read the index file directly: opening the cache would reconcile, deleting unreferenced files.
        val index = Json { ignoreUnknownKeys = true }.decodeFromString<HeartedAudioCacheIndex>(root.resolve("index.json").readText())
        run {
            index.entries.values.sortedBy { it.ownerCanonicalId }.forEach { entry ->
                val file = File(java.net.URI(entry.localUri))
                println("PROOF entry ${entry.ownerCanonicalId} video=${entry.youtubeVideoId} size=${entry.sizeBytes} " +
                    "file=${file.name} sha256=${sha256(file)} spotifyMatch=${entry.spotifyViaYouTubeMatch}")
            }
        }
        root.resolve("audio").listFiles()?.sortedBy { it.name }?.forEach { println("PROOF audio-file ${it.name} ${it.length()}") }
        root.resolve("incoming").listFiles()?.forEach { println("PROOF staged ${it.name} ${it.length()}") }
        println("PROOF staged-count ${root.resolve("incoming").listFiles()?.size ?: 0}")
    }

    private fun heart(profile: File, deviceId: String, tracks: List<Track>) = runBlocking {
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
        tracks.forEach { library.toggleFavorite(it) }
        library.flush()
    }

    private fun track(id: String, title: String) = Track(
        canonicalId = "yt:$id",
        title = title,
        artists = listOf(ArtistRef("proof", "Proof Artist")),
        durationMs = 40_000,
        sources = listOf(PlaybackSource(ProviderId.YOUTUBE_MUSIC, id, isPlayable = true,
            handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, id, 40_000))),
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
