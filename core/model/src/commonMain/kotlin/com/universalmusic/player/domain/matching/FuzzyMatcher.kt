package com.universalmusic.player.domain.matching

/**
 * fzf-style matching for Library type-to-search. Inputs are expected to be folded with
 * [TrackNormalizer.fold] (lowercase, no diacritics, punctuation collapsed to spaces).
 *
 * Every whitespace-separated query token must appear in the haystack as a subsequence
 * ("kmn" finds "kimi no namae"). Contiguous runs, word starts and exact substrings score
 * higher, so the best matches sort first while loose ones still show up further down.
 */
object FuzzyMatcher {
    /** Relevance of [query] in [haystack], or null when some token does not match. */
    fun score(query: String, haystack: String): Int? {
        var total = 0
        var any = false
        for (token in query.split(' ')) {
            if (token.isEmpty()) continue
            any = true
            total += scoreToken(token, haystack) ?: return null
        }
        return if (any) total else 0
    }

    fun matches(query: String, haystack: String): Boolean = score(query, haystack) != null

    private fun scoreToken(token: String, haystack: String): Int? {
        val exact = haystack.indexOf(token)
        if (exact >= 0) {
            var score = EXACT_BASE + token.length * EXACT_PER_CHAR
            if (isWordStart(haystack, exact)) score += WORD_START
            if (exact == 0) score += LEADING
            return score
        }
        var score = 0
        var from = 0
        var previous = -2
        for (c in token) {
            val found = haystack.indexOf(c, from)
            if (found < 0) return null
            score += PER_CHAR
            if (found == previous + 1) score += CONSECUTIVE
            if (isWordStart(haystack, found)) score += WORD_START / 2
            score -= minOf(found - from, MAX_GAP_PENALTY)
            previous = found
            from = found + 1
        }
        return score
    }

    private fun isWordStart(haystack: String, index: Int): Boolean =
        index == 0 || haystack[index - 1] == ' '

    private const val EXACT_BASE = 100
    private const val EXACT_PER_CHAR = 16
    private const val WORD_START = 40
    private const val LEADING = 20
    private const val PER_CHAR = 10
    private const val CONSECUTIVE = 15
    private const val MAX_GAP_PENALTY = 10
}
