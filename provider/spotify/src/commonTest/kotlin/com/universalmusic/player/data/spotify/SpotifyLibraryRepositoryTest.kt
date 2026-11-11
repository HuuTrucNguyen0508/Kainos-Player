package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.ProviderRateLimitedException
import com.universalmusic.player.domain.playback.rateLimitCause
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyLibraryRepositoryTest {
    private var now = 1_000_000L

    private fun song(id: String) = Track("spotify:track:$id", "Song $id", emptyList())

    private fun list(id: String, count: Int = 1) = Playlist(
        canonicalId = "spotify-playlist:$id",
        title = "List $id",
        trackCount = count,
        source = ProviderEntityRef(ProviderId.SPOTIFY, id),
    )

    /** Spotify as a fake: Liked Songs newest first, playlists with snapshots, and every call counted. */
    private class FakeSpotify(var pageSize: Int = 2) : SpotifyLibraryRemote {
        var liked = mutableListOf<LikedSongsEntry>()
        var heads = listOf<SpotifyPlaylistHead>()
        val songs = mutableMapOf<String, List<Track>>()
        val likedOffsets = mutableListOf<Int>()
        val songReads = mutableListOf<String>()
        var headReads = 0
        var failWith: Exception? = null

        override suspend fun likedSongsPage(offset: Int): LikedSongsPage {
            failWith?.let { throw it }
            likedOffsets += offset
            val page = liked.drop(offset).take(pageSize)
            return LikedSongsPage(page, liked.size, offset + page.size < liked.size)
        }

        override suspend fun libraryPlaylistHeads(): List<SpotifyPlaylistHead> {
            failWith?.let { throw it }
            headReads++
            return heads
        }

        override suspend fun playlistSongs(playlistId: String): List<Track> {
            failWith?.let { throw it }
            songReads += playlistId
            return songs.getValue(playlistId)
        }
    }

    private fun entry(id: String, addedAt: String = "t-$id") =
        LikedSongsEntry(id, addedAt, Track("spotify:track:$id", "Song $id", emptyList()))

    private fun repository(remote: FakeSpotify, store: MemorySpotifyLibraryStore) =
        SpotifyLibraryRepository(store, remote, clock = { now }, playlistPauseMs = 0)

    @Test
    fun aSavedLibraryShowsBeforeAnyNetworkCall() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify().apply {
            liked = mutableListOf(entry("b"), entry("a"))
            heads = listOf(SpotifyPlaylistHead(list("p"), "s1"))
            songs["p"] = listOf(song("x"))
        }
        repository(remote, store).apply { refresh("me"); syncPlaylistSongs() }
        val requests = remote.likedOffsets.size + remote.headReads + remote.songReads.size

        val relaunched = repository(remote, store)
        assertTrue(relaunched.hydrate("me"))

        assertEquals(listOf("Song b", "Song a"), relaunched.liked.value.map { it.title })
        assertEquals(listOf("List p"), relaunched.playlists.value.map { it.title })
        assertEquals(SavedPlaylistSongs(listOf(song("x")), current = true), relaunched.savedPlaylistSongs("p"))
        assertEquals(requests, remote.likedOffsets.size + remote.headReads + remote.songReads.size)
    }

    @Test
    fun likedPagingStopsAtTheNewestKnownSong() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify(pageSize = 2).apply { liked = (1..6).map { entry("old$it") }.toMutableList() }
        repository(remote, store).refresh("me")
        assertEquals(listOf(0, 2, 4), remote.likedOffsets)

        remote.likedOffsets.clear()
        remote.liked.addAll(0, listOf(entry("new1"), entry("new2"), entry("new3")))
        val relaunched = repository(remote, store).apply { hydrate("me") }
        relaunched.refresh("me")

        // Page 0 holds new1, new2; page 2 holds new3 then old1, the first known song.
        assertEquals(listOf(0, 2), remote.likedOffsets)
        assertEquals(listOf("new1", "new2", "new3") + (1..6).map { "old$it" }, relaunched.liked.value.map { it.title.removePrefix("Song ") })
        assertEquals(9, store.load("me")!!.liked.tracks.size)
    }

    @Test
    fun anUnchangedLikedListCostsOnePage() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify(pageSize = 2).apply { liked = (1..6).map { entry("s$it") }.toMutableList() }
        val repository = repository(remote, store)
        repository.refresh("me")
        remote.likedOffsets.clear()

        repository.refresh("me")

        assertEquals(listOf(0), remote.likedOffsets)
        assertEquals(6, repository.liked.value.size)
    }

    @Test
    fun anUnlikeBelowTheNewestSongsReadsTheWholeListAgain() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify(pageSize = 2).apply { liked = (1..6).map { entry("s$it") }.toMutableList() }
        val repository = repository(remote, store)
        repository.refresh("me")
        remote.likedOffsets.clear()

        remote.liked.removeAt(4)
        repository.refresh("me")

        assertEquals(listOf(0, 0, 2, 4), remote.likedOffsets)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s6"), repository.liked.value.map { it.title.removePrefix("Song ") })
    }

    @Test
    fun anUnlikeAtTheTopAndARelikeNeedNoFullRead() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify(pageSize = 2).apply { liked = (1..6).map { entry("s$it") }.toMutableList() }
        val repository = repository(remote, store)
        repository.refresh("me")
        remote.likedOffsets.clear()

        remote.liked.removeAt(0)
        remote.liked.removeAt(3)
        remote.liked.add(0, entry("s5", addedAt = "again"))
        repository.refresh("me")

        // s5 liked again sits above s2, the first known song; its old entry below is dropped.
        assertEquals(listOf(0), remote.likedOffsets)
        assertEquals(listOf("s5", "s2", "s3", "s4", "s6"), repository.liked.value.map { it.title.removePrefix("Song ") })
    }

    @Test
    fun unshowableSongsDoNotForceAFullReadEachTime() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify(pageSize = 3).apply {
            liked = mutableListOf(LikedSongsEntry(null, "gone", null), entry("a"), entry("b"))
        }
        val repository = repository(remote, store)
        repository.refresh("me")
        remote.likedOffsets.clear()

        repository.refresh("me")

        assertEquals(listOf(0), remote.likedOffsets)
        assertEquals(listOf("Song a", "Song b"), repository.liked.value.map { it.title })
    }

    @Test
    fun anUnlikeIsNotHiddenByANewUnshowableLike() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify(pageSize = 2).apply { liked = mutableListOf(entry("a"), entry("b"), entry("c")) }
        val repository = repository(remote, store)
        repository.refresh("me")

        // c is unliked, and a song Spotify cannot show is liked: the total stays 3.
        remote.liked.removeAt(2)
        remote.liked.add(0, LikedSongsEntry(null, "gone", null))
        repository.refresh("me")

        assertEquals(listOf("Song a", "Song b"), repository.liked.value.map { it.title })
        assertEquals(1, store.load("me")!!.liked.unmapped)
    }

    @Test
    fun unshowableSongsAboveTheNewestKnownOneAreCountedOnce() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify(pageSize = 2).apply {
            liked = mutableListOf(LikedSongsEntry(null, "gone", null), entry("a"), entry("b"))
        }
        val repository = repository(remote, store)
        repository.refresh("me")

        // A new like lands above the unshowable song; nothing else changed.
        remote.liked.add(0, entry("n"))
        remote.likedOffsets.clear()
        repository.refresh("me")
        assertEquals(listOf(0, 2), remote.likedOffsets, "no full read")
        assertEquals(listOf("Song n", "Song a", "Song b"), repository.liked.value.map { it.title })

        // The unshowable song, now below the newest known one, is unliked: only a full read can tell.
        remote.liked.removeAt(1)
        remote.likedOffsets.clear()
        repository.refresh("me")
        assertEquals(listOf(0, 0, 2), remote.likedOffsets)
        assertEquals(listOf("Song n", "Song a", "Song b"), repository.liked.value.map { it.title })
        assertEquals(0, store.load("me")!!.liked.unmapped)

        // A re-like of b moves it to the top; the unshowable count stays right.
        remote.liked.removeAt(2)
        remote.liked.add(0, entry("b", addedAt = "later"))
        remote.liked.add(1, LikedSongsEntry("x", "x-at", null))
        remote.likedOffsets.clear()
        repository.refresh("me")
        assertEquals(listOf(0, 2), remote.likedOffsets, "no full read")
        assertEquals(listOf("Song b", "Song n", "Song a"), repository.liked.value.map { it.title })
        assertEquals(1, store.load("me")!!.liked.unmapped)
    }

    @Test
    fun onlyPlaylistsWithANewSnapshotAreReadAgain() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify().apply {
            heads = listOf(SpotifyPlaylistHead(list("same"), "a1"), SpotifyPlaylistHead(list("changed"), "b1"))
            songs["same"] = listOf(song("1"))
            songs["changed"] = listOf(song("2"))
        }
        repository(remote, store).apply { refresh("me"); syncPlaylistSongs() }
        assertEquals(listOf("same", "changed"), remote.songReads)
        remote.songReads.clear()

        remote.heads = listOf(SpotifyPlaylistHead(list("same"), "a1"), SpotifyPlaylistHead(list("changed"), "b2"))
        remote.songs["changed"] = listOf(song("2"), song("3"))
        val relaunched = repository(remote, store).apply { hydrate("me") }
        relaunched.refresh("me")
        assertEquals(SavedPlaylistSongs(listOf(song("2")), current = false), relaunched.savedPlaylistSongs("changed"))
        relaunched.syncPlaylistSongs()

        assertEquals(listOf("changed"), remote.songReads)
        assertEquals(SavedPlaylistSongs(listOf(song("2"), song("3")), current = true), relaunched.savedPlaylistSongs("changed"))
        assertEquals(SavedPlaylistSongs(listOf(song("1")), current = true), relaunched.savedPlaylistSongs("same"))
    }

    @Test
    fun aPlaylistSpotifyAnswersEmptyIsNotAskedAgainThisRun() = runTest {
        val remote = FakeSpotify().apply {
            heads = listOf(SpotifyPlaylistHead(list("editorial", count = 30), "e"))
            songs["editorial"] = emptyList()
        }
        val repository = repository(remote, MemorySpotifyLibraryStore())
        repository.refresh("me")

        repository.syncPlaylistSongs()
        repository.syncPlaylistSongs()

        assertEquals(listOf("editorial"), remote.songReads)
        assertNull(repository.savedPlaylistSongs("editorial"))
    }

    @Test
    fun anOfflinePassCostsOneCallAndSetsNothingAside() = runTest {
        var offline = true
        val reads = mutableListOf<String>()
        val remote = object : SpotifyLibraryRemote {
            override suspend fun likedSongsPage(offset: Int) = LikedSongsPage(emptyList(), 0, false)
            override suspend fun libraryPlaylistHeads() = listOf("a", "b").map { SpotifyPlaylistHead(list(it), "s$it") }
            override suspend fun playlistSongs(playlistId: String): List<Track> {
                reads += playlistId
                if (offline) throw IllegalStateException("Unable to resolve host api.spotify.com")
                return listOf(song(playlistId))
            }
        }
        val repository = SpotifyLibraryRepository(MemorySpotifyLibraryStore(), remote, clock = { now }, playlistPauseMs = 0)
        repository.refresh("me")

        repository.syncPlaylistSongs()
        assertEquals(listOf("a"), reads)

        offline = false
        repository.syncPlaylistSongs()
        assertEquals(listOf("a", "a", "b"), reads)
        assertEquals(SavedPlaylistSongs(listOf(song("a")), current = true), repository.savedPlaylistSongs("a"))
    }

    @Test
    fun aRateLimitKeepsTheSavedLibraryAndWaitsOutRetryAfter() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify().apply {
            liked = mutableListOf(entry("a"))
            heads = listOf(SpotifyPlaylistHead(list("p"), "s"))
            songs["p"] = listOf(song("x"))
        }
        repository(remote, store).apply { refresh("me"); syncPlaylistSongs() }
        val relaunched = repository(remote, store).apply { hydrate("me") }
        remote.failWith = ProviderRateLimitedException("Spotify saved tracks was rate limited", retryAfterMs = 30_000)
        remote.likedOffsets.clear()

        val limited = relaunched.refresh("me")

        assertNotNull(limited.liked.exceptionOrNull()?.rateLimitCause())
        assertNotNull(limited.playlists.exceptionOrNull()?.rateLimitCause())
        assertEquals(0, remote.headReads - 1, "playlists are not asked for after the 429")
        assertEquals(listOf("Song a"), relaunched.liked.value.map { it.title })
        assertEquals(listOf("List p"), relaunched.playlists.value.map { it.title })
        assertEquals(now + 30_000, relaunched.rateLimitedUntilMs)

        remote.failWith = null
        now += 10_000
        assertFalse(relaunched.refresh("me").succeeded)
        assertEquals(emptyList(), remote.likedOffsets, "no call before Retry-After passed")

        now += 20_000
        assertTrue(relaunched.refresh("me").succeeded)
        assertEquals(listOf(0), remote.likedOffsets)
    }

    @Test
    fun aRateLimitStopsPlaylistSongReads() = runTest {
        val remote = object : SpotifyLibraryRemote {
            val reads = mutableListOf<String>()
            override suspend fun likedSongsPage(offset: Int) = LikedSongsPage(emptyList(), 0, false)
            override suspend fun libraryPlaylistHeads() =
                listOf("a", "b", "c").map { SpotifyPlaylistHead(list(it), "s$it") }
            override suspend fun playlistSongs(playlistId: String): List<Track> {
                reads += playlistId
                if (playlistId == "b") throw IllegalStateException("wrapped", ProviderRateLimitedException("429", 5_000))
                return listOf(song(playlistId))
            }
        }
        val repository = SpotifyLibraryRepository(MemorySpotifyLibraryStore(), remote, clock = { now }, playlistPauseMs = 0)
        repository.refresh("me")

        repository.syncPlaylistSongs()

        assertEquals(listOf("a", "b"), remote.reads)
        assertEquals(now + 5_000, repository.rateLimitedUntilMs)
    }

    @Test
    fun anotherAccountClearsTheSavedLibrary() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify().apply {
            liked = mutableListOf(entry("mine"))
            heads = listOf(SpotifyPlaylistHead(list("p"), "s"))
            songs["p"] = listOf(song("x"))
        }
        val repository = repository(remote, store).apply { refresh("me"); syncPlaylistSongs() }

        remote.liked = mutableListOf(entry("theirs"))
        remote.heads = emptyList()
        repository.refresh("someone-else")

        assertEquals(listOf("Song theirs"), repository.liked.value.map { it.title })
        assertEquals(emptyList(), repository.playlists.value)
        assertNull(store.load("me"))
        assertEquals("someone-else", store.storedAccount())
        assertFalse(repository(remote, store).hydrate("me"))
    }

    @Test
    fun aRestartSignedInAsAnotherAccountShowsNothingThenClears() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify().apply { liked = mutableListOf(entry("mine")) }
        repository(remote, store).refresh("me")

        val relaunched = repository(remote, store)
        assertFalse(relaunched.hydrate("other"))
        assertEquals(emptyList(), relaunched.liked.value)
        remote.liked = mutableListOf(entry("theirs"))
        relaunched.refresh("other")

        assertNull(store.load("me"))
        assertEquals(listOf("Song theirs"), relaunched.liked.value.map { it.title })
    }

    @Test
    fun clearForgetsTheLibraryAndARefreshInFlightDoesNotBringItBack() = runTest {
        val store = MemorySpotifyLibraryStore()
        lateinit var repository: SpotifyLibraryRepository
        val remote = object : SpotifyLibraryRemote {
            override suspend fun likedSongsPage(offset: Int): LikedSongsPage {
                repository.clear()
                return LikedSongsPage(listOf(entry("late")), 1, false)
            }
            override suspend fun libraryPlaylistHeads() = emptyList<SpotifyPlaylistHead>()
            override suspend fun playlistSongs(playlistId: String) = emptyList<Track>()
        }
        repository = SpotifyLibraryRepository(store, remote, clock = { now }, playlistPauseMs = 0)

        repository.refresh("me")

        assertEquals(emptyList(), repository.liked.value)
        assertNull(store.load("me")?.liked?.tracks?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun withoutAKnownAccountASavedLibraryIsLeftAlone() = runTest {
        val store = MemorySpotifyLibraryStore()
        val remote = FakeSpotify().apply { liked = mutableListOf(entry("a")) }
        val repository = repository(remote, store).apply { refresh("me") }
        remote.likedOffsets.clear()

        assertFalse(repository.refresh(null).succeeded)
        assertEquals(emptyList(), remote.likedOffsets)
        assertEquals(listOf("Song a"), repository.liked.value.map { it.title })
    }

    @Test
    fun savedAgoReadsInMinutesHoursAndDays() {
        assertEquals("saved just now", spotifyLibrarySavedAgo(30_000))
        assertEquals("saved 12 min ago", spotifyLibrarySavedAgo(12 * 60_000L))
        assertEquals("saved 3 h ago", spotifyLibrarySavedAgo(3 * 3_600_000L))
        assertEquals("saved yesterday", spotifyLibrarySavedAgo(30 * 3_600_000L))
        assertEquals("saved 4 days ago", spotifyLibrarySavedAgo(4 * 86_400_000L))
    }
}

/** The store as a map, for tests that do not need SQLite. */
class MemorySpotifyLibraryStore : SpotifyLibraryStore {
    private var account: String? = null
    private var liked: SavedLikedSongs? = null
    private var playlists: List<SavedSpotifyPlaylist> = emptyList()
    private var playlistsAt: Long? = null
    private val songs = mutableMapOf<String, List<Track>>()

    override suspend fun storedAccount() = account

    override suspend fun load(accountId: String): SavedSpotifyLibrary? {
        if (account != accountId) return null
        return SavedSpotifyLibrary(accountId, liked ?: SavedLikedSongs(emptyList(), null, 0, null), playlists, playlistsAt)
    }

    override suspend fun saveLiked(accountId: String, liked: SavedLikedSongs) {
        keepOnly(accountId)
        this.liked = liked
    }

    override suspend fun savePlaylists(accountId: String, playlists: List<SavedSpotifyPlaylist>, checkedAtMs: Long) {
        keepOnly(accountId)
        this.playlists = playlists
        playlistsAt = checkedAtMs
        songs.keys.retainAll(playlists.map { it.id }.toSet())
    }

    override suspend fun playlistTracks(accountId: String, playlistId: String): List<Track>? =
        if (account == accountId && playlists.any { it.id == playlistId && it.tracksSnapshotId != null }) songs[playlistId] else null

    override suspend fun savePlaylistTracks(accountId: String, playlistId: String, snapshotId: String?, tracks: List<Track>, savedAtMs: Long) {
        if (account != accountId || playlists.none { it.id == playlistId }) return
        songs[playlistId] = tracks
        playlists = playlists.map { if (it.id == playlistId) it.copy(tracksSnapshotId = snapshotId.orEmpty()) else it }
    }

    override suspend fun clear() {
        account = null
        liked = null
        playlists = emptyList()
        playlistsAt = null
        songs.clear()
    }

    private suspend fun keepOnly(accountId: String) {
        if (account != accountId) clear()
        account = accountId
    }
}
