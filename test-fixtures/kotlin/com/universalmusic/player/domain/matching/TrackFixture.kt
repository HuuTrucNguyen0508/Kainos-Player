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
    canonicalId: String? = null,
): Track {
    val source = PlaybackSource(
        provider = provider,
        providerTrackId = "${provider.name.lowercase()}-$title",
        quality = AudioQuality(tier = quality, bitrateKbps = bitrate, codec = "aac"),
        isPlayable = playable,
        handle = PlaybackHandle.ProviderPlayback(provider, "${provider.name}-$title"),
    )
    return Track(
        canonicalId = canonicalId ?: "${provider.name}:$title:$artist",
        title = title,
        artists = listOf(ArtistRef("artist-$artist", artist)),
        album = album?.let { AlbumRef("album-$it", it) },
        durationMs = durationMs,
        isrc = isrc,
        sources = listOf(source),
    )
}
