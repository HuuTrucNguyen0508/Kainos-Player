package com.universalmusic.player.domain.matching

import com.universalmusic.player.domain.model.AlbumRef
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.MatchReason
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackMatcherTest {
    private val matcher = TrackMatcher()

    @Test
    fun isrcExactMatchMergesSources() {
        val spotify = track("Everything In Its Right Place", "Radiohead", "Kid A", isrc = "GBAYE0001234", provider = ProviderId.SPOTIFY)
        val youtube = track("everything in its right place", "radiohead", "Kid A", isrc = "GBAYE0001234", provider = ProviderId.YOUTUBE_MUSIC)
        val match = matcher.match(spotify, youtube)
        assertEquals(MatchReason.ISRC, match.reason)
        assertTrue(match.confidence > 0.9f)
        assertEquals(2, match.track.sources.size)
    }

    @Test
    fun differentCapitalizationMatches() {
        val a = track("Paranoid Android", "Radiohead", "OK Computer", provider = ProviderId.SPOTIFY)
        val b = track("paranoid android", "RADIOHEAD", "ok computer", provider = ProviderId.YOUTUBE_MUSIC)
        val match = matcher.match(a, b)
        assertEquals(MatchReason.NORMALIZED_METADATA, match.reason)
    }

    @Test
    fun featuringVariantsMatch() {
        val a = track("Crazy in Love (feat. Jay-Z)", "Beyoncé", "Dangerously in Love", durationMs = 236_000, provider = ProviderId.SPOTIFY)
        val b = track("Crazy in Love ft. Jay-Z", "Beyonce", "Dangerously in Love", durationMs = 236_200, provider = ProviderId.YOUTUBE_MUSIC)
        val match = matcher.match(a, b)
        assertTrue(match.reason == MatchReason.NORMALIZED_METADATA || match.reason == MatchReason.DURATION_SIMILARITY || match.reason == MatchReason.FUZZY_METADATA)
        assertTrue(match.confidence >= 0.7f)
    }

    @Test
    fun remixIsNotMergedWithOriginal() {
        val original = track("Get Lucky", "Daft Punk", "Random Access Memories", provider = ProviderId.SPOTIFY)
        val remix = track("Get Lucky - Remix", "Daft Punk", "Random Access Memories", provider = ProviderId.YOUTUBE_MUSIC)
        val match = matcher.match(original, remix)
        assertEquals(MatchReason.NONE, match.reason)
        assertEquals(0f, match.confidence)
    }

    @Test
    fun liveVersionIsSeparate() {
        val studio = track("Weird Fishes / Arpeggi", "Radiohead", "In Rainbows", provider = ProviderId.SPOTIFY)
        val live = track("Weird Fishes / Arpeggi - Live", "Radiohead", "In Rainbows From the Basement", provider = ProviderId.YOUTUBE_MUSIC)
        assertEquals(MatchReason.NONE, matcher.match(studio, live).reason)
    }

    @Test
    fun acousticAndRemasterStaySeparate() {
        val original = track("Yellow", "Coldplay", "Parachutes", provider = ProviderId.SPOTIFY)
        val acoustic = track("Yellow - Acoustic", "Coldplay", "Parachutes", provider = ProviderId.YOUTUBE_MUSIC)
        val remaster = track("Yellow (2024 Remaster)", "Coldplay", "Parachutes", provider = ProviderId.YOUTUBE_MUSIC)
        assertEquals(MatchReason.NONE, matcher.match(original, acoustic).reason)
        assertEquals(MatchReason.NONE, matcher.match(original, remaster).reason)
    }

    @Test
    fun albumVersionWithSameMetadataMerges() {
        val a = track("Idioteque", "Radiohead", "Kid A", durationMs = 307_000, provider = ProviderId.SPOTIFY)
        val b = track("Idioteque", "Radiohead", "Kid A", durationMs = 307_400, provider = ProviderId.YOUTUBE_MUSIC)
        val match = matcher.match(a, b)
        assertTrue(match.confidence >= 0.85f)
    }

    @Test
    fun durationDifferenceWithoutSharedMetadataDoesNotMerge() {
        val a = track("Hello", "Adele", "25", durationMs = 295_000, provider = ProviderId.SPOTIFY)
        val b = track("Hello", "Lionel Richie", "Can't Slow Down", durationMs = 249_000, provider = ProviderId.YOUTUBE_MUSIC)
        assertEquals(MatchReason.NONE, matcher.match(a, b).reason)
    }

    @Test
    fun groupCollapsesDuplicatesAcrossProviders() {
        val tracks = listOf(
            track("Reckoner", "Radiohead", "In Rainbows", isrc = "GBUM70704123", provider = ProviderId.SPOTIFY),
            track("Reckoner", "Radiohead", "In Rainbows", isrc = "GBUM70704123", provider = ProviderId.YOUTUBE_MUSIC),
            track("Reckoner - Live", "Radiohead", "In Rainbows From the Basement", provider = ProviderId.YOUTUBE_MUSIC),
        )
        val grouped = matcher.group(tracks)
        assertEquals(2, grouped.size)
        assertEquals(2, grouped.first { it.sources.any { source -> source.provider == ProviderId.SPOTIFY } }.sources.size)
    }

    @Test
    fun punctuationAndUnicodeFold() {
        val a = track("Crème Brûlée", "Artist", "Album", provider = ProviderId.SPOTIFY)
        val b = track("Creme Brulee", "Artist", "Album", provider = ProviderId.YOUTUBE_MUSIC)
        assertEquals(MatchReason.NORMALIZED_METADATA, matcher.match(a, b).reason)
    }
}

internal fun track(
    title: String,
    artist: String,
    album: String? = null,
    durationMs: Long? = 240_000,
    isrc: String? = null,
    provider: ProviderId,
    playable: Boolean = true,
    bitrate: Int? = 320,
    quality: QualityTier = QualityTier.HIGH,
): Track {
    val source = PlaybackSource(
        provider = provider,
        providerTrackId = "${provider.name.lowercase()}-$title",
        quality = AudioQuality(tier = quality, bitrateKbps = bitrate, codec = "aac"),
        isPlayable = playable,
        handle = PlaybackHandle.ProviderPlayback(provider, "${provider.name}-$title"),
    )
    return Track(
        canonicalId = "${provider.name}:$title:$artist",
        title = title,
        artists = listOf(ArtistRef("artist-$artist", artist)),
        album = album?.let { AlbumRef("album-$it", it) },
        durationMs = durationMs,
        isrc = isrc,
        sources = listOf(source),
    )
}
