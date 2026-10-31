package com.universalmusic.player.data.library

import com.universalmusic.player.data.playlist.*
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuditStorageTest {
    @Test fun failedLibraryReadDoesNotEnableWrites() = runTest {
        var writes = 0
        val store = object : UserLibraryStore {
            override suspend fun read(): UserLibrarySnapshot = error("temporary read failure")
            override suspend fun write(snapshot: UserLibrarySnapshot) { writes++ }
        }
        val repo = LibraryRepository(this, store)
        runCatching { repo.load(null) }
        repo.toggleFavorite(Track(canonicalId = "yt:a", title = "A", artists = emptyList()))
        advanceUntilIdle()
        assertEquals(0, writes)
    }
    @Test fun failedPlaylistReadDoesNotEnableWrites() = runTest {
        var writes = 0
        val store = object : KainosPlaylistStore {
            override suspend fun read(): KainosPlaylistsSnapshot = error("temporary read failure")
            override suspend fun write(snapshot: KainosPlaylistsSnapshot) { writes++ }
        }
        val repo = KainosPlaylistRepository(this, store)
        runCatching { repo.load() }
        repo.create("Keep disk safe")
        advanceUntilIdle()
        assertEquals(0, writes)
    }
    @Test fun clockRollbackKeepsPlaylistEditAndOrdering() = runTest {
        var clock = 1000L
        val repo = KainosPlaylistRepository(clock = { clock })
        val a = repo.create("Before")
        clock = 1L
        repo.rename(a.id, "After")
        repo.mergeAndPersistSyncState(PlaylistsSyncDocument("b", listOf(a)))
        assertEquals("After", repo.get(a.id)?.title)
        assertTrue(repo.get(a.id)!!.revision > a.revision)
        val revision = repo.get(a.id)!!.revision
        repo.reorderPlaylists(listOf(a.id))
        assertTrue(repo.get(a.id)!!.revision > revision)
    }
    @Test fun clockRollbackKeepsUnheart() = runTest {
        var clock = 1000L
        val repo = LibraryRepository(clock = { clock })
        val track = Track(canonicalId = "yt:a", title = "A", artists = emptyList())
        repo.toggleFavorite(track)
        clock = 1L
        repo.toggleFavorite(track)
        assertEquals(com.universalmusic.player.data.sync.HeartAction.UNFAVORITE, repo.toSnapshot().heartOps.single().action)
        assertTrue(repo.toSnapshot().heartOps.single().revision > 1000L)
    }
}
