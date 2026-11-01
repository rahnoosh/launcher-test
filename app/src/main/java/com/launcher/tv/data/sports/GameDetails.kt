package com.launcher.tv.data.sports

import com.launcher.tv.data.Http
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.arr
import com.launcher.tv.data.obj
import com.launcher.tv.data.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LineScore(val labels: List<String>, val away: List<String>, val home: List<String>, val awayTotal: String?, val homeTotal: String?)
data class Leader(val category: String, val athlete: String, val value: String)
data class TeamLeaders(val team: String, val leaders: List<Leader>)
data class ScoringPlay(val period: String, val clock: String, val team: String?, val type: String?, val text: String, val score: String)
data class StatRow(val label: String, val away: String, val home: String)
data class Highlight(val title: String, val url: String, val thumbnail: String?)

/** What a game page shows. */
sealed interface GameDetail {
    data class Team(
        val status: String?,
        val line: LineScore?,
        val leaders: List<TeamLeaders>,
        val plays: List<ScoringPlay>,
        val stats: List<StatRow>,
        val awayRecord: String?,
        val homeRecord: String?,
        val awayScore: String?,
        val homeScore: String?,
        val venue: String?,
        val highlights: List<Highlight>,
    ) : GameDetail

    data class Session(val name: String, val startMillis: Long)
    data class Standing(val position: String, val name: String, val team: String, val value: String)

    data class F1(val sessions: List<Session>, val results: List<Standing>, val standings: List<Standing>, val circuit: String?) : GameDetail

    data class Bout(val weightClass: String?, val a: String, val b: String, val recordA: String?, val recordB: String?, val winner: Int?)
    data class Fight(val bouts: List<Bout>, val status: String?) : GameDetail
}

/** ESPN game summaries, Jolpica F1 weekends and ESPN UFC cards. */
object GameDetails {
    private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports"
    private const val JOLPICA = "https://api.jolpi.ca/ergast/f1"

    suspend fun load(event: SportsEvent): GameDetail? = when {
        event.home != null -> {
            val path = SportsRepository.espnPath(event.league) ?: return null
            val id = event.id.removePrefix("espn-")
            Http.getJson("$ESPN/$path/summary?event=$id").obj()?.let { parseSummary(it, event.league) }
        }
        event.league == "F1" -> loadF1(event)
        event.league == "UFC" -> loadUfc(event)
        else -> null
    }

    // ---------------- team sports ----------------

