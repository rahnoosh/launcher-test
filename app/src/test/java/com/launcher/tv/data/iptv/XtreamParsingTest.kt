package com.launcher.tv.data.iptv

import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class XtreamParsingTest {
    @Test fun liveStreamsWithMixedTypes() {
        val json = """[
          {"num":1,"name":"US: ESPN FHD","stream_type":"live","stream_id":101,"stream_icon":"","epg_channel_id":"espn.us","added":"1690000000","category_id":"7","custom_sid":null,"tv_archive":1,"direct_source":"","tv_archive_duration":"3"},
          {"num":2,"name":"US: CBS","stream_type":"live","stream_id":"102","stream_icon":null,"epg_channel_id":null,"category_id":7,"tv_archive":0,"tv_archive_duration":0}
        ]"""
        val list = XJson.decodeFromString(ListSerializer(XtreamChannel.serializer()), json)
        assertEquals(listOf("101", "102"), list.map { it.streamId })
        assertTrue(list[0].hasArchive)
        assertEquals("7", list[1].categoryId)
    }

    @Test fun seriesInfoEpisodesBySeason() {
        val root = Json.parseToJsonElement("""{"seasons":[],"info":{"name":"The Bear"},"episodes":{
            "1":[{"id":"5001","episode_num":1,"title":"System","container_extension":"mkv","season":1}],
            "4":[{"id":"5040","episode_num":"3","title":"Episode 3","container_extension":"mp4","season":"4"}]}}""").jsonObject
        val eps = XtreamClient.parseEpisodes(root)
        assertEquals(2, eps.size)
        val e = eps.first { it.season == 4 }
        assertEquals(3, e.episode)
        assertEquals("5040", e.id)
        assertEquals("mp4", e.extension)
    }

    @Test fun shortEpgDecodesBase64() {
        val title = Base64.getEncoder().encodeToString("NFL Football: Patriots at Bills".toByteArray())
        val root = Json.parseToJsonElement("""{"epg_listings":[{"id":"1","title":"$title","description":"","start":"2026-11-01 13:00:00","end":"2026-11-01 16:30:00","start_timestamp":"1793552400","stop_timestamp":"1793565000"}]}""").jsonObject
        val epg = XtreamClient.parseEpg(root)
        assertEquals("NFL Football: Patriots at Bills", epg.single().title)
        assertEquals(1793552400000L, epg.single().startMillis)
    }

    @Test fun catalogueMatchingPrefersTmdbThenTitleAndYear() {
        val series = listOf(
            XtreamSeries("1", "|FR| The Bear (2022)"),
            XtreamSeries("2", "EN - The Bear (2022)"),
            XtreamSeries("3", "The Bear Grylls Show"),
            XtreamSeries("4", "Bear (1988)"),
        )
        val item = MediaItem("x", MediaKind.EPISODE, "The Bear", "", year = 2022, season = 4, episode = 3)
        val ranked = CatalogMatcher.rank(series, item, { it.name }, { it.tmdbAny }, { it.yearAny })
        assertEquals("2", ranked.first().seriesId)
        assertTrue("3" !in ranked.map { it.seriesId })

        val withTmdb = series + XtreamSeries("9", "Bear, The", tmdb = "136315")
        val ranked2 = CatalogMatcher.rank(withTmdb, item.copy(tmdbId = 136315), { it.name }, { it.tmdbAny }, { it.yearAny })
        assertEquals("9", ranked2.first().seriesId)
    }

    @Test fun serverInputAcceptsM3uLink() {
        val c = XtreamClient.normalize("http://line.example.com:8080/get.php?username=joe&password=secret&type=m3u_plus", "", "")
        assertEquals(XtreamClient.Config("http://line.example.com:8080", "joe", "secret"), c)
        assertEquals("http://host.tv", XtreamClient.normalize("host.tv/", "a", "b").server)
    }
}
