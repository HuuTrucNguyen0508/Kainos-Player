package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.ResolvedPlayback
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.domain.model.Track

interface SourceResolver {
    suspend fun resolve(
        track: Track,
        preferences: PlaybackPreferences,
    ): ResolvedPlayback
}

class DefaultSourceResolver : SourceResolver {
    override suspend fun resolve(
        track: Track,
        preferences: PlaybackPreferences,
    ): ResolvedPlayback {
        val playable = track.playableSources()
        if (playable.isEmpty()) {
            error("No playable sources for ${track.title}")
        }

        val forced = forcedProvider(preferences.sourceSelection)
        if (forced != null) {
            val source = playable.firstOrNull { it.provider == forced }
                ?: error("${forced.displayName} is not available for this track")
            return ResolvedPlayback(
                track = track,
                source = source,
                fallbacks = playable.filterNot { it.provider == forced }.sortedWith(qualityThenPreference(preferences)),
                reason = "Forced ${forced.displayName}",
            )
        }

        val ranked = playable.sortedWith(qualityThenPreference(preferences))
        val selected = ranked.first()
        return ResolvedPlayback(
            track = track,
            source = selected,
            fallbacks = ranked.drop(1),
            reason = selectionReason(preferences, selected.provider),
        )
    }

    /**
     * Tier, then bitrate, then provider preference.
     * Bitrate is not added into the tier score, so a 320 kbps High source cannot outrank
     * lossless with an unknown bitrate. Unverified quality (no source data) ranks below every known tier.
     */
    private fun qualityThenPreference(preferences: PlaybackPreferences): Comparator<PlaybackSource> {
        val bitrateFirst = preferences.sourceSelection == SourceSelectionMode.PREFER_HIGHEST_BITRATE
        return compareByDescending<PlaybackSource> { source ->
            if (bitrateFirst) source.quality?.bitrateKbps ?: -1 else tierRank(source, preferences)
        }
            .thenByDescending { source ->
                if (bitrateFirst) tierRank(source, preferences) else source.quality?.bitrateKbps ?: -1
            }
            .thenByDescending { providerPreferenceScore(it.provider, preferences) }
            // Prefer on-disk hearted cache over streaming when quality ties.
            .thenByDescending { if (it.provider == ProviderId.LOCAL) 1 else 0 }
    }

    private fun tierRank(source: PlaybackSource, preferences: PlaybackPreferences): Int {
        val quality = source.quality
        val rank = quality?.tierRank() ?: 0
        val lossless = quality != null &&
            (quality.tier == QualityTier.LOSSLESS || quality.tier == QualityTier.HI_RES) &&
            rank > 0
        return if (preferences.sourceSelection == SourceSelectionMode.PREFER_LOSSLESS && lossless) {
            rank + 10
        } else {
            rank
        }
    }

    private fun providerPreferenceScore(provider: ProviderId, preferences: PlaybackPreferences): Int {
        val preferred = preferences.preferredProvider ?: preferredFromMode(preferences.sourceSelection)
        return if (preferred == provider) 10 else 0
    }

    private fun preferredFromMode(mode: SourceSelectionMode): ProviderId? = when (mode) {
        SourceSelectionMode.PREFER_SPOTIFY, SourceSelectionMode.FORCE_SPOTIFY -> ProviderId.SPOTIFY
        SourceSelectionMode.PREFER_YOUTUBE_MUSIC, SourceSelectionMode.FORCE_YOUTUBE_MUSIC -> ProviderId.YOUTUBE_MUSIC
        else -> null
    }

    private fun forcedProvider(mode: SourceSelectionMode): ProviderId? = when (mode) {
        SourceSelectionMode.FORCE_SPOTIFY -> ProviderId.SPOTIFY
        SourceSelectionMode.FORCE_YOUTUBE_MUSIC -> ProviderId.YOUTUBE_MUSIC
        else -> null
    }

    private fun selectionReason(preferences: PlaybackPreferences, provider: ProviderId): String =
        when (preferences.sourceSelection) {
            SourceSelectionMode.AUTOMATIC -> "Best available · ${provider.displayName}"
            SourceSelectionMode.PREFER_LOSSLESS -> "Preferred lossless · ${provider.displayName}"
            SourceSelectionMode.PREFER_HIGHEST_BITRATE -> "Highest bitrate · ${provider.displayName}"
            SourceSelectionMode.PREFER_SPOTIFY -> "Preferred Spotify, selected ${provider.displayName}"
            SourceSelectionMode.PREFER_YOUTUBE_MUSIC -> "Preferred YouTube Music, selected ${provider.displayName}"
            SourceSelectionMode.FORCE_SPOTIFY,
            SourceSelectionMode.FORCE_YOUTUBE_MUSIC -> "Manual override · ${provider.displayName}"
        }
}
