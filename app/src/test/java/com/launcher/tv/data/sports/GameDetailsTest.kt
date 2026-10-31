package com.launcher.tv.data.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDetailsTest {
    private val nflSummary = """{
      "header": {"competitions": [{
        "status": {"type": {"state": "in", "detail": "3rd Quarter - 4:12", "shortDetail": "Q3 4:12"}},
        "competitors": [
          {"homeAway": "home", "score": "10", "team": {"abbreviation": "BUF", "displayName": "Buffalo Bills"},
           "record": [{"type": "total", "summary": "6-2"}], "linescores": [{"displayValue": "7"}, {"displayValue": "3"}, {"displayValue": "0"}]},
          {"homeAway": "away", "score": "14", "team": {"abbreviation": "NE", "displayName": "New England Patriots"},
           "record": [{"type": "total", "summary": "5-3"}], "linescores": [{"displayValue": "0"}, {"displayValue": "7"}, {"displayValue": "7"}]}
        ]}]},
      "leaders": [
        {"team": {"abbreviation": "NE"}, "leaders": [
          {"name": "passingYards", "displayName": "Passing Yards", "leaders": [{"displayValue": "18/24, 212 YDS, 2 TD", "athlete": {"displayName": "Drake Maye", "shortName": "D. Maye"}}]},
          {"name": "rushingYards", "displayName": "Rushing Yards", "leaders": [{"displayValue": "14 CAR, 66 YDS", "athlete": {"displayName": "Rhamondre Stevenson"}}]}]},
        {"team": {"abbreviation": "BUF"}, "leaders": [
          {"name": "passingYards", "displayName": "Passing Yards", "leaders": [{"displayValue": "15/22, 180 YDS, 1 TD", "athlete": {"displayName": "Josh Allen", "shortName": "J. Allen"}}]}]}
      ],
      "scoringPlays": [
        {"type": {"text": "Passing Touchdown"}, "text": "James Cook 3 Yd Run (Bass Kick)", "awayScore": 0, "homeScore": 7, "period": {"number": 1}, "clock": {"displayValue": "8:21"}, "team": {"abbreviation": "BUF"}},
        {"type": {"text": "Field Goal Good"}, "text": "Tyler Bass 41 Yd Field Goal", "awayScore": 0, "homeScore": 10, "period": {"number": 2}, "clock": {"displayValue": "11:02"}, "team": {"abbreviation": "BUF"}},
        {"type": {"text": "Passing Touchdown"}, "text": "Drake Maye Pass to Hunter Henry for 12 Yds", "awayScore": 7, "homeScore": 10, "period": {"number": 2}, "clock": {"displayValue": "0:31"}, "team": {"abbreviation": "NE"}},
        {"type": {"text": "Rushing Touchdown"}, "text": "Rhamondre Stevenson 5 Yd Run", "awayScore": 14, "homeScore": 10, "period": {"number": 3}, "clock": {"displayValue": "6:40"}, "team": {"abbreviation": "NE"}}
      ],
      "boxscore": {"teams": [
        {"team": {"abbreviation": "NE"}, "statistics": [{"name": "firstDowns", "label": "1st Downs", "displayValue": "15"}, {"name": "totalYards", "label": "Total Yards", "displayValue": "301"}, {"name": "turnovers", "label": "Turnovers", "displayValue": "0"}]},
        {"team": {"abbreviation": "BUF"}, "statistics": [{"name": "firstDowns", "label": "1st Downs", "displayValue": "12"}, {"name": "totalYards", "label": "Total Yards", "displayValue": "244"}, {"name": "turnovers", "label": "Turnovers", "displayValue": "2"}]}
      ]},
      "gameInfo": {"venue": {"fullName": "Highmark Stadium"}},
      "videos": [{"headline": "Maye finds Henry for the score", "thumbnail": "http://img/1.jpg", "links": {"source": {"href": "https://media.video-cdn.espn.com/clip.mp4", "HD": {"href": "https://media.video-cdn.espn.com/clip_HD.mp4"}}}}]
    }"""

    @Test fun footballSummary() {
        val d = GameDetails.parseSummary(Json.parseToJsonElement(nflSummary).jsonObject, "NFL")
        assertEquals("3rd Quarter - 4:12", d.status)
        assertEquals(listOf("1", "2", "3"), d.line!!.labels)
        assertEquals(listOf("0", "7", "7"), d.line!!.away)
        assertEquals("14", d.line!!.awayTotal)
        assertEquals("5-3", d.awayRecord)
        assertEquals("6-2", d.homeRecord)
        // Most recent first
        assertEquals(listOf("14–10", "7–10", "0–10", "0–7"), d.plays.map { it.score })
        assertEquals("Q3", d.plays.first().period)
        assertEquals("NE", d.plays.first().team)
        // Stats ordered by importance, aligned away/home
        assertEquals("Total Yards", d.stats.first().label)
        assertEquals("301", d.stats.first().away)
        assertEquals("244", d.stats.first().home)
        assertEquals("D. Maye", d.leaders.first { it.team == "NE" }.leaders.first().athlete)
        assertEquals("https://media.video-cdn.espn.com/clip_HD.mp4", d.highlights.single().url)
        assertEquals("Highmark Stadium", d.venue)
    }

    @Test fun overtimeLabels() {
        val json = """{"header":{"competitions":[{"competitors":[
            {"homeAway":"home","score":"110","linescores":[{"displayValue":"25"},{"displayValue":"25"},{"displayValue":"25"},{"displayValue":"25"},{"displayValue":"10"}]},
            {"homeAway":"away","score":"108","linescores":[{"displayValue":"25"},{"displayValue":"25"},{"displayValue":"25"},{"displayValue":"25"},{"displayValue":"8"}]}]}]}}"""
        val d = GameDetails.parseSummary(Json.parseToJsonElement(json).jsonObject, "NBA")
        assertEquals(listOf("1", "2", "3", "4", "OT"), d.line!!.labels)
        assertTrue(d.plays.isEmpty())
    }

    @Test fun f1WeekendResultsAndStandings() {
        val race = Json.parseToJsonElement("""{"season":"2026","round":"21","raceName":"São Paulo Grand Prix",
            "Circuit":{"circuitName":"Autódromo José Carlos Pace"},
            "date":"2026-11-08","time":"17:00:00Z",
            "FirstPractice":{"date":"2026-11-06","time":"14:30:00Z"},
            "SprintQualifying":{"date":"2026-11-06","time":"18:30:00Z"},
            "Sprint":{"date":"2026-11-07","time":"14:00:00Z"},
            "Qualifying":{"date":"2026-11-07","time":"18:00:00Z"}}""").jsonObject
        assertEquals(listOf("Practice 1", "Sprint qualifying", "Sprint", "Qualifying", "Race"), GameDetails.parseF1Sessions(race).map { it.name })

        val results = Json.parseToJsonElement("""{"MRData":{"RaceTable":{"Races":[{"Results":[
            {"position":"1","points":"25","Driver":{"givenName":"Lando","familyName":"Norris"},"Constructor":{"name":"McLaren"},"Time":{"time":"1:32:01.123"},"status":"Finished"},
            {"position":"2","points":"18","Driver":{"givenName":"Max","familyName":"Verstappen"},"Constructor":{"name":"Red Bull"},"Time":{"time":"+2.345"},"status":"Finished"},
            {"position":"3","Driver":{"givenName":"Oscar","familyName":"Piastri"},"Constructor":{"name":"McLaren"},"status":"+1 Lap"}]}]}}}""").jsonObject
        val r = GameDetails.parseF1Results(results)
        assertEquals(listOf("Lando Norris", "Max Verstappen", "Oscar Piastri"), r.map { it.name })
        assertEquals(listOf("1:32:01.123", "+2.345", "+1 Lap"), r.map { it.value })

        val standings = Json.parseToJsonElement("""{"MRData":{"StandingsTable":{"StandingsLists":[{"DriverStandings":[
            {"position":"1","points":"390","Driver":{"givenName":"Lando","familyName":"Norris"},"Constructors":[{"name":"McLaren"}]}]}]}}}""").jsonObject
        assertEquals("390 pts", GameDetails.parseF1Standings(standings).single().value)
    }

    @Test fun ufcCardMainEventFirst() {
        val ev = Json.parseToJsonElement("""{"id":"600","name":"UFC 329: Smith vs. Jones","status":{"type":{"detail":"Final"}},"competitions":[
            {"type":{"text":"Flyweight"},"competitors":[{"athlete":{"displayName":"Early Prelim A"}},{"athlete":{"displayName":"Early Prelim B"}}]},
            {"type":{"text":"Lightweight"},"competitors":[{"athlete":{"displayName":"Smith"},"records":[{"summary":"22-3-0"}],"winner":true},{"athlete":{"displayName":"Jones"},"records":[{"summary":"19-1-0"}],"winner":false}]}
        ]}""").jsonObject
        val f = GameDetails.parseUfcEvent(ev)
        assertEquals("Smith", f.bouts.first().a)
        assertEquals(0, f.bouts.first().winner)
        assertEquals("22-3-0", f.bouts.first().recordA)
        assertEquals("Lightweight", f.bouts.first().weightClass)
    }
}
