package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.YouTubeDownloadResult
import com.universalmusic.player.platform.YouTubeFailureKind
import com.universalmusic.player.data.cache.CachedAudioCopies
import com.universalmusic.player.data.cache.DefaultHeartedAudioCache
import com.universalmusic.player.data.cache.FileHeartedAudioCacheDisk
import com.universalmusic.player.data.cache.HEARTED_AUDIO_CACHE_MAX_BYTES
import com.universalmusic.player.data.cache.HEARTED_AUDIO_CACHE_PROVIDER_PREFIX
import com.universalmusic.player.data.cache.EvictionRequest
import com.universalmusic.player.data.cache.HeartedAudioCache
import com.universalmusic.player.data.cache.HeartedAudioCacheEntry
import com.universalmusic.player.data.cache.HeartedAudioCacheService
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.offlineOnly
import com.universalmusic.player.data.cache.toLocalPlaybackSource
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.DownloadedYouTubeAudio
import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.YouTubeAudioDownloader
import com.universalmusic.player.platform.pinnedHomeLanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cached-audio copies between two real caches over a real pinned HTTPS hub on loopback. */
class CachedAudioSyncTest {
    private val size = VAULT_BLOB_CHUNK_BYTES * 2 + 1234

    @Test
    fun aYouTubeHeartCachedOnTheHubDownloadsVerifiedAndPlaysFromThePhoneCache() = scenario { pc, phone, client ->
        val track = ytTrack("aaa")
        pc.heart(track); phone.heart(track)
        val hubFile = pc.seed(track, bytes(size, 3))

        val pending = phone.plan(client)
        assertEquals(listOf(VaultCopyDirection.TO_LOCAL), pending.map { it.direction })
        assertEquals("Download offline copy: Song aaa · Artist", pending.single().label)
        assertEquals("Copied Song aaa", phone.execute(client, pending.single()))

        val entry = assertNotNull(phone.cache.get("yt:aaa"))
        val copied = File(java.net.URI(entry.localUri))
        assertTrue(copied.parentFile == phone.audioDir, "copy lives in the phone's own audio cache")
        assertContentEquals(hubFile.readBytes(), copied.readBytes())
        assertEquals("aaa", entry.youtubeVideoId)
        assertEquals(size.toLong(), entry.sizeBytes)
        val saved = phone.library.savedTracks.value.single { it.canonicalId == "yt:aaa" }
        assertEquals(TrackAvailability.CACHED, phone.service.availabilityFor(saved).status)
        assertEquals(listOf("file"), saved.offlineOnly()?.sources?.map { (it.handle as PlaybackHandle.Url).url.substringBefore(':') })
        assertTrue(phone.incoming().isEmpty())
        assertTrue(phone.plan(client).isEmpty(), "nothing left to copy")
    }

    @Test
    fun aPhoneCachedFileTheHubLacksUploadsAndTheHubVerifiesItsHash() = scenario { pc, phone, client ->
        val track = ytTrack("bbb")
        pc.heart(track); phone.heart(track)
        val phoneFile = phone.seed(track, bytes(size, 5))

        val pending = phone.plan(client).single()
        assertEquals(VaultCopyDirection.TO_REMOTE, pending.direction)
        phone.execute(client, pending)

        val entry = assertNotNull(pc.cache.get("yt:bbb"))
        assertContentEquals(phoneFile.readBytes(), File(java.net.URI(entry.localUri)).readBytes())
        assertTrue(pc.incoming().isEmpty())
        assertTrue(pc.library.savedTracks.value.single().sources.any {
            it.providerTrackId == "${HEARTED_AUDIO_CACHE_PROVIDER_PREFIX}bbb"
        })
    }

    @Test
    fun aSpotifyMatchCrossesOnlyToAPeerOnTheSameSpotifyAccount() = runBlocking {
        val track = spotifyTrack("sss")
        suspend fun offered(phoneAccount: String?): List<String> {
            var result = emptyList<String>()
            scenario(pcAccount = "account-a", phoneAccount = phoneAccount) { pc, phone, client ->
                pc.heart(track)
                if (phoneAccount == "account-a") phone.heart(track)
                pc.seed(track, bytes(4096, 1), spotifyMatch = true)
                val reply = assertNotNull(client.syncCachedAudio(phone.document()).getOrThrow())
                result = reply.offers.map { it.ownerCanonicalId }
                if (phoneAccount != "account-a") {
                    val token = pc.copies.offers { null }.single().fileToken
                    assertTrue(client.fetchCachedAudioHash("spotify:sss", token).isFailure, "hub serves no other account")
                } else {
                    phone.execute(client, phone.plan(client).single())
                    assertTrue(assertNotNull(phone.cache.get("spotify:sss")).spotifyViaYouTubeMatch)
                }
            }
            return result
        }
        assertEquals(listOf("spotify:sss"), offered("account-a"))
        assertEquals(emptyList(), offered("account-b"))
        assertEquals(emptyList(), offered(null))
    }

