package com.universalmusic.player.data.playlist

import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KainosPlaylistPersistenceTest {
    @Test
    fun keyedPlaylistExportHasV1DowngradeWithoutLocalUris() {
        val key = "lc1:" + "a".repeat(64)
        val portable = localTrack().copy(localContentKey = key).toPlaylistPersisted(0).forPlaylistSync()!!
        assertEquals("localkey:$key", portable.canonicalId)
        assertEquals("song.flac", portable.sources.single().providerTrackId)
        assertNull(portable.sources.single().localLocation)
        val doc = PlaylistsSyncDocument("pc", listOf(PersistedKainosPlaylist("kainos:playlist:key", "Key",
            entries = listOf(PersistedPlaylistEntry("entry", portable)), revision = 40)))
        val v1 = doc.forV1Sync().playlists.single().entries.single().track
        assertEquals("localfile:song.flac", v1.canonicalId)
        assertNull(v1.localContentKey)
        assertNull(v1.sources.single().localLocation)
        assertEquals(40L, doc.forV1Sync().playlists.single().revision)
    }

    @Test
    fun keyedPlaylistRematchesRenamedFileAndKeepsRevisionAndEntryIdentity() = runTest {
        val key = "lc1:" + "a".repeat(64)
        val old = localTrack().copy(localContentKey = key)
        val renamed = old.copy(canonicalId = "local:renamed", sources = listOf(PlaybackSource(
            ProviderId.LOCAL, "renamed", streamUrl = "file:///new.flac", handle = PlaybackHandle.Url("file:///new.flac"), isPlayable = true,
        )))
        val playlist = PersistedKainosPlaylist("kainos:playlist:key", "Key", entries = listOf(
            PersistedPlaylistEntry("entry", old.toPlaylistPersisted(0).forPlaylistSync()!!)), revision = 40, deviceId = "phone")
        val repo = KainosPlaylistRepository(clock = { 100 })
        repo.applySnapshot(KainosPlaylistsSnapshot(playlists = listOf(playlist)))
        assertEquals(1, repo.rematchPortableLocalFileEntries(emptyMap(), listOf(renamed)))
        val result = repo.get(playlist.id)!!
        assertEquals("local:renamed", result.entries.single().track.canonicalId)
        assertEquals("entry", result.entries.single().entryId)
        assertEquals(40L, result.revision)
        assertEquals("phone", result.deviceId)
    }

    @Test
    fun keyedPlaylistRejectsSameNameDifferentKeyAndBackfillsExistingLocalEntries() = runTest {
        val a = "lc1:" + "a".repeat(64)
        val b = "lc1:" + "b".repeat(64)
        val track = localTrack()
        val playlist = PersistedKainosPlaylist("kainos:playlist:key", "Key", entries = listOf(
            PersistedPlaylistEntry("entry", track.copy(localContentKey = a).toPlaylistPersisted(0).forPlaylistSync()!!)), revision = 40)
        val repo = KainosPlaylistRepository()
        repo.applySnapshot(KainosPlaylistsSnapshot(playlists = listOf(playlist)))
        assertEquals(0, repo.rematchPortableLocalFileEntries(emptyMap(), listOf(track.copy(localContentKey = b))))
        assertEquals("localkey:$a", repo.get(playlist.id)!!.entries.single().track.canonicalId)
        repo.applySnapshot(KainosPlaylistsSnapshot(playlists = listOf(playlist.copy(entries = listOf(
            PersistedPlaylistEntry("entry", track.toPlaylistPersisted(0)))))))
        repo.rematchPortableLocalFileEntries(emptyMap(), listOf(track.copy(localContentKey = a)))
        assertEquals("localkey:$a", repo.exportSyncDocument().playlists.single().entries.single().track.canonicalId)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun roundTripsPlaylistWithoutStreamUrls() = runTest {
        val store = InMemoryKainosPlaylistStore()
        val repo = KainosPlaylistRepository(scope = this, store = store, clock = { 1_000L }, deviceIdProvider = { "pc" })
        repo.load()

        val yt = youtubeTrack(streamUrl = "https://googlevideo.example/expire=1")
        val local = localTrack()
        val created = repo.create("Mixed", listOf(yt, local))
        advanceUntilIdle()

        val onDisk = store.read()
        assertEquals(1, onDisk.playlists.size)
        assertEquals("Mixed", onDisk.playlists.single().title)
        assertEquals(2, onDisk.playlists.single().entries.size)
        val ytEntry = onDisk.playlists.single().entries.first { it.track.canonicalId == "yt:abc" }
        assertNull(ytEntry.track.sources.single().localLocation)
        assertNull(ytEntry.track.toDomain().sources.single().streamUrl)
        assertTrue(ytEntry.track.toDomain().sources.single().handle is PlaybackHandle.ProviderPlayback)

        val reloaded = KainosPlaylistRepository(scope = this, store = store, clock = { 2_000L })
        reloaded.load()
        assertEquals(created.id, reloaded.playlists.value.single().id)
        assertEquals(listOf("yt:abc", "local:1"), reloaded.tracksForPlayback(created.id).map { it.canonicalId })
    }

    @Test
    fun saveQueuePreservesOrderAndProviderIds() = runTest {
        val store = InMemoryKainosPlaylistStore()
        val repo = KainosPlaylistRepository(scope = this, store = store, clock = { 5_000L }, deviceIdProvider = { "phone" })
        repo.load()

        val queue = listOf(
            spotifyTrack("spotify:z"),
            youtubeTrack(id = "yt:mid"),
            localTrack(id = "local:end"),
        )
        val saved = repo.saveQueueAsPlaylist("From queue", queue)
        advanceUntilIdle()

        assertEquals(listOf("spotify:z", "yt:mid", "local:end"), saved.entries.map { it.track.canonicalId })
        val disk = store.read().playlists.single()
        assertEquals(listOf("spotify:z", "yt:mid", "local:end"), disk.entries.map { it.track.canonicalId })
        assertEquals(
            listOf(ProviderId.SPOTIFY.name, ProviderId.YOUTUBE_MUSIC.name, ProviderId.LOCAL.name),
            disk.entries.map { it.track.sources.single().provider },
        )
        // Local keeps stable location; streaming never stores URLs.
        assertEquals("file:///music/song.flac", disk.entries[2].track.sources.single().localLocation)
        assertNull(disk.entries[0].track.sources.single().localLocation)
        assertNull(disk.entries[1].track.sources.single().localLocation)
    }

    @Test
    fun jsonRoundTripMigratesCorruptSnapshotSafely() {
        val raw = """
            {"version":0,"playlists":[{"id":"bad","title":"","entries":[{"entryId":"","track":{"canonicalId":"","title":"x"}}]}],
            "tombstones":[{"playlistId":"kainos:playlist:gone","revision":9,"deviceId":"pc","deletedAtMs":1}]}
        """.trimIndent()
        val decoded = json.decodeFromString<KainosPlaylistsSnapshot>(raw).migrated()
        assertTrue(decoded.playlists.isEmpty())
        assertEquals(1, decoded.tombstones.size)
        assertEquals(KAINOS_PLAYLISTS_FORMAT_VERSION, decoded.version)
    }

    @Test
    fun deleteEmitsTombstoneAndMergeHonorsIt() = runTest {
        val store = InMemoryKainosPlaylistStore()
        val repo = KainosPlaylistRepository(scope = this, store = store, clock = { 10L }, deviceIdProvider = { "phone" })
        repo.load()
        val created = repo.create("Temp", listOf(youtubeTrack()))
        advanceUntilIdle()
        assertTrue(repo.delete(created.id))
        advanceUntilIdle()

        val remote = PlaylistsSyncDocument(
            deviceId = "pc",
            playlists = listOf(
                PersistedKainosPlaylist(
                    id = created.id,
                    title = "Temp revived",
                    entries = emptyList(),
                    revision = 5L,
                    deviceId = "pc",
                ),
            ),
        )
        val merged = mergePlaylistSync(store.read(), remote)
        assertTrue(merged.playlists.none { it.id == created.id })
        assertTrue(merged.tombstones.any { it.playlistId == created.id })
    }

    @Test
    fun mergePrefersHigherRevisionPlaylistBody() {
        val id = "kainos:playlist:shared"
        val local = KainosPlaylistsSnapshot(
            playlists = listOf(
                PersistedKainosPlaylist(
                    id = id,
                    title = "Old",
                    entries = listOf(
                        PersistedPlaylistEntry("e1", youtubePersisted("yt:old")),
                    ),
                    revision = 10,
                    deviceId = "phone",
                ),
            ),
        )
        val remote = PlaylistsSyncDocument(
            deviceId = "pc",
            playlists = listOf(
                PersistedKainosPlaylist(
                    id = id,
                    title = "New",
                    entries = listOf(
                        PersistedPlaylistEntry("e2", youtubePersisted("yt:new")),
                    ),
                    revision = 20,
                    deviceId = "pc",
                ).forPlaylistSync(),
            ),
        )
        val merged = mergePlaylistSync(local, remote)
        assertEquals("New", merged.playlists.single().title)
        assertEquals(listOf("yt:new"), merged.playlists.single().entries.map { it.track.canonicalId })
    }

    @Test
    fun portableLocalfileExportUsesBasename() {
        val track = localTrack().toPlaylistPersisted(0)
        val portable = track.forPlaylistSync()
        assertNotNull(portable)
        assertEquals("localfile:song.flac", portable.canonicalId)
        assertNull(portable.sources.single().localLocation)
    }

    @Test
    fun encodeDecodeSnapshotPreservesEntries() {
        val snapshot = KainosPlaylistsSnapshot(
            playlists = listOf(
                PersistedKainosPlaylist(
                    id = "kainos:playlist:abc",
                    title = "Keep",
                    entries = listOf(
                        PersistedPlaylistEntry("e1", youtubePersisted("yt:1")),
                        PersistedPlaylistEntry("e2", localPersisted()),
                    ),
                    revision = 3,
                    deviceId = "pc",
                ),
            ),
        )
        val encoded = json.encodeToString(snapshot)
        assertFalse("googlevideo" in encoded)
        val decoded = json.decodeFromString<KainosPlaylistsSnapshot>(encoded).migrated()
        assertEquals(listOf("yt:1", "local:1"), decoded.playlists.single().entries.map { it.track.canonicalId })
    }
}

private class InMemoryKainosPlaylistStore : KainosPlaylistStore {
    private var snapshot = KainosPlaylistsSnapshot()
    override suspend fun read(): KainosPlaylistsSnapshot = snapshot
    override suspend fun write(snapshot: KainosPlaylistsSnapshot) {
        this.snapshot = snapshot
    }
}

private fun youtubeTrack(streamUrl: String? = null, id: String = "yt:abc") = Track(
    canonicalId = id,
    title = "Cached YT",
    artists = listOf(ArtistRef("yt-artist:1", "YT Artist")),
    durationMs = 180_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.YOUTUBE_MUSIC,
            providerTrackId = id.removePrefix("yt:"),
            streamUrl = streamUrl,
            quality = AudioQuality(QualityTier.STANDARD, codec = "opus"),
            isPlayable = true,
            handle = if (streamUrl != null) {
                PlaybackHandle.Url(streamUrl)
            } else {
                PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, id.removePrefix("yt:"), 180_000)
            },
        ),
    ),
)

