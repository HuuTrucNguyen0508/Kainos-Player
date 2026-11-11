package com.universalmusic.player.domain.matching

import com.universalmusic.player.domain.model.Track
import kotlin.math.abs

/**
 * Finds the same recording listed twice from different sources, such as a local file and the
 * Spotify like of the same song, so lists can show it once.
 *
 * A match needs the same title and the same primary artist after folding case, accents and
 * punctuation, and durations within [DURATION_TOLERANCE_MS] when both are known. Remaster and
 * "official video" style suffixes are ignored; any other suffix ("Live", "Acoustic", "Remix",
 * "Radio Edit") stays part of the title, so a live take never hides the studio one.
 *
 * Only copies from different sources are grouped: two local files, or two Spotify likes, are
 * both shown. The kept copy is local first, then Spotify, then YouTube. Nothing here changes
 * what is stored; it only decides what a list shows and plays.
 */
object DuplicateTracks {
    const val DURATION_TOLERANCE_MS = 3_000L

    /** Where a copy comes from, in keep order: the first kind wins. */
    enum class Origin { LOCAL, SPOTIFY, YOUTUBE }

    /** Null for tracks that can never be matched safely: no title, no artist, or an unknown source. */
    fun key(track: Track): String? {
        val origin = origin(track) ?: return null
        val artist = primaryArtist(track) ?: return null
        val title = titleKey(track.title, artist, origin) ?: return null
        return "$artist\u0000$title"
    }

    fun origin(track: Track): Origin? {
        val id = track.canonicalId
        return when {
            id.startsWith("local:") || id.startsWith("localfile:") || id.startsWith("localkey:") -> Origin.LOCAL
            id.startsWith("spotify:") -> Origin.SPOTIFY
            id.startsWith("yt:") -> Origin.YOUTUBE
            else -> null
        }
    }

    /** True when [a] and [b] are the same recording from different sources. */
    fun sameRecording(a: Track, b: Track): Boolean {
        val originA = origin(a) ?: return false
        val originB = origin(b) ?: return false
        if (originA == originB) return false
        val keyA = key(a) ?: return false
        return keyA == key(b) && durationsAgree(a.durationMs, b.durationMs)
    }

    /**
     * Groups [tracks] (any order) into kept copies and the copies they hide. Each hidden copy
     * matched its keeper on title, artist and duration, and came from another source.
     */
    fun group(tracks: List<Track>): DuplicateGroups {
        val buckets = LinkedHashMap<String, MutableList<Pair<Track, Origin>>>()
        for (track in tracks) {
            val origin = origin(track) ?: continue
            val key = key(track) ?: continue
            buckets.getOrPut(key) { mutableListOf() } += track to origin
        }
        val keeperOf = HashMap<String, String>()
        for (bucket in buckets.values) {
            if (bucket.size < 2 || bucket.map { it.second }.distinct().size < 2) continue
            // Keepers are chosen in keep order so a local file is never hidden behind a stream.
            val keepers = mutableListOf<Pair<Track, Origin>>()
            for (candidate in bucket.distinctBy { it.first.canonicalId }.sortedBy { it.second.ordinal }) {
                val (track, origin) = candidate
                val keeper = keepers.firstOrNull { (kept, keptOrigin) ->
                    keptOrigin != origin && durationsAgree(kept.durationMs, track.durationMs)
                }
                if (keeper == null) keepers += candidate else keeperOf[track.canonicalId] = keeper.first.canonicalId
            }
        }
        return DuplicateGroups(keeperOf)
    }

    private fun durationsAgree(a: Long?, b: Long?): Boolean {
        if (a == null || b == null || a <= 0 || b <= 0) return true
        return abs(a - b) <= DURATION_TOLERANCE_MS
    }

    // --- Title -------------------------------------------------------------------------------

    private val bracketed = Regex("""[(\[]([^()\[\]]*)[)\]]""")
    /** "Song - Live at Wembley": Spotify and YouTube put version notes after a spaced dash. */
    private val dashSuffix = Regex("""\s+[-–—]\s+""")
    private val featuringWords = Regex("""^(feat|ft|featuring|with)\b""")
    private val trailingFeaturing = Regex("""(?i)\s+(feat\.?|ft\.|featuring)\s+.*$""")
    private val ignorableNotes = listOf(
        // Remasters are the same recording.
        Regex("""^(\d{4} )?(digital(ly)? )?remaster(ed)?( \d{4})?( version| edition)?$"""),
        Regex("""^remaster(ed)? (in )?\d{4}( version)?$"""),
        Regex("""^explicit( version)?$"""),
        // Presentation words on YouTube uploads, not versions.
        Regex("""^official( music| lyric)? (video|audio)$"""),
        Regex("""^(lyric|lyrics) video$"""),
        Regex("""^(lyrics|audio|visuali[sz]er|hd|hq|4k)$"""),
    )

