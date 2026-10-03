package com.universalmusic.player.data.session

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSessionSnapshotStoreTest {
    @Test
    fun atomicWriteAndCorruptReadRecover() = runTest {
        val dir = Files.createTempDirectory("kainos-session-test")
        val path = dir.resolve("playback-session.json")
        val store = FileSessionSnapshotStore(path)

        val snapshot = SessionSnapshot(
            items = listOf(
                PersistedQueueItem(
                    id = "q1",
                    track = com.universalmusic.player.data.library.PersistedTrack(
                        canonicalId = "yt:1",
                        title = "Song",
                        sources = listOf(
                            com.universalmusic.player.data.library.PersistedSource(
                                provider = "YOUTUBE_MUSIC",
                                providerTrackId = "1",
                            ),
                        ),
                    ),
                ),
            ),
            currentIndex = 0,
            positionMs = 1_000L,
        )
        store.write(snapshot)
        assertTrue(Files.exists(path))
        assertEquals("q1", store.read().items.single().id)

        Files.writeString(path, "{{{broken")
        val recovered = store.read()
        assertEquals(emptyList(), recovered.items)
    }
}