private fun spotifyTrack(id: String) = Track(
    canonicalId = id,
    title = "Spotify Song",
    artists = listOf(ArtistRef("sp-artist:1", "SP Artist")),
    durationMs = 200_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.SPOTIFY,
            providerTrackId = id.removePrefix("spotify:"),
            streamUrl = "https://should-not-persist.example/stream",
            isPlayable = true,
            handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, id.removePrefix("spotify:"), 200_000),
        ),
    ),
)

private fun localTrack(id: String = "local:1") = Track(
    canonicalId = id,
    title = "Local Song",
    artists = listOf(ArtistRef("local-artist:1", "Local Artist")),
    durationMs = 200_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.LOCAL,
            providerTrackId = id.removePrefix("local:"),
            streamUrl = "file:///music/song.flac",
            isPlayable = true,
            handle = PlaybackHandle.Url("file:///music/song.flac"),
        ),
    ),
)

private fun youtubePersisted(id: String) = PersistedTrack(
    canonicalId = id,
    title = "YT",
    artists = listOf(PersistedArtist("a", "A")),
    sources = listOf(PersistedSource(ProviderId.YOUTUBE_MUSIC.name, id.removePrefix("yt:"))),
)

private fun localPersisted() = PersistedTrack(
    canonicalId = "local:1",
    title = "Local",
    artists = listOf(PersistedArtist("a", "A")),
    sources = listOf(
        PersistedSource(ProviderId.LOCAL.name, "1", localLocation = "file:///music/song.flac"),
    ),
)