    private fun titleKey(raw: String, artist: String, origin: Origin): String? {
        var title = raw.trim()
        if (origin == Origin.YOUTUBE) title = stripArtistPrefix(title, artist)
        // Notes in brackets: drop featuring credits and ignorable notes, keep the rest.
        val kept = mutableListOf<String>()
        title = bracketed.replace(title) { match ->
            val note = TrackNormalizer.fold(match.groupValues[1])
            if (note.isNotEmpty() && !featuringWords.containsMatchIn(note) && ignorableNotes.none { it.matches(note) }) {
                kept += note
            }
            " "
        }
        // Then dash notes: the first part is the title, each later part a note.
        val parts = title.split(dashSuffix)
        val base = TrackNormalizer.fold(trailingFeaturing.replace(parts.first(), ""))
        parts.drop(1).forEach { part ->
            val note = TrackNormalizer.fold(trailingFeaturing.replace(part, ""))
            if (note.isNotEmpty() && ignorableNotes.none { it.matches(note) }) kept += note
        }
        if (base.isEmpty()) return null
        return (listOf(base) + kept.sorted()).joinToString(" | ")
    }

    /** YouTube titles are often "Artist - Song"; drop the artist so the song alone is compared. */
    private fun stripArtistPrefix(title: String, artist: String): String {
        val parts = title.split(dashSuffix, limit = 2)
        if (parts.size < 2) return title
        return if (artistKey(parts[0]) == artist) parts[1] else title
    }

    // --- Artist ------------------------------------------------------------------------------

    private val artistSeparators = Regex("""\s*(,|;|/|&|\+|\sx\s|\sfeat\.?\s|\sft\.\s|\sfeaturing\s|\svs\.?\s)\s*""", RegexOption.IGNORE_CASE)
    private val youtubeChannelNoise = Regex("""(?i)(\s+-\s+topic|vevo|\s+official)$""")
    private val unknownArtists = setOf("unknown", "unknown artist", "various artists", "various")

    private fun primaryArtist(track: Track): String? {
        val first = track.artists.firstOrNull()?.name ?: return null
        return artistKey(first)
    }

    private fun artistKey(name: String): String? {
        val cleaned = youtubeChannelNoise.replace(name.trim(), "")
        val primary = cleaned.split(artistSeparators).firstOrNull { it.isNotBlank() } ?: return null
        val folded = TrackNormalizer.fold(primary).removePrefix("the ").trim()
        return folded.takeIf { it.isNotEmpty() && it !in unknownArtists }
    }
}

/** Which listed copies a list hides, by canonical id, and the copy each one folds into. */
class DuplicateGroups(private val keeperOf: Map<String, String>) {
    val hiddenCount: Int get() = keeperOf.size

    fun isHidden(canonicalId: String): Boolean = canonicalId in keeperOf

    /** The copy shown in place of [canonicalId]; itself when it is not hidden. */
    fun keeper(canonicalId: String): String = keeperOf[canonicalId] ?: canonicalId

    /** [tracks] without hidden copies, order kept. */
    fun visible(tracks: List<Track>): List<Track> =
        if (keeperOf.isEmpty()) tracks else tracks.filter { it.canonicalId !in keeperOf }

    /** A heart on any copy counts for the copy that is shown. */
    fun effectiveFavorites(favorites: Set<String>): Set<String> {
        if (keeperOf.isEmpty()) return favorites
        val extra = keeperOf.filter { (hidden, _) -> hidden in favorites }.values
        return if (extra.isEmpty()) favorites else favorites + extra
    }

    /** Every copy that folds into [keeperId], including itself: what a heart toggle should cover. */
    fun copiesOf(keeperId: String): Set<String> =
        keeperOf.filterValues { it == keeperId }.keys + keeperId

    override fun equals(other: Any?): Boolean = other is DuplicateGroups && other.keeperOf == keeperOf

    override fun hashCode(): Int = keeperOf.hashCode()

    companion object {
        val NONE = DuplicateGroups(emptyMap())
    }
}
