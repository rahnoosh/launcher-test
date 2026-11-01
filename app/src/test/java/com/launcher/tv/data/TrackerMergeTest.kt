package com.launcher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMergeTest {

    private fun ep(
        id: String, title: String, year: Int?, season: Int?, episode: Int?, sort: Long? = null,
        trakt: Long? = null, simkl: Long? = null, tmdb: Long? = null, imdb: String? = null, time: Long? = null,
    ) = MediaItem(
        id = id, kind = MediaKind.EPISODE, title = title, subtitle = "S${season}E$episode", year = year, season = season, episode = episode,
        sortMillis = sort, traktId = trakt, simklId = simkl, tmdbId = tmdb, imdbId = imdb, timeMillis = time,
    )

    @Test
    fun sameShowOnBothBecomesOneCardWithBothIds() {
        val trakt = listOf(
            ep("t1", "Severance", 2022, 2, 3, sort = 100, trakt = 11, tmdb = 95396),
            ep("t2", "The Bear", 2022, 3, 1, sort = 300, trakt = 12, tmdb = 136315),
        )
        val simkl = listOf(
            // Watched more recently on Simkl: its next episode wins.
            ep("s1", "Severance", 2022, 2, 4, sort = 200, simkl = 900, tmdb = 95396),
            ep("s2", "Frieren", 2023, null, 12, sort = 50, simkl = 1880000),
        )
        val merged = TrackerMerge.upNext(trakt, simkl)
        assertEquals(listOf("The Bear", "Severance", "Frieren"), merged.map { it.title })
        val sev = merged[1]
        assertEquals(4, sev.episode)
        assertEquals(11L, sev.traktId)
        assertEquals(900L, sev.simklId)
        assertEquals(200L, sev.sortMillis)
    }

    @Test
    fun matchesByImdbOrTitleAndYearWhenTmdbIsMissing() {
        val a = ep("a", "Futurama", 1999, 8, 3, trakt = 1, imdb = "tt0149460", tmdb = 615)
        val b = ep("b", "Futurama", 1999, 8, 3, simkl = 3407, imdb = "tt0149460")
        assertTrue(TrackerMerge.sameTitle(a, b))
        assertTrue(TrackerMerge.sameTitle(ep("c", "Law & Order", 1990, 1, 1), ep("d", "Law and Order", 1990, 1, 1)))
        // Same title, different year: a remake, not the same show.
        assertFalse(TrackerMerge.sameTitle(ep("e", "Shogun", 2024, 1, 1), ep("f", "Shogun", 1980, 1, 1)))
        // A movie and a show never match, even with the same TMDB number.
        val movie = MediaItem(id = "m", kind = MediaKind.MOVIE, title = "Futurama", subtitle = "", tmdbId = 615)
        assertFalse(TrackerMerge.sameTitle(a, movie))
    }

    @Test
    fun watchlistAndUpcomingDropDuplicates() {
        fun movie(id: String, title: String, tmdb: Long?, sort: Long, trakt: Long? = null, simkl: Long? = null) =
            MediaItem(id = id, kind = MediaKind.MOVIE, title = title, subtitle = "", tmdbId = tmdb, sortMillis = sort, traktId = trakt, simklId = simkl)
        val list = TrackerMerge.watchlist(
            listOf(movie("t1", "Dune: Part Two", 693134, 10, trakt = 1)),
            listOf(movie("s1", "Dune: Part Two", 693134, 20, simkl = 5), movie("s2", "The Godfather", 238, 5, simkl = 6)),
        )
        assertEquals(listOf("Dune: Part Two", "The Godfather"), list.map { it.title })
        assertEquals(5L, list[0].simklId)
        assertEquals(1L, list[0].traktId)

        val upcoming = TrackerMerge.upcoming(
            listOf(ep("t", "Futurama", 1999, 9, 2, trakt = 1, tmdb = 615, time = 2000)),
            listOf(ep("s", "Futurama", 1999, 9, 2, simkl = 3407, tmdb = 615, time = 2000), ep("s3", "Futurama", 1999, 9, 3, simkl = 3407, tmdb = 615, time = 3000)),
        )
        assertEquals(listOf(2, 3), upcoming.map { it.episode })
        assertEquals(3407L, upcoming[0].simklId)
    }
}