    fun parseSummary(root: JsonObject, league: String): GameDetail.Team {
        val comp = root.obj("header")?.arr("competitions")?.firstOrNull()?.obj()
        val status = comp?.obj("status")?.obj("type")?.let { it.str("detail") ?: it.str("shortDetail") }
        val competitors = comp?.arr("competitors").orEmpty().mapNotNull { it.obj() }
        val home = competitors.firstOrNull { it.str("homeAway") == "home" }
        val away = competitors.firstOrNull { it.str("homeAway") == "away" }
        val homeAbbr = home?.obj("team")?.str("abbreviation")
        val awayAbbr = away?.obj("team")?.str("abbreviation")

        fun lines(c: JsonObject?) = c?.arr("linescores").orEmpty().mapNotNull { it.obj()?.let { o -> o.str("displayValue") ?: o.str("value") } }
        fun record(c: JsonObject?) = (c?.arr("record") ?: c?.arr("records"))?.firstOrNull()?.obj()?.let { it.str("summary") ?: it.str("displayValue") }

        val awayLines = lines(away)
        val homeLines = lines(home)
        val periods = maxOf(awayLines.size, homeLines.size)
        val leagueInfo = Leagues.byLabel(league)
        val regulation = if (leagueInfo?.kind == SportKind.BASEBALL) Int.MAX_VALUE else leagueInfo?.periods ?: 4
        val line = if (periods == 0) null else LineScore(
            labels = (1..periods).map { if (it > regulation) (if (it == regulation + 1) "OT" else "OT${it - regulation}") else "$it" },
            away = awayLines, home = homeLines,
            awayTotal = away?.str("score"), homeTotal = home?.str("score"),
        )

        val leaders = root.arr("leaders").orEmpty().mapNotNull { t ->
            val o = t.obj() ?: return@mapNotNull null
            val abbr = o.obj("team")?.str("abbreviation") ?: return@mapNotNull null
            val cats = o.arr("leaders").orEmpty().mapNotNull { c ->
                val co = c.obj() ?: return@mapNotNull null
                val top = co.arr("leaders")?.firstOrNull()?.obj() ?: return@mapNotNull null
                val athlete = top.obj("athlete")?.let { it.str("shortName") ?: it.str("displayName") } ?: return@mapNotNull null
                Leader(co.str("displayName") ?: co.str("name").orEmpty(), athlete, top.str("displayValue").orEmpty())
            }.take(3)
            if (cats.isEmpty()) null else TeamLeaders(abbr, cats)
        }

        val playsSource = root.arr("scoringPlays")
            ?: root.arr("plays")?.filter { (it.obj()?.get("scoringPlay") as? JsonPrimitive)?.booleanOrNull == true }
            // Soccer: goals are in keyEvents.
            ?: root.arr("keyEvents")?.filter { it.obj()?.obj("type")?.str("text")?.contains("Goal", ignoreCase = true) == true }
        val plays = playsSource.orEmpty().mapNotNull { p ->
            val o = p.obj() ?: return@mapNotNull null
            val period = o.obj("period")?.str("number")?.toIntOrNull()
            ScoringPlay(
                period = periodLabel(period, league),
                clock = o.obj("clock")?.str("displayValue").orEmpty(),
                team = o.obj("team")?.let { it.str("abbreviation") ?: it.str("displayName") },
                type = o.obj("type")?.str("text"),
                text = o.str("text").orEmpty(),
                score = if (o.str("awayScore") == null && o.str("homeScore") == null) "" else "${o.str("awayScore") ?: "-"}–${o.str("homeScore") ?: "-"}",
            )
        }.reversed().take(8)

        val teams = root.obj("boxscore")?.arr("teams").orEmpty().mapNotNull { it.obj() }
        fun statsOf(abbr: String?) = teams.firstOrNull { it.obj("team")?.str("abbreviation") == abbr }
            ?.arr("statistics").orEmpty().mapNotNull { s -> s.obj()?.takeIf { it["stats"] == null } }
            .mapNotNull { s -> (s.str("label") ?: s.str("name"))?.let { it to s.str("displayValue").orEmpty() } }
        val awayStats = statsOf(awayAbbr).toMap()
        val homeStats = statsOf(homeAbbr)
        val wanted = PRIORITY[league].orEmpty()
        val stats = homeStats.map { it.first }.filter { it in awayStats }
            .sortedBy { label -> wanted.indexOf(label).let { if (it < 0) 99 else it } }
            .take(8)
            .map { label -> StatRow(label, awayStats[label].orEmpty(), homeStats.first { it.first == label }.second) }

        val highlights = root.arr("videos").orEmpty().mapNotNull { v ->
            val o = v.obj() ?: return@mapNotNull null
            val links = o.obj("links")
            val url = links?.obj("source")?.let { it.obj("HD")?.str("href") ?: it.str("href") }
                ?: links?.obj("mobile")?.obj("source")?.str("href")
                ?: return@mapNotNull null
            Highlight(o.str("headline") ?: "Highlights", url, o.str("thumbnail"))
        }

        return GameDetail.Team(
            status = status, line = line, leaders = leaders, plays = plays, stats = stats,
            awayRecord = record(away), homeRecord = record(home),
            awayScore = away?.str("score"), homeScore = home?.str("score"),
            venue = root.obj("gameInfo")?.obj("venue")?.str("fullName"),
            highlights = highlights,
        )
    }

    private val PRIORITY = mapOf(
        "NFL" to listOf("Total Yards", "Passing", "Rushing", "Turnovers", "1st Downs", "3rd down efficiency", "Possession", "Penalties"),
        "College Football" to listOf("Total Yards", "Passing", "Rushing", "Turnovers", "1st Downs", "3rd down efficiency", "Possession", "Penalties"),
        "NBA" to listOf("Field Goal %", "Three Point %", "Rebounds", "Assists", "Turnovers", "Steals", "Blocks", "Free Throw %"),
    )

    private fun periodLabel(n: Int?, league: String): String {
        if (n == null) return ""
        val l = Leagues.byLabel(league)
        return when (l?.kind) {
            SportKind.BASEBALL -> "Inn $n"
            SportKind.HOCKEY -> if (n > 3) "OT" else "P$n"
            SportKind.SOCCER -> when (n) { 1 -> "1st half"; 2 -> "2nd half"; else -> "Extra time" }
            SportKind.BASKETBALL -> if (n > l.periods) "OT" else if (l.periods == 2) "H$n" else "Q$n"
            else -> if (n > 4) "OT" else "Q$n"
        }
    }

    // ---------------- F1 ----------------

    private suspend fun loadF1(event: SportsEvent): GameDetail.F1? {
        val (season, round) = event.id.removePrefix("f1-").split("-").takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
        val race = Http.getJson("$JOLPICA/$season/$round.json").obj()?.let { raceOf(it) }
        val results = runCatching { Http.getJson("$JOLPICA/$season/$round/results.json").obj()?.let { parseF1Results(it) } }.getOrNull().orEmpty()
        val standings = runCatching { Http.getJson("$JOLPICA/$season/driverStandings.json").obj()?.let { parseF1Standings(it) } }.getOrNull().orEmpty()
        return GameDetail.F1(race?.let { parseF1Sessions(it) }.orEmpty(), results, standings, race?.obj("Circuit")?.str("circuitName"))
    }

