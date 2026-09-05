package com.universalmusic.player.domain.matching

import com.universalmusic.player.domain.model.Track

data class NormalizedTrack(
    val title: String,
    val artists: Set<String>,
    val album: String?,
    val versionTokens: Set<String>,
    val isrc: String?,
    val durationMs: Long?,
)

object TrackNormalizer {
    private val featuringPattern = Regex(
        """(?i)\s*[\(\[]?\s*(feat\.|ft\.|featuring|with)\s+[^)\]]+[)\]]?"""
    )
    private val punctuationPattern = Regex("""[^\p{L}\p{N}\s]""")
    private val whitespacePattern = Regex("""\s+""")
    private val versionPatterns = listOf(
        "live",
        "acoustic",
        "remix",
        "rmx",
        "radio edit",
        "radio mix",
        "instrumental",
        "karaoke",
        "cover",
        "unplugged",
        "remaster",
        "remastered",
        "deluxe",
        "extended",
        "club mix",
        "nightcore",
        "sped up",
        "slowed",
        "reprise",
        "demo",
        "edit",
        "mix",
        "version",
        "mono",
        "stereo",
    )

    fun normalize(track: Track): NormalizedTrack {
        val rawTitle = track.title
        val versionTokens = extractVersionTokens(rawTitle, track.album?.title)
        val strippedTitle = stripFeaturing(rawTitle)
        val title = fold(strippedTitle)
        val artists = track.artists
            .map { fold(stripFeaturing(it.name)) }
            .filter { it.isNotBlank() }
            .toSet()
        val album = track.album?.title?.let { fold(it).ifBlank { null } }
        val isrc = track.isrc?.trim()?.uppercase()?.ifBlank { null }
        return NormalizedTrack(
            title = title,
            artists = artists,
            album = album,
            versionTokens = versionTokens,
            isrc = isrc,
            durationMs = track.durationMs,
        )
    }

    fun fold(value: String): String {
        val decomposed = decompose(value).lowercase().trim()
        return punctuationPattern.replace(decomposed, " ")
            .replace(whitespacePattern, " ")
            .trim()
    }

    fun stripFeaturing(value: String): String =
        featuringPattern.replace(value, " ").trim()

    fun extractVersionTokens(title: String, albumTitle: String? = null): Set<String> {
        val haystack = listOfNotNull(title, albumTitle).joinToString(" ").lowercase()
        return versionPatterns.filter { token ->
            haystack.contains(token)
        }.toSet()
    }

    private fun decompose(value: String): String {
        // Manual Unicode-ish fold for common diacritics without java.text on all targets.
        val builder = StringBuilder(value.length)
        value.forEach { ch ->
            when (ch) {
                'à', 'á', 'â', 'ã', 'ä', 'å', 'ā' -> builder.append('a')
                'ç' -> builder.append('c')
                'è', 'é', 'ê', 'ë', 'ē' -> builder.append('e')
                'ì', 'í', 'î', 'ï', 'ī' -> builder.append('i')
                'ñ' -> builder.append('n')
                'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō' -> builder.append('o')
                'ù', 'ú', 'û', 'ü', 'ū' -> builder.append('u')
                'ý', 'ÿ' -> builder.append('y')
                'ß' -> builder.append("ss")
                'æ' -> builder.append("ae")
                'œ' -> builder.append("oe")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }
}
