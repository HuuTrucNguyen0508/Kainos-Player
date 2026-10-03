package com.universalmusic.player.data.cache

import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackAvailabilityTest {
    @Test
    fun pendingKeyedLocalTrackReportsMissingFileInLibraryAndPlaylist() {
        val track = Track("localkey:lc1:" + "a".repeat(64), "Song", emptyList())
        assertEquals("Local file missing", resolveTrackAvailability(track).label)
        assertEquals(TrackAvailability.UNAVAILABLE, resolveTrackAvailability(track).status)
        assertEquals(com.universalmusic.player.data.playlist.PlaylistEntryAvailability.MISSING_LOCAL,
            com.universalmusic.player.data.playlist.playlistEntryAvailability(track))
    }

    @Test
    fun localFileIsAvailableLocallySeparateFromFavorite() {
        val track = Track(
            canonicalId = "local:1",
            title = "Song",
            artists = listOf(ArtistRef("a", "Artist")),
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.LOCAL,
                    providerTrackId = "file",
                    isPlayable = true,
                    handle = PlaybackHandle.Url("file:///music/song.flac"),
                ),
            ),
        )
        val info = resolveTrackAvailability(track, networkAvailable = false)
        assertEquals(TrackAvailability.AVAILABLE_LOCALLY, info.status)
        assertEquals(PlaybackSourceKind.LOCAL_FILE, info.playbackSource)
    }

    @Test
    fun heartedYoutubeCacheIsCachedNotSpotifyDrm() {
        val entry = HeartedAudioCacheEntry(
            ownerCanonicalId = "yt:abc",
            youtubeVideoId = "abc",
            localUri = "file:///cache/abc.m4a",
            sizeBytes = 10,
            downloadedAtMs = 1,
        )
        val track = Track(
            canonicalId = "yt:abc",
            title = "Song",
            artists = listOf(ArtistRef("a", "Artist")),
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.YOUTUBE_MUSIC,
                    providerTrackId = "abc",
                    isPlayable = true,
                    handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, "abc"),
                ),
            ),
        ).withHeartedAudioCache(entry)
        val info = resolveTrackAvailability(track)
        assertEquals(TrackAvailability.CACHED, info.status)
        assertEquals(PlaybackSourceKind.HEARTED_YOUTUBE_CACHE, info.playbackSource)
        assertFalse(info.isSpotifyViaYouTubeMatch)
    }

    @Test
    fun spotifyViaYoutubeMatchIsLabeledHonestly() {
        val entry = HeartedAudioCacheEntry(
            ownerCanonicalId = "spotify:1",
            youtubeVideoId = "mirror",
            localUri = "file:///cache/mirror.m4a",
            sizeBytes = 10,
            downloadedAtMs = 1,
            spotifyViaYouTubeMatch = true,
        )
        val track = Track(
            canonicalId = "spotify:1",
            title = "Song",
            artists = listOf(ArtistRef("a", "Artist")),
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.SPOTIFY,
                    providerTrackId = "1",
                    isPlayable = true,
                    handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, "1"),
                ),
            ),
        ).withHeartedAudioCache(entry)
        val info = resolveTrackAvailability(track)
        assertEquals(TrackAvailability.CACHED, info.status)
        assertTrue(info.isSpotifyViaYouTubeMatch)
        assertEquals(PlaybackSourceKind.SPOTIFY_VIA_YOUTUBE_CACHE, info.playbackSource)
        assertTrue(info.label.contains("YouTube match"))
    }

    @Test
    fun spotifyStreamWithoutCacheNeedsNetworkWhenOffline() {
        val track = Track(
            canonicalId = "spotify:1",
            title = "Song",
            artists = listOf(ArtistRef("a", "Artist")),
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.SPOTIFY,
                    providerTrackId = "1",
                    isPlayable = true,
                    handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, "1"),
                ),
            ),
        )
        val info = resolveTrackAvailability(track, networkAvailable = false)
        assertEquals(TrackAvailability.WAITING_FOR_NETWORK, info.status)
        assertEquals(PlaybackSourceKind.SPOTIFY_STREAM, info.playbackSource)
    }

    @Test
    fun downloadingAndFailedPhasesSurfaceSeparatelyFromFavorite() {
        val track = Track(
            canonicalId = "yt:abc",
            title = "Song",
            artists = listOf(ArtistRef("a", "Artist")),
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.YOUTUBE_MUSIC,
                    providerTrackId = "abc",
                    isPlayable = true,
                    handle = PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, "abc"),
                ),
            ),
        )
        val downloading = resolveTrackAvailability(
            track,
            download = DownloadItemState(
                ownerCanonicalId = "yt:abc",
                title = "Song",
                artistLine = "Artist",
                phase = DownloadPhase.DOWNLOADING,
                progress = 0.4f,
            ),
        )
        assertEquals(TrackAvailability.DOWNLOADING, downloading.status)
        assertEquals(0.4f, downloading.progress)

        val failed = resolveTrackAvailability(
            track,
            download = DownloadItemState(
                ownerCanonicalId = "yt:abc",
                title = "Song",
                artistLine = "Artist",
                phase = DownloadPhase.FAILED,
                errorMessage = "boom",
            ),
        )
        assertEquals(TrackAvailability.FAILED, failed.status)
        assertEquals("boom", failed.errorMessage)
    }

    @Test
    fun missingLocalFileIsUnavailable() {
        val track = Track(
            canonicalId = "localfile:song.flac",
            title = "Song",
            artists = listOf(ArtistRef("a", "Artist")),
            sources = emptyList(),
        )
        val info = resolveTrackAvailability(track)
        assertEquals(TrackAvailability.UNAVAILABLE, info.status)
    }
}
