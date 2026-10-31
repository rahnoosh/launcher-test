package com.launcher.tv.data.iptv

import com.launcher.tv.data.HomeData
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.Spoilers
import com.launcher.tv.data.TeamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCheckTest {
    @Test fun hlsMasterAndMediaPlaylists() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="avc1.640028"
            /live/1080/index.m3u8?token=abc
        """.trimIndent()
        val p = Hls.parse(master, "http://cdn.example.com/espn/master.m3u8?token=abc")
        assertEquals(2, p.variants.size)
        val best = p.variants.maxByOrNull { it.bandwidth }!!
        assertEquals(1080, best.height)
        assertEquals("http://cdn.example.com/live/1080/index.m3u8?token=abc", best.uri)
        assertEquals("http://cdn.example.com/espn/720/index.m3u8", p.variants[0].uri)

        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg100.ts\n#EXTINF:6.0,\nseg101.ts\n#EXTINF:6.0,\nhttps://other.cdn/seg102.ts"
        val m = Hls.parse(media, "http://cdn.example.com/live/1080/index.m3u8")
        assertEquals(listOf("http://cdn.example.com/live/1080/seg100.ts", "http://cdn.example.com/live/1080/seg101.ts", "https://other.cdn/seg102.ts"), m.segments)
    }

    private fun cand(src: String, id: String, name: String, score: Int, reason: String = "network", archive: Boolean = false) =
        ChannelCandidate(IptvChannel(src, id, name, archive = archive), score, reason)

    @Test fun ratingPrefersFastSharpStreams() {
        val c = cand("a", "1", "US: CBS FHD", 60, "network, in TV guide")
        val fast = StreamProbe.rating(ProbeResult(true, 600, 9_000, 1080), c.copy(epgTitle = "NFL"))
        val slow = StreamProbe.rating(ProbeResult(true, 3_500, 2_500, null), cand("b", "2", "US: CBS SD", 60))
        val dead = StreamProbe.rating(ProbeResult(false, error = "HTTP 403"), c)
        assertTrue(fast > slow)
        assertEquals(0, dead)
        assertEquals(1080, StreamProbe.guessResolution("USA ESPN FHD"))
        assertEquals(2160, StreamProbe.guessResolution("UHD | ESPN 4K"))
        assertNull(StreamProbe.guessResolution("ESPN"))
    }

    @Test fun picksTopTwoPerProvider() {
        val picks = ProviderCheck.pick(listOf(
            cand("a", "1", "A1", 90), cand("a", "2", "A2", 80), cand("a", "3", "A3", 70),
            cand("b", "1", "B1", 60), cand("c", "1", "C1", 10),
        ))
        assertEquals(listOf("A1", "A2", "B1"), picks.map { it.channel.name })
    }

    @Test fun spoilersHideScoresOnlyForStartedGames() {
        val live = SportsEvent("espn-1", "Patriots @ Bills", "NFL", 0L, live = true, status = "14-10 Q3 4:12",
            home = TeamInfo("Bills", "BUF", score = "10"), away = TeamInfo("Patriots", "NE", score = "14"))
        val upcoming = SportsEvent("espn-2", "Chiefs @ Broncos", "NFL", 0L, home = TeamInfo("Broncos", "DEN"), away = TeamInfo("Chiefs", "KC"))
        val done = live.copy(id = "espn-3", live = false, finished = true, status = "Final")
        val d = Spoilers.apply(HomeData(myTeams = listOf(live, upcoming), results = listOf(done)), enabled = true, revealed = setOf("espn-3"))
        val hiddenLive = d.myTeams[0]
        assertNull(hiddenLive.home!!.score)
        assertEquals("Q3 4:12", hiddenLive.status)
        assertEquals(upcoming, d.myTeams[1])
        assertEquals("14", d.results[0].away!!.score) // revealed with Show score
        assertEquals(live, Spoilers.apply(HomeData(myTeams = listOf(live)), enabled = false, revealed = emptySet()).myTeams[0])
    }
}
