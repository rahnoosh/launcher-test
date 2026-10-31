package com.launcher.tv.data.iptv

import com.launcher.tv.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds Trakt shows/movies in the provider's series and VOD catalogues. */
class CatalogMatcher(private val client: XtreamClient) {

    data class Playable(val url: String, val title: String)

    suspend fun episode(item: MediaItem): Playable? {
        val season = item.season ?: return null
        val number = item.episode ?: return null
        val all = client.series()
        val candidates = withContext(Dispatchers.Default) { rank(all, item, { it.name }, { it.tmdbAny }, { it.yearAny }) }
        for (s in candidates.take(4)) {
            val ep = client.seriesEpisodes(s.seriesId).firstOrNull { it.season == season && it.episode == number } ?: continue
            return Playable(client.episodeUrl(ep), "%s S%02dE%02d".format(item.title, season, number))
        }
        return null
    }

    suspend fun movie(item: MediaItem): Playable? {
        val all = client.movies()
        val best = withContext(Dispatchers.Default) {
            rank(all, item, { it.name }, { it.tmdbAny }, { Names.yearOf(it.name) ?: it.year?.toIntOrNull() }).firstOrNull()
        } ?: return null
        return Playable(client.movieUrl(best), item.year?.let { "${item.title} ($it)" } ?: item.title)
    }

    companion object {
        /** Best matches first: TMDB id, then exact title (+ year), preferring English/US entries. Unit-tested. */
        fun <T> rank(all: List<T>, item: MediaItem, name: (T) -> String, tmdb: (T) -> String?, year: (T) -> Int?): List<T> {
            val key = Names.titleKey(item.title)
            val tmdbId = item.tmdbId?.toString()
            return all.mapNotNull { e ->
                var score = 0
                if (tmdbId != null && tmdb(e) == tmdbId) score += 100
                val n = name(e)
                if (Names.titleKey(n) == key) score += 50
                if (score == 0) return@mapNotNull null
                val y = year(e) ?: Names.yearOf(n)
                if (item.year != null && y != null) score += if (kotlin.math.abs(y - item.year) <= 1) 10 else -30
                val t = Names.tokens(n.substringBefore(' ').plus(" ").plus(n.take(6)))
                if (t.any { it == "EN" || it == "US" || it == "ENG" || it == "UK" }) score += 3
                e to score
            }.sortedByDescending { it.second }.map { it.first }
        }
    }
}
