package com.company.liquibasevalidator.sql

/** Levenshtein distance, shared by keyword/directive typo detection. */
object TextDistance {

    fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    previous + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
                previous = temp
            }
        }
        return dp[b.length]
    }

    /** Best candidate within [maxDistance] (case-insensitive), or null. */
    fun nearest(word: String, candidates: Collection<String>, maxDistance: Int = 2): String? {
        val upper = word.uppercase()
        return candidates.minByOrNull { editDistance(upper, it.uppercase()) }
            ?.takeIf { editDistance(upper, it.uppercase()) <= maxDistance }
    }
}
