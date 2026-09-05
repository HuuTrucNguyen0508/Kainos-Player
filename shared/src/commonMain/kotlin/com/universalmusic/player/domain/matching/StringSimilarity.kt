package com.universalmusic.player.domain.matching

internal object StringSimilarity {
    fun tokenJaccard(a: String, b: String): Float {
        val left = a.split(" ").filter { it.isNotBlank() }.toSet()
        val right = b.split(" ").filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0f
        val intersection = left.intersect(right).size.toFloat()
        val union = left.union(right).size.toFloat()
        return intersection / union
    }

    fun ratio(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = levenshtein(a, b)
        val max = maxOf(a.length, b.length).toFloat()
        return 1f - (distance / max)
    }

    fun artistOverlap(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var best = 0f
        a.forEach { left ->
            b.forEach { right ->
                best = maxOf(best, maxOf(ratio(left, right), tokenJaccard(left, right)))
            }
        }
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[m][n]
    }
}
