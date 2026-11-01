package com.launcher.tv.data.simkl

import com.launcher.tv.data.MediaKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SimklDataTest {

    /** Shaped like the examples in https://api.simkl.org/api-reference/simkl/get-all-items. */
    private val allItems = """
    {
      "shows": [
        { "added_to_watchlist_at": "2014-11-12T18:42:53Z", "last_watched_at": "2026-05-04T19:40:59Z", "status": "watching",
          "last_watched": "S08E02", "next_to_watch": "S08E03", "watched_episodes_count": 126, "total_episodes_count": 165,
          "show": { "title": "Futurama", "poster": "18/18421459bbf0f06beb", "year": 1999,
                    "ids": { "simkl": 3407, "slug": "futurama", "imdb": "tt0149460", "tvdb": "73871", "tmdb": "615" } },
          "next_to_watch_info": { "title": "How the West Was 1010001", "season": 8, "episode": 3, "date": "2023-08-07T00:00:00-05:00" } },
        { "last_watched_at": "2026-05-06T10:00:00Z", "status": "watching", "next_to_watch": null,
          "show": { "title": "The Walking Dead", "year": 2010, "ids": { "simkl": 2090, "slug": "the-walking-dead" } } },
        { "last_watched_at": "2026-05-07T10:00:00Z", "status": "watching", "next_to_watch": "S02E01",
          "show": { "title": "Severance", "year": 2022, "ids": { "simkl": 900, "tmdb": "95396" } } },
        { "status": "completed", "next_to_watch": null,
          "show": { "title": "Charmed", "year": 1998, "ids": { "simkl": 297 } } }
      ],
      "anime": [
        { "last_watched_at": "2026-05-01T00:00:00Z", "status": "watching", "next_to_watch": "E12",
          "show": { "title": "Frieren", "year": 2023, "ids": { "simkl": 1880000, "mal": "52991" } }, "anime_type": "tv" }
      ],
      "movies": [
        { "added_to_watchlist_at": "2026-04-10T20:13:02Z", "status": "plantowatch",
          "movie": { "title": "The Godfather", "poster": "10/102447562f6b49ff3a", "year": 1972, "ids": { "simkl": 53434, "slug": "the-godfather", "imdb": "tt0068646", "tmdb": "238" } } },
        { "added_to_watchlist_at": "2026-05-10T20:13:02Z", "status": "plantowatch",
          "movie": { "title": "Dune: Part Two", "year": 2024, "ids": { "simkl": 1, "tmdb": "693134" } } },
        { "status": "completed", "movie": { "title": "Pulp Fiction", "year": 1994, "ids": { "simkl": 54130 } } }
      ]
    }
    """.trimIndent()

    private fun library() = SimklLibrary(SimklData.parseAllItems(Json.parseToJsonElement(allItems)).associateBy { it.key })

    @Test
    fun parsesAllItems() {
        val items = SimklData.parseAllItems(Json.parseToJsonElement(allItems))
        assertEquals(8, items.size)
        val futurama = items.first { it.simkl == 3407L }
        assertEquals("shows", futurama.type)
        assertEquals("Futurama", futurama.title)
        assertEquals(1999, futurama.year)
        assertEquals(615L, futurama.tmdb) // ids are strings in Simkl responses
        assertEquals("tt0149460", futurama.imdb)
        assertEquals("S08E03", futurama.nextToWatch)
        assertEquals("How the West Was 1010001", futurama.nextTitle)
        assertEquals("anime:1880000", items.first { it.type == "anime" }.key)
        // An empty library is {} and an all-items response may omit any bucket.
        assertTrue(SimklData.parseAllItems(Json.parseToJsonElement("{}")).isEmpty())
    }

    @Test
    fun upNextShowsWatchingTitlesWithSomethingLeft() {
        val rows = SimklData.upNext(library())
        // Walking Dead is caught up (next_to_watch null), Charmed is completed, movies never appear.
        assertEquals(listOf("Severance", "Futurama", "Frieren"), rows.map { it.title })
        val f = rows.first { it.title == "Futurama" }
        assertEquals(MediaKind.EPISODE, f.kind)
        assertEquals(8, f.season)
        assertEquals(3, f.episode)
        assertEquals("S08E03 · How the West Was 1010001", f.subtitle)
        assertEquals(3407L, f.simklId)
        assertEquals("https://simkl.com/tv/3407/futurama", f.simklUrl)
        assertEquals("https://wsrv.nl/?url=https://simkl.in/posters/18/18421459bbf0f06beb_m.webp&q=90", f.posterUrl)
        // Anime is numbered per cour: no season.
        val anime = rows.first { it.title == "Frieren" }
        assertTrue(anime.anime)
        assertNull(anime.season)
        assertEquals(12, anime.episode)
        assertEquals("https://simkl.com/anime/1880000", anime.simklUrl)
    }

    @Test
    fun watchlistIsPlanToWatchMoviesNewestFirst() {
        val rows = SimklData.watchlistMovies(library())
        assertEquals(listOf("Dune: Part Two", "The Godfather"), rows.map { it.title })
        assertEquals(MediaKind.MOVIE, rows[0].kind)
        assertEquals(693134L, rows[0].tmdbId)
        assertEquals("https://simkl.com/movies/53434/the-godfather", rows[1].simklUrl)
    }

    private fun activities(all: String, watching: String = "2026-05-01T00:00:00Z", removed: String = "2026-01-01T00:00:00Z", rated: String = "2026-01-01T00:00:00Z"): JsonObject =
        Json.parseToJsonElement(
            """{ "all": "$all", "settings": { "all": "2026-01-01T00:00:00Z" },
                 "tv_shows": { "all": "$all", "watching": "$watching", "plantowatch": "2026-01-01T00:00:00Z", "rated_at": "$rated", "removed_from_list": "$removed" },
                 "anime": { "all": "2026-01-01T00:00:00Z", "watching": "2026-01-01T00:00:00Z", "removed_from_list": "2026-01-01T00:00:00Z" },
                 "movies": { "all": "2026-01-01T00:00:00Z", "plantowatch": "2026-01-01T00:00:00Z", "removed_from_list": "2026-01-01T00:00:00Z" } }"""
        ).jsonObject

    @Test
    fun activitiesDecideWhatToFetch() {
        val saved = activities("2026-05-01T00:00:00Z")
        // No snapshot yet: the one-time full pull.
        assertNull(SimklData.changes(null, saved))
        // Nothing moved: no further calls.
        assertFalse(SimklData.changes(saved, activities("2026-05-01T00:00:00Z"))!!.any)
        // A list moved: fetch the delta.
        val moved = SimklData.changes(saved, activities("2026-05-02T00:00:00Z", watching = "2026-05-02T00:00:00Z"))!!
        assertTrue(moved.items)
        assertFalse(moved.removals)
        // Something was removed: compare ids.
        val removed = SimklData.changes(saved, activities("2026-05-03T00:00:00Z", removed = "2026-05-03T00:00:00Z"))!!
        assertTrue(removed.removals)
        assertFalse(removed.items)
        // Only a rating changed: HomeBase shows no ratings, so nothing to fetch.
        assertFalse(SimklData.changes(saved, activities("2026-05-04T00:00:00Z", rated = "2026-05-04T00:00:00Z"))!!.any)
    }

    @Test
    fun deltasMergeAndRemovalsDiff() {
        val lib = library().items
        val delta = SimklData.parseAllItems(
            Json.parseToJsonElement(
                """{ "shows": [ { "status": "completed", "next_to_watch": null, "show": { "title": "Severance", "year": 2022, "ids": { "simkl": 900 } } } ] }"""
            )
        )
        val merged = SimklData.merge(lib, delta)
        assertEquals(lib.size, merged.size)
        assertEquals("completed", merged["shows:900"]!!.status)
        assertEquals("watching", merged["shows:3407"]!!.status) // untouched by the delta

        val keep = SimklData.parseKeys(
            Json.parseToJsonElement(
                """{ "shows": [ { "show": { "ids": { "simkl": 3407 } } } ], "movies": [ { "movie": { "ids": { "simkl": 53434 } } } ] }"""
            )
        )
        assertEquals(setOf("shows:3407", "movies:53434"), keep)
    }

    @Test
    fun upcomingFromTheCalendarFile() {
        val now = Instant.parse("2026-07-20T12:00:00Z").toEpochMilli()
        val calendar = """
        {
          "calendar": [
            { "simkl_id": 3407, "date": "2026-07-19T04:00:00Z", "episode": { "season": 9, "episode": 1, "title": "Too early" } },
            { "simkl_id": 3407, "date": "2026-07-22T04:00:00Z", "finale_type": null, "episode": { "season": 9, "episode": 2, "title": "Bender's Big Score", "runtime": 24 } },
            { "simkl_id": 1880000, "date": "2026-07-21T15:00:00Z", "episode": { "episode": 13 } },
            { "simkl_id": 42, "date": "2026-07-21T04:00:00Z", "episode": { "season": 1, "episode": 1 } },
            { "simkl_id": 3407, "date": "2026-09-01T04:00:00Z", "episode": { "season": 9, "episode": 9 } }
          ],
          "metadata": {
            "3407": { "title": "Futurama", "poster": "18/18421459bbf0f06beb", "fanart": "23/23782f1e28e9ca2", "ids": { "simkl_id": 3407, "tmdb": "615" }, "ratings": { "simkl": { "rating": 8.1 } } }
          }
        }
        """.trimIndent()
        val lib = library()
        val airings = SimklData.parseCalendar(calendar, SimklData.followed(lib).keys)
        assertEquals(4, airings.size) // simkl_id 42 isn't one of yours
        val rows = SimklData.upcoming(lib, airings, now)
        // Yesterday's is before today; September is past the 14-day window.
        assertEquals(listOf("Frieren", "Futurama"), rows.map { it.title })
        val f = rows[1]
        assertEquals("S09E02 · Bender's Big Score", f.subtitle)
        assertEquals("https://wsrv.nl/?url=https://simkl.in/fanart/23/23782f1e28e9ca2_medium.webp&q=90", f.imageUrl)
        assertEquals(Instant.parse("2026-07-22T04:00:00Z").toEpochMilli(), f.timeMillis)
        assertEquals("Episode 13", rows[0].subtitle)
    }

    @Test
    fun episodeCodes() {
        assertEquals(2 to 4, SimklData.episodeCode("S02E04"))
        assertEquals(null to 12, SimklData.episodeCode("E12"))
        assertNull(SimklData.episodeCode(null))
        assertNull(SimklData.episodeCode("soon"))
    }
}
