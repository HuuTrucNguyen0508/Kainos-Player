package com.universalmusic.player.data.library

import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.PlaybackHandle

/**
 * Strip resolved streaming URLs from non-local sources so in-memory + disk state stay identity-only.
 */
fun Track.withPersistedSourcesOnly(): Track = copy(
    sources = sources.map { source ->
        when (source.provider) {
            ProviderId.LOCAL -> source
            else -> source.copy(
                streamUrl = null,
                handle = PlaybackHandle.ProviderPlayback(
                    provider = source.provider,
                    trackId = source.providerTrackId,
                    durationMs = durationMs,
                ),
            )
        }
    },
)
