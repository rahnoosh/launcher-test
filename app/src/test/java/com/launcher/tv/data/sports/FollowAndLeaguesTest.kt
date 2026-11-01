package com.launcher.tv.data.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FollowAndLeaguesTest {
    @Test fun defaultsAreYourTeams() {
        val cfg = FollowConfig()
        assertEquals(listOf("ALA", "NE", "LAL", "LAD"), cfg.teams.map { it.abbr })
        assertTrue(cfg.follows(EventFollow.F1))
        assertFalse(cfg.follows(EventFollow.GOLF))
        assertTrue(cfg.isFollowed("nfl", "17"))
        // Every followed team's league exists
        cfg.teams.forEach { assertTrue(it.league, Leagues.byKey(it.league) != null) }
    }

    @Test fun configRoundTrips() {
        val cfg = FollowConfig(
            teams = listOf(FollowedTeam("epl", "359", "Arsenal", "ARS", "EF0107")),
            events = setOf(EventFollow.GOLF.key), gamesPerTeam = 3,
        )
        assertEquals(cfg, FollowStore.decode(FollowStore.encode(cfg)))
        assertEquals(null, FollowStore.decode("garbage"))
    }

    @Test fun leagueLookups() {
        assertEquals("hockey/nhl", Leagues.byLabel("NHL")!!.espnPath)
        assertEquals(SportKind.SOCCER, Leagues.byKey("epl")!!.kind)
        assertEquals("soccer/usa.1", SportsRepository.espnPath("MLS"))
        assertEquals(null, SportsRepository.espnPath("UFC"))
    }

    @Test fun parsesEspnTeamList() {
        val root = Json.parseToJsonElement("""{"sports":[{"leagues":[{"teams":[
            {"team":{"id":"17","abbreviation":"NE","displayName":"New England Patriots","color":"002244","alternateColor":"c60c30","logos":[{"href":"https://a.espncdn.com/ne.png"}]}},
            {"team":{"id":"2","abbreviation":"BUF","displayName":"Buffalo Bills","color":"00338d"}}
        ]}]}]}""").jsonObject
        val teams = SportsRepository.parseTeams(root, "nfl")
        assertEquals(listOf("Buffalo Bills", "New England Patriots"), teams.map { it.name }) // sorted
        val ne = teams[1]
        assertEquals("nfl:17", ne.key)
        assertEquals("https://a.espncdn.com/ne.png", ne.logo)
        assertEquals("002244", ne.color)
    }

    @Test fun golfMajorsAndGrandSlamsFromTheCalendar() {
        val now = Instant.parse("2027-04-09T12:00:00Z").toEpochMilli()
        val golf = Json.parseToJsonElement("""{"leagues":[{"calendar":[
            {"label":"The Sentry","startDate":"2027-01-02T08:00Z","endDate":"2027-01-05T08:00Z"},
            {"label":"Masters Tournament","startDate":"2027-04-08T07:00Z","endDate":"2027-04-11T07:00Z"},
            {"label":"PGA Championship","startDate":"2027-05-20T07:00Z","endDate":"2027-05-23T07:00Z"},
            {"label":"U.S. Open","startDate":"2027-06-17T07:00Z","endDate":"2027-06-20T07:00Z"}
        ]}]}""").jsonObject
        val majors = Regex("""(?i)\b(Masters Tournament|PGA Championship|U\.S\. Open|The Open|Open Championship)\b""")
        val g = SportsRepository.parseTournaments(golf, "Golf", majors, now)
        assertEquals(listOf("Masters Tournament", "PGA Championship"), g.map { it.title }) // US Open > 60 days away
        assertTrue(g[0].live)
        assertFalse(g[1].live)

        val tennis = Json.parseToJsonElement("""{"events":[{"name":"Wimbledon","date":"2027-04-01T10:00Z","endDate":"2027-04-14T18:00Z"}],
            "leagues":[{"calendar":[{"label":"Miami Open","startDate":"2027-03-20T00:00Z","endDate":"2027-04-02T00:00Z"}]}]}""").jsonObject
        val slams = Regex("""(?i)\b(Australian Open|Roland[- ]Garros|French Open|Wimbledon|US Open|U\.S\. Open)\b""")
        assertEquals(listOf("Wimbledon"), SportsRepository.parseTournaments(tennis, "Tennis", slams, now).map { it.title })
    }
}
