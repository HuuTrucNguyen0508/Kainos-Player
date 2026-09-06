package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.model.PlaybackPreferences
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

    private fun qualityThenPreference(preferences: PlaybackPreferences): Comparator<com.universalmusic.player.domain.model.PlaybackSource> =
        compareByDescending<com.universalmusic.player.domain.model.PlaybackSource> { qualityScore(it, preferences) }
            .thenByDescending { providerPreferenceScore(it.provider, preferences) }

    private fun qualityScore(
        source: com.universalmusic.player.domain.model.PlaybackSource,
        preferences: PlaybackPreferences,
    ): Int {
        val quality = source.quality
        val bitrate = quality?.bitrateKbps ?: 0
        val tierScore = when (quality?.tier) {
            QualityTier.HI_RES -> 500
            QualityTier.LOSSLESS -> 400
            QualityTier.HIGH -> 300
            QualityTier.STANDARD -> 200
            QualityTier.LOW -> 100
            null -> 0
        }
        return when (preferences.sourceSelection) {
            SourceSelectionMode.PREFER_LOSSLESS -> {
                val losslessBonus = if (quality?.tier == QualityTier.LOSSLESS || quality?.tier == QualityTier.HI_RES) 1000 else 0
                losslessBonus + tierScore + bitrate
            }
            SourceSelectionMode.PREFER_HIGHEST_BITRATE -> bitrate * 10 + tierScore
            else -> tierScore + bitrate
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
