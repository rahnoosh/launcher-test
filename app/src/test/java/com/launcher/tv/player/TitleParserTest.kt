package com.launcher.tv.player

import com.launcher.tv.player.TitleParser.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TitleParserTest {
    private fun check(title: String?, url: String?, kind: Kind, name: String, year: Int? = null, s: Int? = null, e: Int? = null) {
        val p = TitleParser.parse(title, url)
        assertEquals("kind for '$title'", kind, p.kind)
        assertEquals("name for '$title'", name, p.name)
        assertEquals("year for '$title'", year, p.year)
        assertEquals("season for '$title'", s, p.season)
        assertEquals("episode for '$title'", e, p.episode)
    }

    @Test fun episodeWithLanguagePrefix() = check("EN - The Bear S03 E05", null, Kind.EPISODE, "The Bear", s = 3, e = 5)
    @Test fun episodeWithEpisodeTitle() = check("The Last of Us - S02E03 - The Path", null, Kind.EPISODE, "The Last of Us", s = 2, e = 3)
    @Test fun episodeNxN() = check("Breaking Bad 1x05", null, Kind.EPISODE, "Breaking Bad", s = 1, e = 5)
    @Test fun episodeFromFileName() = check(null, "http://host:8080/series/u/p/Breaking.Bad.S01E05.720p.mkv", Kind.EPISODE, "Breaking Bad", s = 1, e = 5)
    @Test fun episodeWithYearInShowName() = check("|UK| Doctor Who (2023) S01E02", null, Kind.EPISODE, "Doctor Who", year = 2023, s = 1, e = 2)
    @Test fun movieWithPipesAndTags() = check("|EN| Dune: Part Two (2024) [4K]", null, Kind.MOVIE, "Dune: Part Two", year = 2024)
    @Test fun movieBareYear() = check("Oppenheimer 2023 4K HDR", null, Kind.MOVIE, "Oppenheimer", year = 2023)
    @Test fun movieNamedLikeAYear() = check("1917 (2019)", "http://h/movie/u/p/1.mkv", Kind.MOVIE, "1917", year = 2019)
    @Test fun movieWithoutYearFromUrl() = check("EN - Gladiator II", "http://h:80/movie/u/p/991.mp4", Kind.MOVIE, "Gladiator II")
    @Test fun movieColonCountryPrefix() = check("UK: The Holdovers (2023)", null, Kind.MOVIE, "The Holdovers", year = 2023)
    @Test fun liveChannel() = check("UK: Sky Sports Main Event FHD", "http://h/live/u/p/1234.ts", Kind.LIVE, "Sky Sports Main Event")

    @Test fun numericFileNameIsNotScrobbled() = assertFalse(TitleParser.parse(null, "http://h:80/movie/u/p/991.mp4").canScrobble)
    @Test fun liveIsNotScrobbled() = assertFalse(TitleParser.parse("ESPN HD", "http://h/live/u/p/1.ts").canScrobble)
    @Test fun unknownIsNotScrobbled() = assertFalse(TitleParser.parse("Some Channel", "http://h/u/p/1").canScrobble)
}
