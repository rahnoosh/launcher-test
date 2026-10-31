package com.launcher.tv.data.iptv

import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.TeamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFinderTest {
    private val pats = TeamInfo("Patriots", "NE", fullName = "New England Patriots")
    private val bills = TeamInfo("Bills", "BUF", fullName = "Buffalo Bills")
    private val game = SportsEvent("espn-1", "Patriots @ Bills", "NFL", startMillis = 1_000_000_000L, broadcast = "CBS", home = bills, away = pats)

    private val cats = mapOf("1" to "USA | ENTERTAINMENT", "2" to "NFL GAME PASS", "3" to "UK SPORTS")
    private fun ch(id: String, name: String, cat: String = "1") = IptvChannel(sourceId = "s", id = id, name = name, group = cats[cat])

    @Test fun eventChannelBeatsNetwork() {
        val list = ChannelFinder.score(game, listOf(
            ch("10", "US: CBS HD"),
            ch("11", "NFL 05: New England Patriots vs Buffalo Bills", "2"),
            ch("12", "US: Comedy Central"),
        ), emptyMap())
        assertEquals("11", list.first().channel.id)
        assertEquals(listOf("11", "10"), list.map { it.channel.id })
    }

    @Test fun networkMatchIgnoresLookalikes() {
        val espn = game.copy(broadcast = "ESPN")
        val ids = ChannelFinder.score(espn, listOf(
            ch("1", "US: ESPN FHD"),
            ch("2", "US: ESPN 2 HD"),
            ch("3", "US: ESPNEWS"),
            ch("4", "UK: ESPN", "3"),
        ), emptyMap()).map { it.channel.id }
        assertEquals("1", ids.first())
        assertTrue("ESPN 2 must not match ESPN", "2" !in ids)
        assertTrue("ESPNEWS is a different channel", "3" !in ids)
    }

    @Test fun espn2MatchesSpacedName() {
        val ids = ChannelFinder.score(game.copy(broadcast = "ESPN2"), listOf(ch("2", "US: ESPN 2 HD"), ch("1", "US: ESPN HD")), emptyMap())
            .map { it.channel.id }
        assertEquals(listOf("2"), ids)
    }

    @Test fun foxDoesNotMatchFoxNews() {
        val ids = ChannelFinder.score(game.copy(broadcast = "FOX"), listOf(ch("1", "US: FOX NEWS"), ch("2", "US: FOX HD")), emptyMap())
            .map { it.channel.id }
        assertEquals(listOf("2"), ids)
    }

    @Test fun aliasesMapEspnNames() {
        val sec = game.copy(broadcast = "SECN")
        val ids = ChannelFinder.score(sec, listOf(ch("1", "US: SEC Network FHD"), ch("2", "US: CBS")), emptyMap()).map { it.channel.id }
        assertEquals(listOf("1"), ids)
    }

    @Test fun rememberedPickWins() {
        val learned = mapOf("team:NE" to "s:20")
        val list = ChannelFinder.score(game, listOf(ch("10", "US: CBS HD"), ch("20", "US: CBS (WBZ) Boston")), learned)
        assertEquals("20", list.first().channel.id)
    }

    @Test fun ufcNumberedEventMatchesPpvChannel() {
        val ufc = SportsEvent("ufc", "UFC 329: Smith vs. Jones", "UFC", 0L, broadcast = "Paramount+")
        val ids = ChannelFinder.score(ufc, listOf(ch("1", "PPV 01: UFC 329"), ch("2", "UFC Fight Pass"), ch("3", "Paramount+ Live")), emptyMap())
            .map { it.channel.id }
        assertEquals("1", ids.first())
    }

    @Test fun guideConfirmsTheRightAffiliate() {
        val a = ChannelCandidate(ch("1", "US: CBS Los Angeles"), 40, "network")
        val b = ChannelCandidate(ch("2", "US: CBS Boston"), 40, "network")
        val start = game.startMillis
        val la = ChannelFinder.applyGuide(game, a, listOf(EpgEntry("NFL Football: Chiefs at Broncos", "", start - 600_000, start + 3 * 3600_000)))
        val bos = ChannelFinder.applyGuide(game, b, listOf(EpgEntry("NFL Football: Patriots at Bills", "", start - 600_000, start + 3 * 3600_000)))
        assertTrue(bos.score > la.score)
        assertEquals("NFL Football: Patriots at Bills", bos.epgTitle)
    }
}
