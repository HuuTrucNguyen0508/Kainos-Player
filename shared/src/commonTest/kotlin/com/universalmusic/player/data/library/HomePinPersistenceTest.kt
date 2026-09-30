package com.universalmusic.player.data.library

import com.universalmusic.player.data.playlist.KAINOS_PLAYLIST_ID_PREFIX
import com.universalmusic.player.data.playlist.newKainosPlaylistId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomePinPersistenceTest {
    @Test
    fun pinOrderSurvivesRestartAndUnpinDoesNotDeleteTarget() = runTest {
        val store = PinTestLibraryStore()
        val library = LibraryRepository(scope = this, store = store, clock = { 1_000L })

        val playlistId = newKainosPlaylistId()
        assertTrue(playlistId.startsWith(KAINOS_PLAYLIST_ID_PREFIX))

        val a = library.pinHome(
            kind = HomePinKind.KAINOS_PLAYLIST,
            targetId = playlistId,
            title = "Morning",
        )
        val b = library.pinHome(
            kind = HomePinKind.ALBUM,
            targetId = "local-album:artist|title|abc",
            title = "Album X",
        )
        val c = library.pinHome(
            kind = HomePinKind.LOCAL_FOLDER,
            targetId = "/home/music/Playlist",
            title = "Playlist",
        )
        assertNotNull(a)
        assertNotNull(b)
        assertNotNull(c)
        advanceUntilIdle()

        library.moveHomePin(c!!.id, -2)
        advanceUntilIdle()
        assertEquals(
            listOf(c.id, a!!.id, b!!.id),
            library.homePins.value.map { it.id },
        )

        assertTrue(library.unpinHome(a.id))
        advanceUntilIdle()
        assertFalse(library.isPinned(HomePinKind.KAINOS_PLAYLIST, playlistId))
        // Unpin must not invent playlist deletion; target id remains a valid kainos id shape.
        assertTrue(playlistId.startsWith(KAINOS_PLAYLIST_ID_PREFIX))

        val reloaded = LibraryRepository(scope = this, store = store, clock = { 2_000L })
        reloaded.load(activeSpotifyAccountId = null)
        assertEquals(
            listOf(HomePinKind.LOCAL_FOLDER, HomePinKind.ALBUM),
            reloaded.homePins.value.map { it.kind },
        )
        assertEquals("/home/music/Playlist", reloaded.homePins.value.first().targetId)
        assertEquals(3, store.read().version)
    }

    @Test
    fun spotifyAccountSwitchDropsProviderPlaylistPins() = runTest {
        val store = PinTestLibraryStore()
        store.write(
            UserLibrarySnapshot(
                version = 3,
                spotifyAccountId = "user-a",
                homePins = listOf(
                    PersistedHomePin(
                        id = "pin:1",
                        kind = HomePinKind.PROVIDER_PLAYLIST,
                        targetId = "spotify-playlist:dw",
                        title = "Discover Weekly",
                        providerEntityId = "dw",
                        provider = "SPOTIFY",
                    ),
                    PersistedHomePin(
                        id = "pin:2",
                        kind = HomePinKind.KAINOS_PLAYLIST,
                        targetId = newKainosPlaylistId(),
                        title = "Keep",
                    ),
                ),
            ),
        )
        val library = LibraryRepository(scope = this, store = store)
        library.load(activeSpotifyAccountId = "user-b")
        assertEquals(1, library.homePins.value.size)
        assertEquals(HomePinKind.KAINOS_PLAYLIST, library.homePins.value.single().kind)
    }

    @Test
    fun homeSyncEligibilityAndMergeKeepsDeviceLocalPins() {
        assertTrue(HomePinKind.KAINOS_PLAYLIST.canHomeSync)
        assertTrue(HomePinKind.PROVIDER_PLAYLIST.canHomeSync)
        assertFalse(HomePinKind.ALBUM.canHomeSync)
        assertFalse(HomePinKind.LOCAL_FOLDER.canHomeSync)

        val local = listOf(
            PersistedHomePin(
                id = "pin:local-folder",
                kind = HomePinKind.LOCAL_FOLDER,
                targetId = "/music/A",
                title = "A",
            ),
            PersistedHomePin(
                id = "pin:k1",
                kind = HomePinKind.KAINOS_PLAYLIST,
                targetId = "kainos:playlist:aaa",
                title = "Local list",
            ),
        )
        val remote = listOf(
            PersistedHomePin(
                id = "pin:k2",
                kind = HomePinKind.KAINOS_PLAYLIST,
                targetId = "kainos:playlist:bbb",
                title = "Remote list",
            ),
            PersistedHomePin(
                id = "pin:album",
                kind = HomePinKind.ALBUM,
                targetId = "local-album:x",
                title = "Should drop",
            ),
        )
        val merged = mergeHomePins(local, remote)
        assertEquals(
            listOf(
                HomePinKind.KAINOS_PLAYLIST,
                HomePinKind.KAINOS_PLAYLIST,
                HomePinKind.LOCAL_FOLDER,
            ),
            merged.map { it.kind },
        )
        assertNull(remote.single { it.kind == HomePinKind.ALBUM }.forHomeSync())
        assertEquals("Playlist", folderPinDisplayName("/home/music/Playlist"))
    }

    @Test
    fun duplicatePinUpdatesMetadataWithoutDuplicating() = runTest {
        val library = LibraryRepository(scope = this, store = PinTestLibraryStore(), clock = { 1L })
        val id = "kainos:playlist:dup"
        library.pinHome(HomePinKind.KAINOS_PLAYLIST, id, title = "Old")
        library.pinHome(HomePinKind.KAINOS_PLAYLIST, id, title = "New")
        assertEquals(1, library.homePins.value.size)
        assertEquals("New", library.homePins.value.single().title)
    }
}

private class PinTestLibraryStore : UserLibraryStore {
    private var snapshot = UserLibrarySnapshot()
    override suspend fun read(): UserLibrarySnapshot = snapshot
    override suspend fun write(snapshot: UserLibrarySnapshot) {
        this.snapshot = snapshot
    }
}
