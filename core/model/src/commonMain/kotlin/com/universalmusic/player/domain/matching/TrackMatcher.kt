package com.universalmusic.player.domain.matching

import com.universalmusic.player.domain.model.MatchReason
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackMatch

class TrackMatcher(
    private val mergeThreshold: Float = 0.70f,
) {
    fun match(left: Track, right: Track): TrackMatch {
        val a = TrackNormalizer.normalize(left)
        val b = TrackNormalizer.normalize(right)

        if (!a.isrc.isNullOrBlank() && a.isrc == b.isrc) {
            return TrackMatch(merge(left, right), 0.99f, MatchReason.ISRC)
        }

        if (conflictingVersions(a, b)) {
            return TrackMatch(left, 0f, MatchReason.NONE)
        }

        val titleExact = a.title.isNotBlank() && a.title == b.title
        val artistExact = a.artists.isNotEmpty() && a.artists == b.artists
        val albumExact = !a.album.isNullOrBlank() && a.album == b.album
        val versionsAlign = a.versionTokens == b.versionTokens

        if (titleExact && artistExact && albumExact && versionsAlign) {
            return TrackMatch(merge(left, right), 0.94f, MatchReason.NORMALIZED_METADATA)
        }

        if (titleExact && artistExact && versionsAlign && durationSimilar(a.durationMs, b.durationMs, 2_000)) {
            return TrackMatch(merge(left, right), 0.88f, MatchReason.DURATION_SIMILARITY)
        }

        val titleScore = maxOf(
            StringSimilarity.ratio(a.title, b.title),
            StringSimilarity.tokenJaccard(a.title, b.title),
        )
        val artistScore = StringSimilarity.artistOverlap(a.artists, b.artists)
        val durationOk = durationSimilar(a.durationMs, b.durationMs, 5_000)
        val fuzzy = (titleScore * 0.6f) + (artistScore * 0.4f)

        if (versionsAlign && titleScore >= 0.86f && artistScore >= 0.80f && durationOk && fuzzy >= mergeThreshold) {
            return TrackMatch(merge(left, right), fuzzy.coerceAtMost(0.84f), MatchReason.FUZZY_METADATA)
        }

        return TrackMatch(left, 0f, MatchReason.NONE)
    }

    fun group(tracks: List<Track>): List<Track> {
        val clusters = mutableListOf<Track>()
        tracks.forEach { candidate ->
            var merged = false
            for (index in clusters.indices) {
                val existing = clusters[index]
                val result = match(existing, candidate)
                if (result.confidence >= mergeThreshold && result.reason != MatchReason.NONE) {
                    clusters[index] = result.track
                    merged = true
                    break
                }
            }
            if (!merged) {
                clusters += candidate
            }
        }
        return clusters
    }

    private fun conflictingVersions(a: NormalizedTrack, b: NormalizedTrack): Boolean {
        if (a.versionTokens == b.versionTokens) return false
        if (a.versionTokens.isEmpty() && b.versionTokens.isEmpty()) return false
        return a.versionTokens != b.versionTokens
    }

    private fun durationSimilar(left: Long?, right: Long?, toleranceMs: Long): Boolean {
        if (left == null || right == null) return false
        return kotlin.math.abs(left - right) <= toleranceMs
    }

    private fun merge(left: Track, right: Track): Track {
        val sources = linkedMapOf<String, PlaybackSource>()
        (left.sources + right.sources).forEach { source ->
            sources["${source.provider}:${source.providerTrackId}"] = source
        }
        return left.copy(
            title = preferredTitle(left, right),
            artists = if (left.artists.isNotEmpty()) left.artists else right.artists,
            album = left.album ?: right.album,
            durationMs = left.durationMs ?: right.durationMs,
            artwork = left.artwork ?: right.artwork,
            explicit = left.explicit || right.explicit,
            isrc = left.isrc ?: right.isrc,
            sources = sources.values.toList(),
        )
    }

    private fun preferredTitle(left: Track, right: Track): String {
        val leftClean = left.title.length
        val rightClean = right.title.length
        return if (leftClean <= rightClean) left.title else right.title
    }
}