    @Test
    fun theReceiverIsOfferedOnlyWhatItHeartsAndLacks() {
        fun offer(owner: String) = CachedAudioOffer(owner, owner.substringAfter(':'), UUID.randomUUID().toString().replace("-", "") + ".m4a", 10)
        val local = CachedAudioSyncDocument("phone", "acct", offers = listOf(offer("yt:mine")), heldOwners = listOf("yt:mine", "yt:held"))
        val remote = CachedAudioSyncDocument("pc", "acct",
            offers = listOf(offer("yt:held"), offer("yt:unhearted"), offer("yt:wanted"), offer("spotify:match")),
            heldOwners = listOf("yt:held", "yt:unhearted", "yt:wanted", "spotify:match", "yt:mine"))
        val plan = planCachedAudioTransfers(local, remote, setOf("yt:mine", "yt:held", "yt:wanted", "spotify:match"))
        assertEquals(listOf("yt:wanted" to VaultCopyDirection.TO_LOCAL, "spotify:match" to VaultCopyDirection.TO_LOCAL),
            plan.map { it.offer.ownerCanonicalId to it.direction })
        assertEquals(listOf("yt:mine"), planCachedAudioTransfers(local, remote.copy(offers = emptyList(), heldOwners = emptyList()), emptySet())
            .map { it.offer.ownerCanonicalId })
    }

    @Test
    fun aCorruptedChunkFailsTheHashCheckAndLeavesNoCacheEntryOrFile() = scenario { pc, phone, client ->
        val track = ytTrack("ccc")
        pc.heart(track); phone.heart(track)
        pc.seed(track, bytes(size, 7))
        val flipping = object : HomeLanSyncClient by client {
            override suspend fun downloadCachedAudio(owner: String, token: String, offset: Long, length: Int) =
                client.downloadCachedAudio(owner, token, offset, length).map { chunk ->
                    if (offset > 0L) chunk.also { it[0] = (it[0] + 1).toByte() } else chunk
                }
        }

        val failure = assertFails { phone.execute(flipping, phone.plan(client).single()) }

        assertContains(failure.message.orEmpty(), "SHA-256")
        assertNull(phone.cache.get("yt:ccc"))
        assertTrue(phone.audioFiles().isEmpty())
        assertTrue(phone.incoming().isEmpty())
    }

    @Test
    fun anInterruptedDownloadLeavesNoPartialEntryAndTransferAgainCompletes() = scenario { pc, phone, client ->
        val track = ytTrack("ddd")
        pc.heart(track); phone.heart(track)
        val hubFile = pc.seed(track, bytes(size, 9))
        val dropping = object : HomeLanSyncClient by client {
            override suspend fun downloadCachedAudio(owner: String, token: String, offset: Long, length: Int) =
                if (offset > 0L) Result.failure(IOException("Wi-Fi dropped")) else client.downloadCachedAudio(owner, token, offset, length)
        }
        val pending = phone.plan(client).single()

        assertContains(assertFails { phone.execute(dropping, pending) }.message.orEmpty(), "Wi-Fi dropped")
        assertNull(phone.cache.get("yt:ddd"))
        assertTrue(phone.audioFiles().isEmpty())
        assertTrue(phone.incoming().isEmpty())

        phone.execute(client, pending)
        assertContentEquals(hubFile.readBytes(), File(java.net.URI(assertNotNull(phone.cache.get("yt:ddd")).localUri)).readBytes())
    }

    @Test
    fun clearingTheOfflineCacheDuringACopyKeepsTheCopyOut() = scenario { pc, phone, client ->
        val track = ytTrack("kkk")
        pc.heart(track); phone.heart(track)
        pc.seed(track, bytes(size, 2))
        val clearing = object : HomeLanSyncClient by client {
            override suspend fun downloadCachedAudio(owner: String, token: String, offset: Long, length: Int) =
                client.downloadCachedAudio(owner, token, offset, length).also { if (offset == 0L) phone.service.clear() }
        }

        val outcome = phone.execute(clearing, phone.plan(client).single())

        assertContains(outcome, "Skipped Song kkk")
        assertNull(phone.cache.get("yt:kkk"))
        assertTrue(phone.audioFiles().isEmpty())
        assertTrue(phone.incoming().isEmpty())
    }

