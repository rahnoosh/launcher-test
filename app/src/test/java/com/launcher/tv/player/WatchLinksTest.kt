package com.launcher.tv.player

import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchLinksTest {
    private val episode = MediaItem("upnext-1", MediaKind.EPISODE, "Severance", "S02E04", season = 2, episode = 4, imdbId = "tt11280740", posterUrl = "p", imageUrl = "b")
    private val movie = MediaItem("movie-1", MediaKind.MOVIE, "Dune: Part Two", "2024", imdbId = "tt15239678")

    @Test
    fun stremioOpensTheStreamList() {
        assertEquals("stremio:///detail/series/tt11280740/tt11280740:2:4", WatchLinks.stremioUri(episode, "tt11280740"))
        assertEquals("stremio:///detail/movie/tt15239678/tt15239678", WatchLinks.stremioUri(movie, "tt15239678"))
        // Anime numbered without seasons: the show's page.
        assertEquals("stremio:///detail/series/tt1", WatchLinks.stremioUri(episode.copy(season = null), "tt1"))
    }

    @Test
    fun nuvioGetsTheExtrasItsOwnChannelUses() {
        val e = WatchLinks.nuvioExtras(episode, "tt11280740")
        assertEquals("tt11280740", e["contentId"])
        assertEquals("series", e["contentType"])
        assertEquals("stream", e["launchMode"])
        assertEquals("tt11280740:2:4", e["videoId"])
        assertEquals(2, e["season"])
        assertEquals(4, e["episode"])
        assertEquals("Severance", e["name"])
        assertEquals("p", e["poster"])
        assertEquals("b", e["backdrop"])

        val m = WatchLinks.nuvioExtras(movie, "tt15239678")
        assertEquals("movie", m["contentType"])
        assertEquals("tt15239678", m["videoId"])
        assertFalse("season" in m)

        // No episode numbers: no stream launch, the page link is used instead.
        assertFalse("launchMode" in WatchLinks.nuvioExtras(episode.copy(season = null), "tt1"))
        assertEquals("nuvio://series/tt11280740", WatchLinks.nuvioUri(episode, "tt11280740"))
        assertEquals("nuvio://movie/tt15239678", WatchLinks.nuvioUri(movie, "tt15239678"))
    }
}
