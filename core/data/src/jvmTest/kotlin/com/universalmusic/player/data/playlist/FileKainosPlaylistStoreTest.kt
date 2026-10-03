package com.universalmusic.player.data.playlist

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileKainosPlaylistStoreTest {
    @Test
    fun roundTripsThroughDisk() = runTest {
        val dir = Files.createTempDirectory("kainos-playlists-test")
        val path = dir.resolve("kainos-playlists.json")
        val store = FileKainosPlaylistStore(path)
        val snapshot = KainosPlaylistsSnapshot(
            playlists = listOf(
                PersistedKainosPlaylist(
                    id = "kainos:playlist:disk",
                    title = "Disk",
                    entries = listOf(
                        PersistedPlaylistEntry(
                            entryId = "e1",
                            track = com.universalmusic.player.data.library.PersistedTrack(
                                canonicalId = "yt:1",
                                title = "One",
                                artists = listOf(
                                    com.universalmusic.player.data.library.PersistedArtist("a", "A"),
                                ),
                                sources = listOf(
                                    com.universalmusic.player.data.library.PersistedSource(
                                        provider = "YOUTUBE_MUSIC",
                                        providerTrackId = "1",
                                    ),
                                ),
                            ),
                        ),
                    ),
                    revision = 1,
                    deviceId = "pc",
                ),
            ),
        )
        store.write(snapshot)
        val read = store.read()
        assertEquals("Disk", read.playlists.single().title)
        assertEquals("yt:1", read.playlists.single().entries.single().track.canonicalId)
        assertTrue(Files.exists(path))
    }
}
