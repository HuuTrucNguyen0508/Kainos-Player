package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceResolverTest {
    private val resolver = DefaultSourceResolver()

    @Test
    fun selectsSpotifyWhenItHasHighestBitrate() = runTest {
        val track = merged(
            track("Song", "Artist", provider = ProviderId.SPOTIFY, bitrate = 320),
            track("Song", "Artist", provider = ProviderId.YOUTUBE_MUSIC, bitrate = 256, quality = QualityTier.STANDARD),
            track("Song", "Artist", provider = ProviderId.SOUNDCLOUD, bitrate = 128, quality = QualityTier.LOW),
        )
        val resolved = resolver.resolve(track, PlaybackPreferences.Default)
        assertEquals(ProviderId.SPOTIFY, resolved.source.provider)
    }

    @Test
    fun prefersSpotifyOnEqualQuality() = runTest {
        val track = merged(
            track("Song", "Artist", provider = ProviderId.SPOTIFY, bitrate = 256, quality = QualityTier.HIGH),
            track("Song", "Artist", provider = ProviderId.YOUTUBE_MUSIC, bitrate = 256, quality = QualityTier.HIGH),
            track("Song", "Artist", provider = ProviderId.SOUNDCLOUD, bitrate = 256, quality = QualityTier.HIGH),
        )
        val resolved = resolver.resolve(
            track,
            PlaybackPreferences(preferredProvider = ProviderId.SPOTIFY),
        )
        assertEquals(ProviderId.SPOTIFY, resolved.source.provider)
    }

    @Test
    fun selectsSoundCloudWhenItIsBestAvailable() = runTest {
        val track = merged(
            track("Song", "Artist", provider = ProviderId.SPOTIFY, playable = false),
            track("Song", "Artist", provider = ProviderId.YOUTUBE_MUSIC, bitrate = 256, quality = QualityTier.STANDARD),
            track("Song", "Artist", provider = ProviderId.SOUNDCLOUD, bitrate = 320),
        )
        val resolved = resolver.resolve(track, PlaybackPreferences.Default)
        assertEquals(ProviderId.SOUNDCLOUD, resolved.source.provider)
        assertTrue(resolved.fallbacks.any { it.provider == ProviderId.YOUTUBE_MUSIC })
    }

    @Test
    fun youtubeWinsWhenItIsTheOnlyHighQualitySource() = runTest {
        val track = merged(
            track("Song", "Artist", provider = ProviderId.SPOTIFY, bitrate = 160, quality = QualityTier.STANDARD),
            track("Song", "Artist", provider = ProviderId.YOUTUBE_MUSIC, bitrate = 256, quality = QualityTier.HIGH),
            track("Song", "Artist", provider = ProviderId.SOUNDCLOUD, playable = false),
        )
        val resolved = resolver.resolve(track, PlaybackPreferences.Default)
        assertEquals(ProviderId.YOUTUBE_MUSIC, resolved.source.provider)
    }

    @Test
    fun unknownQualityRanksBelowKnownHighQuality() = runTest {
        val unknown = track("Song", "Artist", provider = ProviderId.SOUNDCLOUD).let { original ->
            original.copy(sources = original.sources.map { it.copy(quality = null) })
        }
        val track = merged(
            track("Song", "Artist", provider = ProviderId.SPOTIFY, bitrate = 320),
            unknown,
        )
        val resolved = resolver.resolve(track, PlaybackPreferences.Default)
        assertEquals(ProviderId.SPOTIFY, resolved.source.provider)
    }

    @Test
    fun unavailableSourcesAreIgnored() = runTest {
        val track = merged(
            track("Song", "Artist", provider = ProviderId.SPOTIFY, playable = false),
            track("Song", "Artist", provider = ProviderId.YOUTUBE_MUSIC, playable = false),
            track("Song", "Artist", provider = ProviderId.SOUNDCLOUD, bitrate = 128, quality = QualityTier.LOW),
        )
        val resolved = resolver.resolve(track, PlaybackPreferences.Default)
        assertEquals(ProviderId.SOUNDCLOUD, resolved.source.provider)
        assertTrue(resolved.fallbacks.isEmpty())
    }

    @Test
    fun forcedProviderIsHonored() = runTest {
        val track = merged(
            track("Song", "Artist", provider = ProviderId.SPOTIFY, bitrate = 320),
            track("Song", "Artist", provider = ProviderId.SOUNDCLOUD, bitrate = 128, quality = QualityTier.LOW),
        )
        val resolved = resolver.resolve(
            track,
            PlaybackPreferences(sourceSelection = SourceSelectionMode.FORCE_SOUNDCLOUD),
        )
        assertEquals(ProviderId.SOUNDCLOUD, resolved.source.provider)
    }

    @Test
    fun forcedUnavailableProviderFails() = runTest {
        val track = merged(
            track("Song", "Artist", provider = ProviderId.YOUTUBE_MUSIC, bitrate = 256),
        )
        assertFailsWith<IllegalStateException> {
            resolver.resolve(track, PlaybackPreferences(sourceSelection = SourceSelectionMode.FORCE_SPOTIFY))
        }
    }

    @Test
    fun noPlayableSourcesFails() = runTest {
        val track = merged(
            track("Song", "Artist", provider = ProviderId.SPOTIFY, playable = false),
        )
        assertFailsWith<IllegalStateException> {
            resolver.resolve(track, PlaybackPreferences.Default)
        }
    }

    private fun merged(vararg tracks: Track): Track {
        val head = tracks.first()
        return head.copy(sources = tracks.flatMap { it.sources })
    }
}
