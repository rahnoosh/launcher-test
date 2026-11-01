package com.launcher.tv.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrobblerBodyTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun traktNeedsAnIdItKnowsAndASeason() {
        val ep = Scrobbler.Match(movie = false, title = "Severance", year = 2022, season = 2, episode = 3, traktId = 11, tmdbId = 95396, simklId = 900)
        assertEquals(
            json("""{"show":{"ids":{"trakt":11,"tmdb":95396}},"episode":{"season":2,"number":3},"progress":42.5}"""),
            Scrobbler.traktBody(ep, 42.5f),
        )
        // Only a Simkl id: Trakt can't use it.
        assertNull(Scrobbler.traktBody(ep.copy(traktId = null, tmdbId = null), 10f))
        // Anime numbered Simkl's way has no season.
        assertNull(Scrobbler.traktBody(ep.copy(season = null, anime = true), 10f))
        assertEquals(
            json("""{"movie":{"ids":{"imdb":"tt0068646"}},"progress":100.0}"""),
            Scrobbler.traktBody(Scrobbler.Match(movie = true, title = "The Godfather", imdbId = "tt0068646"), 120f),
        )
    }

    @Test
    fun simklGetsTitleYearAndEveryId() {
        val ep = Scrobbler.Match(movie = false, title = "Severance", year = 2022, season = 2, episode = 3, traktId = 11, tmdbId = 95396, simklId = 900, imdbId = "tt11280740")
        assertEquals(
            json("""{"progress":42.5,"show":{"title":"Severance","year":2022,"ids":{"simkl":900,"imdb":"tt11280740","tmdb":95396}},"episode":{"season":2,"number":3}}"""),
            Scrobbler.simklBody(ep, 42.5f),
        )
        val movie = Scrobbler.Match(movie = true, title = "Inception", year = 2010, tmdbId = 27205)
        assertEquals(
            json("""{"progress":0.0,"movie":{"title":"Inception","year":2010,"ids":{"tmdb":27205}}}"""),
            Scrobbler.simklBody(movie, 0f),
        )
    }

    @Test
    fun animeNumberingDecidesWhichIdsGoToSimkl() {
        // Simkl's own numbering (per cour, no season): the anime's id and a plain episode number.
        val cour = Scrobbler.Match(movie = false, title = "Frieren", year = 2023, episode = 12, simklId = 1880000, tvdbId = "424536", anime = true)
        assertEquals(
            json("""{"progress":50.0,"anime":{"title":"Frieren","year":2023,"ids":{"simkl":1880000}},"episode":{"number":12}}"""),
            Scrobbler.simklBody(cour, 50f),
        )
        // TVDB-style season numbers: send the TVDB/TMDB ids so Simkl maps them, not the cour's own id.
        val tvdbStyle = cour.copy(season = 2, episode = 4)
        assertEquals(
            json("""{"progress":50.0,"anime":{"title":"Frieren","year":2023,"ids":{"tvdb":424536}},"episode":{"season":2,"number":4}}"""),
            Scrobbler.simklBody(tvdbStyle, 50f),
        )
    }

    @Test
    fun fileNamesForSimklsMatcher() {
        assertEquals("The Bear (2022) S02E03.mkv", Scrobbler.fileName(TitleParser.Parsed(TitleParser.Kind.EPISODE, "The Bear", 2022, 2, 3)))
        assertEquals("Inception (2010).mkv", Scrobbler.fileName(TitleParser.Parsed(TitleParser.Kind.MOVIE, "Inception", 2010)))
        assertNull(Scrobbler.fileName(TitleParser.Parsed(TitleParser.Kind.EPISODE, "The Bear", null, null, 3)))
    }
}
