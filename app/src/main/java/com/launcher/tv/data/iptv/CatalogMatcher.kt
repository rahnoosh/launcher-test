package com.launcher.tv.data.iptv

/** Ranking shared by all providers: TMDB id, then exact title (+ year), preferring English/US entries. */
object CatalogMatcher {
    fun <T> rank(all: List<T>, title: String, year: Int?, tmdbId: Long?, name: (T) -> String, tmdb: (T) -> String?, yearOf: (T) -> Int?): List<T> {
        val key = Names.titleKey(title)
        val tmdbStr = tmdbId?.toString()
        return all.mapNotNull { e ->
            var score = 0
            if (tmdbStr != null && tmdb(e) == tmdbStr) score += 100
            val n = name(e)
            if (Names.titleKey(n) == key) score += 50
            if (score == 0) return@mapNotNull null
            val y = yearOf(e) ?: Names.yearOf(n)
            if (year != null && y != null) score += if (kotlin.math.abs(y - year) <= 1) 10 else -30
            if (Regex("""^\W*(EN|ENG|US|UK)\b""", RegexOption.IGNORE_CASE).containsMatchIn(n)) score += 3
            e to score
        }.sortedByDescending { it.second }.map { it.first }
    }
}