    @Test
    fun aClearThatLandsWhileTheCopyIsBeingStoredStillWins() = scenario { pc, phone, client ->
        val track = ytTrack("lll")
        pc.heart(track); phone.heart(track)
        pc.seed(track, bytes(4096, 3))
        val pending = phone.plan(client).single()
        phone.duringPut = {
            phone.duringPut = {}
            val before = phone.service.copyGeneration("yt:lll")
            // The clear bumps its generation at once, then waits for the put to finish before it empties the cache.
            CoroutineScope(Dispatchers.Default).launch { phone.service.clear() }
            while (phone.service.copyGeneration("yt:lll") == before) delay(1)
        }

        val outcome = phone.execute(client, pending)

        assertContains(outcome, "Skipped Song lll")
        withTimeout(5_000) { while (phone.cache.allEntries().isNotEmpty() || phone.audioFiles().isNotEmpty()) delay(10) }
        assertFalse(phone.library.savedTracks.value.single().sources.any {
            it.providerTrackId.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX)
        })
    }

    @Test
    fun pinnedDownloadsThatFillTheBudgetStopTheCopyBeforeAnyByteMoves() = scenario(phoneMaxBytes = size + 100L) { pc, phone, client ->
        val track = ytTrack("eee")
        val pinned = ytTrack("pinned")
        pc.heart(track); phone.heart(track); phone.heart(pinned)
        pc.seed(track, bytes(size, 1))
        phone.seed(pinned, bytes(200, 2))
        phone.cache.setPinned("yt:pinned", true)
        var chunks = 0
        val counting = object : HomeLanSyncClient by client {
            override suspend fun downloadCachedAudio(owner: String, token: String, offset: Long, length: Int) =
                client.downloadCachedAudio(owner, token, offset, length).also { chunks++ }
        }
        val pending = phone.plan(client).single { it.direction == VaultCopyDirection.TO_LOCAL }

        assertContains(assertFails { phone.execute(counting, pending) }.message.orEmpty(), "Offline cache full")
        assertEquals(0, chunks)
        assertNull(phone.cache.get("yt:eee"))
        assertNotNull(phone.cache.get("yt:pinned"))
    }

    @Test
    fun aCopyEvictsTheOldestUnpinnedDownloadButNeverAPinnedOne() = scenario(phoneMaxBytes = size + 300L) { pc, phone, client ->
        val track = ytTrack("fff")
        pc.heart(track); phone.heart(track)
        listOf("old", "pinned").forEach { phone.heart(ytTrack(it)) }
        pc.seed(track, bytes(size, 1))
        phone.seed(ytTrack("pinned"), bytes(200, 2), downloadedAtMs = 1)
        phone.seed(ytTrack("old"), bytes(200, 3), downloadedAtMs = 2)
        phone.cache.setPinned("yt:pinned", true)

        phone.execute(client, phone.plan(client).single { it.direction == VaultCopyDirection.TO_LOCAL })

        assertEquals(setOf("yt:fff", "yt:pinned"), phone.cache.allEntries().map { it.ownerCanonicalId }.toSet())
        assertFalse(phone.library.savedTracks.value.single { it.canonicalId == "yt:old" }.sources.any {
            it.providerTrackId.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX)
        })
    }

    @Test
    fun theHubReadsOnlyIndexedHeartedFilesAndAcceptsOnlyOfferedUploads() = scenario { pc, phone, client ->
        val hearted = ytTrack("ggg")
        val notHearted = ytTrack("hhh")
        pc.heart(hearted)
        pc.seed(hearted, bytes(4096, 1))
        pc.seed(notHearted, bytes(4096, 2))
        val tokens = pc.copies.offers { null }.associate { it.ownerCanonicalId to it.fileToken }

        assertTrue(client.fetchCachedAudioHash("yt:ggg", tokens.getValue("yt:ggg")).isSuccess)
        assertTrue(client.fetchCachedAudioHash("yt:hhh", tokens.getValue("yt:hhh")).isFailure, "not hearted on the hub")
        assertTrue(client.downloadCachedAudio("yt:ggg", "0".repeat(32) + ".m4a", 0, 16).isFailure, "wrong file token")
        assertTrue(client.downloadCachedAudio("yt:ggg", "../index.json", 0, 16).isFailure, "no paths")
        val upload = client.uploadCachedAudio("yt:ggg", tokens.getValue("yt:ggg"), 0, 4096, "0".repeat(64), bytes(4096, 1))
        assertContains(upload.exceptionOrNull()?.message.orEmpty(), "HTTP 409")
    }

    @Test
    fun theHubRefusesAnUploadWhoseBytesDoNotMatchTheSentHash() = scenario { pc, phone, client ->
        val track = ytTrack("iii")
        pc.heart(track); phone.heart(track)
        phone.seed(track, bytes(4096, 4))
        val offer = phone.plan(client).single().offer

        val reply = client.uploadCachedAudio("yt:iii", offer.fileToken, 0, 4096, "0".repeat(64), bytes(4096, 4))

        assertContains(reply.exceptionOrNull()?.message.orEmpty(), "HTTP 422")
        assertNull(pc.cache.get("yt:iii"))
        assertTrue(pc.audioFiles().isEmpty())
        assertTrue(pc.incoming().isEmpty())
    }

    @Test
    fun anInterruptedUploadNeverPublishesAndTransferAgainStartsClean() = scenario { pc, phone, client ->
        val track = ytTrack("jjj")
        pc.heart(track); phone.heart(track)
        val phoneFile = phone.seed(track, bytes(size, 6))
        val dropping = object : HomeLanSyncClient by client {
            override suspend fun uploadCachedAudio(owner: String, token: String, offset: Long, totalSize: Long, sha256: String, chunk: ByteArray) =
                if (offset > 0L) Result.failure(IOException("Wi-Fi dropped"))
                else client.uploadCachedAudio(owner, token, offset, totalSize, sha256, chunk)
        }
        val pending = phone.plan(client).single()

        assertFails { phone.execute(dropping, pending) }
        assertNull(pc.cache.get("yt:jjj"))
        assertTrue(pc.audioFiles().isEmpty())
        assertEquals(1, pc.incoming().size, "the hub cannot tell a dropped upload from a slow one at once")
        pc.nowMs += 11 * 60_000L
        client.syncCachedAudio(phone.document()).getOrThrow()
        assertTrue(pc.incoming().isEmpty(), "the next sync sweeps the abandoned upload")

        phone.execute(client, pending)
        assertContentEquals(phoneFile.readBytes(), File(java.net.URI(assertNotNull(pc.cache.get("yt:jjj")).localUri)).readBytes())
        assertTrue(pc.incoming().isEmpty())
    }

    @Test
    fun aHubFromBeforeCachedAudioSyncAnswers404AndTheClientSkips() = scenario(hubServesCachedAudio = false) { _, phone, client ->
        assertNull(client.syncCachedAudio(phone.document()).getOrThrow())
    }

    private class Device(root: File, val name: String, maxBytes: Long, private val scope: CoroutineScope) {
        private val cacheRoot = root.resolve("$name-cache")
        private val disk = DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(cacheRoot.toPath()), maxBytes)
        /** Runs inside each cache put, before it persists. */
        var duringPut: suspend () -> Unit = {}
        val cache = object : HeartedAudioCache by disk {
            override suspend fun put(entry: HeartedAudioCacheEntry, eviction: EvictionRequest): HeartedAudioCacheEntry {
                duringPut()
                return disk.put(entry, eviction)
            }
        }
        val library = LibraryRepository(deviceIdProvider = { name })
        val service = HeartedAudioCacheService(scope, cache, NoDownloader, youtubeSearch = { emptyList() },
            networkAvailable = { false }, maxBytes = maxBytes,
            onCached = { id, entry -> library.attachHeartedCacheSource(id, entry.toLocalPlaybackSource()) },
            onRemoved = { id -> library.stripHeartedCacheSource(id) })
        var nowMs = System.currentTimeMillis()
        val copies = CachedAudioCopies(cache, service, clock = { nowMs })
        val audioDir: File get() = File(cache.audioDirectoryPath())

        fun heart(track: Track) { library.toggleFavorite(track) }

        suspend fun seed(track: Track, content: ByteArray, spotifyMatch: Boolean = false, downloadedAtMs: Long = 1): File {
            val file = File(audioDir, UUID.randomUUID().toString().replace("-", "") + ".m4a").apply { writeBytes(content) }
            val videoId = track.canonicalId.substringAfter(':')
            cache.put(HeartedAudioCacheEntry(track.canonicalId, videoId, file.toURI().toASCIIString(),
                qualityCodec = "aac", sizeBytes = content.size.toLong(), downloadedAtMs = downloadedAtMs,
                spotifyViaYouTubeMatch = spotifyMatch))
            return file
        }

        suspend fun document() = cachedAudioDocument(name, library, copies)

        suspend fun plan(client: HomeLanSyncClient): List<PendingCachedAudioTransfer> {
            val local = document()
            return planCachedAudioTransfers(local, assertNotNull(client.syncCachedAudio(local).getOrThrow()), library.favoriteIds.value)
        }

        suspend fun execute(client: HomeLanSyncClient, transfer: PendingCachedAudioTransfer) =
            executeCachedAudioTransfer(copies, client, transfer, { library.isFavorite(transfer.offer.ownerCanonicalId) }) { _, _, _ -> }

        fun incoming(): List<File> = cacheRoot.resolve("incoming").listFiles()?.toList().orEmpty()
        fun audioFiles(): List<File> = audioDir.listFiles()?.toList().orEmpty()
    }

    private fun scenario(
        pcAccount: String? = null,
        phoneAccount: String? = null,
        phoneMaxBytes: Long = HEARTED_AUDIO_CACHE_MAX_BYTES,
        hubServesCachedAudio: Boolean = true,
        block: suspend (pc: Device, phone: Device, client: HttpHomeLanSyncClient) -> Unit,
    ) = runBlocking {
        val directory = Files.createTempDirectory("kainos-cached-audio-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var hub: DesktopHomeLanSyncHub? = null
        try {
            val pcKey = JvmDeviceIdentity(directory.resolve("pc-key")).material()
            val phoneKey = JvmDeviceIdentity(directory.resolve("phone-key")).material()
            val port = ServerSocket(0).use { it.localPort }
            val pc = Device(directory, "pc", HEARTED_AUDIO_CACHE_MAX_BYTES, scope)
            val phone = Device(directory, "phone", phoneMaxBytes, scope)
            pc.library.setSpotifyAccountId(pcAccount)
            phone.library.setSpotifyAccountId(phoneAccount)
            hub = DesktopHomeLanSyncHub(
                HomeLanHubConfig(
                    deviceId = "pc",
                    port = port,
                    peers = { listOf(PairedPeer("phone", "Phone", phoneKey.fingerprint, pairedAtMs = 1L)) },
                    pairing = PairingHost({ pcKey.fingerprint }, { "pc" }, onPaired = {}),
                    onHearts = { it },
                    onPlaylists = { it },
                    cachedAudio = if (hubServesCachedAudio) CachedAudioHost({ "pc" }, pc.library, pc.copies) else null,
                    vault = null,
                    heartedVaultFileNames = { null },
                ),
                pcKey,
                bindHost = "127.0.0.1",
            )
            hub.start()
            HttpHomeLanSyncClient(PairedPeer("pc", "PC", pcKey.fingerprint, "127.0.0.1", port, pairedAtMs = 1L),
                phone.library, KainosPlaylistRepository(), pinnedHomeLanHttpClient(pcKey.fingerprint, phoneKey.keyManagers()),
            ).use { client -> block(pc, phone, client) }
        } finally {
            hub?.stop()
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private object NoDownloader : YouTubeAudioDownloader {
        override fun isAvailable() = false
        override suspend fun downloadAudio(videoId: String, destinationDirectory: String, fileBaseName: String,
            onProgress: ((Long, Long?) -> Unit)?): YouTubeDownloadResult = YouTubeDownloadResult.Failed(YouTubeFailureKind.PERMANENT, "no downloader")
    }

    private fun bytes(count: Int, seed: Int) = ByteArray(count) { ((it * 31 + seed) % 251).toByte() }

    private fun ytTrack(id: String) = Track("yt:$id", "Song $id", listOf(ArtistRef("a", "Artist")),
        sources = listOf(PlaybackSource(ProviderId.YOUTUBE_MUSIC, id, isPlayable = true,
            handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, id))))

    private fun spotifyTrack(id: String) = Track("spotify:$id", "Song $id", listOf(ArtistRef("a", "Artist")),
        sources = listOf(PlaybackSource(ProviderId.SPOTIFY, id, isPlayable = true,
            handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, id, 180_000))))
}
