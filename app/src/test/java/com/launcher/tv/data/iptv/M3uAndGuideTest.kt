package com.launcher.tv.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.time.Instant
import java.util.zip.GZIPOutputStream

class M3uAndGuideTest {
    private val playlist = """
        #EXTM3U url-tvg="http://epg.example.com/guide.xml.gz" x-tvg-url="http://other/guide.xml"
        #EXTINF:-1 tvg-id="espn.us" tvg-name="US: ESPN" tvg-logo="http://logo/espn.png" group-title="USA | Sports, Live" catchup="default" catchup-days="3" catchup-source="http://cdn.example.com/espn/index-{utc}-{duration}.m3u8",US: ESPN FHD
        http://cdn.example.com/espn/index.m3u8
        #EXTINF:-1 tvg-id="" group-title="USA | Locals",US: CBS (WBZ) Boston
        #EXTVLCOPT:http-user-agent=TiviMate/5.0
        http://line.example.com:8080/joe/secret/1234
        #EXTINF:-1 group-title="VOD | Series",The Bear S04 E03
        http://line.example.com:8080/series/joe/secret/5040.mkv
        #EXTINF:-1 group-title="Movies 2024",|EN| Dune: Part Two (2024)
        http://line.example.com:8080/movie/joe/secret/991.mp4
    """.trimIndent()

    @Test fun parsesChannelsAndVod() {
        val r = M3uParser.parse(playlist.lineSequence(), "p1")
        assertEquals("http://epg.example.com/guide.xml.gz", r.epgUrl)
        assertEquals(listOf("US: ESPN FHD", "US: CBS (WBZ) Boston"), r.channels.map { it.name })
        val espn = r.channels[0]
        assertEquals("espn.us", espn.epgId)
        assertEquals("USA | Sports, Live", espn.group)          // comma inside quotes
        assertTrue(espn.archive)
        assertNull(r.channels[1].epgId)                         // empty tvg-id
        assertEquals("TiviMate/5.0", r.channels[1].userAgent)
        assertEquals(listOf("The Bear S04 E03", "|EN| Dune: Part Two (2024)"), r.vod.map { it.name })
    }

    @Test fun catchupFromTemplateAndXtreamStyleUrls() {
        val r = M3uParser.parse(playlist.lineSequence(), "p1")
        val start = Instant.parse("2026-11-01T13:00:00Z").toEpochMilli()
        val espn = Catchup.url(r.channels[0], start, 240, now = start + 3600_000L)
        assertEquals("http://cdn.example.com/espn/index-${start / 1000}-14400.m3u8", espn)
        val cbs = Catchup.url(r.channels[1], start, 240, now = start + 3600_000L)
        assertEquals("http://line.example.com:8080/timeshift/joe/secret/240/2026-11-01:13-00/1234.ts", cbs)
    }

    @Test fun xmltvKeepsOnlyTheWindowAndDecodesEntities() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <tv generator-info-name="x">
              <channel id="espn.us"><display-name>ESPN</display-name><display-name lang="en">US: ESPN FHD</display-name></channel>
              <channel id="wbz.us">
                <display-name>US: CBS (WBZ) Boston</display-name>
              </channel>
              <programme start="20261101120000 +0000" stop="20261101130000 +0000" channel="wbz.us"><title>News</title></programme>
              <programme start="20261101130000 +0000" stop="20261101163000 +0000" channel="wbz.us">
                <title lang="en">NFL Football</title><sub-title>Patriots at Bills</sub-title><desc>AFC East &amp; more</desc>
              </programme>
              <programme start="20261105130000 +0000" stop="20261105140000 +0000" channel="espn.us"><title>Too far ahead</title></programme>
            </tv>"""
        val from = Instant.parse("2026-11-01T13:00:00Z").toEpochMilli() - 90 * 60_000L
        val r = XmltvParser.parse(StringReader(xml), from, from + 36 * 3600_000L)
        val wbz = r.programmes["wbz.us"]!!
        assertEquals(listOf("News", "NFL Football: Patriots at Bills"), wbz.map { it.title })
        assertEquals("AFC East & more", wbz[1].description)
        assertNull(r.programmes["espn.us"])
        assertEquals("wbz.us", r.names[Names.tokens("US: CBS (WBZ) Boston").joinToString(" ")])
        assertEquals("espn.us", r.names["ESPN"])
    }

    @Test fun xmltvSurvivesTagsSplitAcrossReads() {
        val programme = """<programme start="20261101130000 +0000" stop="20261101140000 +0000" channel="c1"><title>Game</title></programme>"""
        val xml = "<tv>" + "x".repeat(65_530) + programme + "</tv>" // crosses the 64 KB read boundary
        val r = XmltvParser.parse(StringReader(xml), 0L, Long.MAX_VALUE)
        assertEquals("Game", r.programmes["c1"]!!.single().title)
    }

    @Test fun gzippedGuideIsDetected() {
        val bytes = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write("<tv></tv>".toByteArray()) } }.toByteArray()
        assertEquals("<tv></tv>", M3uSource.maybeGunzip(ByteArrayInputStream(bytes)).bufferedReader().readText())
        assertEquals("<tv/>", M3uSource.maybeGunzip(ByteArrayInputStream("<tv/>".toByteArray())).bufferedReader().readText())
    }
}
