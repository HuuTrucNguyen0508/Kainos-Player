package com.universalmusic.player.data.session

import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CachedTrackProjectionTest {
    /** A hearted YouTube song queued from the audio cache, as the desktop stored it on 2026-11-07. */
    private val cachedYouTube = PersistedTrack(
        canonicalId = "yt:ZwZC4vlZTbI",
        title = "Dust",
        sources = listOf(
            PersistedSource(
                provider = "LOCAL",
                providerTrackId = "hearted-yt:ZwZC4vlZTbI",
                localLocation = "file:///home/u/.universal-music-player/audio-cache/audio/060225bc70523799bc8680c682310a62.webm",
            ),
        ),
        cachedAtMs = 1793926257292,
    )

    @Test
    fun aQueueWithACachedYouTubeSongStillMovesAsThatYouTubeSong() {
        val local = PersistedTrack(
            canonicalId = "localfile:song.flac",
            title = "Song",
            sources = listOf(PersistedSource("LOCAL", "song.flac")),
        )
        val projection = PortableListeningQueue.project(
            listOf(PersistedQueueItem("a", local), PersistedQueueItem("b", cachedYouTube)),
        )

        val queue = assertIs<QueueProjection.Complete>(projection).queue
        val moved = queue.entries.single { it.itemId == "b" }.track.track
        assertEquals("yt:ZwZC4vlZTbI", moved.canonicalId)
        assertEquals(listOf(PersistedSource("YOUTUBE_MUSIC", "ZwZC4vlZTbI")), moved.sources)
    }

    @Test
    fun aSpotifySongPlayingFromItsYouTubeMirrorMovesWithItsSpotifyId() {
        val mirrored = cachedYouTube.copy(canonicalId = "spotify:4qW3BbQAwZsrnu8a3ZRdyT")

        val moved = assertIs<QueueProjection.Complete>(PortableListeningQueue.project(listOf(PersistedQueueItem("a", mirrored))))
            .queue.entries.single().track.track

        assertEquals(listOf(PersistedSource("SPOTIFY", "4qW3BbQAwZsrnu8a3ZRdyT")), moved.sources)
    }
}