    private fun raceOf(root: JsonObject) = root.obj("MRData")?.obj("RaceTable")?.arr("Races")?.firstOrNull()?.obj()

    fun parseF1Sessions(race: JsonObject): List<GameDetail.Session> {
        val names = listOf(
            "FirstPractice" to "Practice 1", "SecondPractice" to "Practice 2", "ThirdPractice" to "Practice 3",
            "SprintQualifying" to "Sprint qualifying", "SprintShootout" to "Sprint shootout", "Sprint" to "Sprint",
            "Qualifying" to "Qualifying",
        )
        val sessions = names.mapNotNull { (key, label) ->
            val s = race.obj(key) ?: return@mapNotNull null
            dateTime(s.str("date"), s.str("time"))?.let { GameDetail.Session(label, it) }
        } + listOfNotNull(dateTime(race.str("date"), race.str("time"))?.let { GameDetail.Session("Race", it) })
        return sessions.sortedBy { it.startMillis }
    }

    fun parseF1Results(root: JsonObject): List<GameDetail.Standing> =
        raceOf(root)?.arr("Results").orEmpty().mapNotNull { r ->
            val o = r.obj() ?: return@mapNotNull null
            GameDetail.Standing(
                position = o.str("position") ?: return@mapNotNull null,
                name = driver(o.obj("Driver")),
                team = o.obj("Constructor")?.str("name").orEmpty(),
                value = o.obj("Time")?.str("time") ?: o.str("status").orEmpty(),
            )
        }.take(10)

    fun parseF1Standings(root: JsonObject): List<GameDetail.Standing> =
        root.obj("MRData")?.obj("StandingsTable")?.arr("StandingsLists")?.firstOrNull()?.obj()?.arr("DriverStandings").orEmpty().mapNotNull { r ->
            val o = r.obj() ?: return@mapNotNull null
            GameDetail.Standing(
                position = o.str("position") ?: o.str("positionText") ?: return@mapNotNull null,
                name = driver(o.obj("Driver")),
                team = o.arr("Constructors")?.firstOrNull()?.obj()?.str("name").orEmpty(),
                value = "${o.str("points") ?: "0"} pts",
            )
        }.take(10)

    private fun driver(d: JsonObject?): String = listOfNotNull(d?.str("givenName"), d?.str("familyName")).joinToString(" ")

    private fun dateTime(date: String?, time: String?): Long? {
        date ?: return null
        return runCatching { java.time.OffsetDateTime.parse("${date}T${time ?: "12:00:00Z"}").toInstant().toEpochMilli() }.getOrNull()
    }

    // ---------------- UFC ----------------

    private suspend fun loadUfc(event: SportsEvent): GameDetail.Fight? {
        val day = Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.of("America/New_York")).toLocalDate()
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        val range = "${day.minusDays(1).format(fmt)}-${day.plusDays(1).format(fmt)}"
        val root = Http.getJson("$ESPN/mma/ufc/scoreboard?dates=$range").obj() ?: return null
        val id = event.id.removePrefix("espn-")
        val ev = root.arr("events").orEmpty().mapNotNull { it.obj() }.firstOrNull { it.str("id") == id }
            ?: root.arr("events").orEmpty().mapNotNull { it.obj() }.firstOrNull { (it.str("name") ?: "").startsWith(event.title.substringBefore(":")) }
            ?: return null
        return parseUfcEvent(ev)
    }

    fun parseUfcEvent(ev: JsonObject): GameDetail.Fight {
        val bouts = ev.arr("competitions").orEmpty().mapNotNull { c ->
            val o = c.obj() ?: return@mapNotNull null
            val fighters = o.arr("competitors").orEmpty().mapNotNull { it.obj() }
            if (fighters.size < 2) return@mapNotNull null
            fun name(f: JsonObject) = f.obj("athlete")?.let { it.str("displayName") ?: it.str("fullName") } ?: "TBA"
            fun rec(f: JsonObject) = f.arr("records")?.firstOrNull()?.obj()?.str("summary")
            val winner = fighters.indexOfFirst { (it["winner"] as? JsonPrimitive)?.booleanOrNull == true }.takeIf { it >= 0 }
            GameDetail.Bout(
                weightClass = o.obj("type")?.let { it.str("text") ?: it.str("abbreviation") },
                a = name(fighters[0]), b = name(fighters[1]),
                recordA = rec(fighters[0]), recordB = rec(fighters[1]),
                winner = winner,
            )
        }.reversed() // ESPN lists the early prelims first; main event on top
        return GameDetail.Fight(bouts, ev.obj("status")?.obj("type")?.str("detail"))
    }
}
