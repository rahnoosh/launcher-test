package com.launcher.tv.data.sports

import com.launcher.tv.BuildConfig
import com.launcher.tv.data.Http
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.arr
import com.launcher.tv.data.obj
import com.launcher.tv.data.str
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Sports schedules from free, keyless (or free-key) APIs:
 *  - ESPN's public site API: NFL, college football, NBA, MLB, UFC
 *  - TheSportsDB: boxing
 *  - Jolpica (Ergast successor): Formula 1
 *
 * Every public function returns null on failure so the caller can keep the cached copy.
 */
object SportsRepository {
    private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports"
    private const val LIVE_GRACE_MS = 5 * 3600_000L  // keep games visible for 5h after kickoff

    // ---------------- My teams ----------------

    suspend fun myTeams(): List<SportsEvent>? = runCatchingNull {
        coroutineScope {
            SportsConfig.myTeams.map { team ->
                async {
                    // Regular season (default) + postseason (seasontype=3), merged.
                    val urls = listOf(
                        "$ESPN/${team.espnPath}/teams/${team.espnId}/schedule",
                        "$ESPN/${team.espnPath}/teams/${team.espnId}/schedule?seasontype=3",
                    )
                    urls.map { url ->
                        async { runCatching { Http.getJson(url).obj()?.arr("events") }.getOrNull() }
                    }.awaitAll()
                        .flatMap { it.orEmpty() }
                        .mapNotNull { parseEspnEvent(it, team.league) }
                        .distinctBy { it.id }
                        .filter { it.isUpcomingOrLive() }
                        .sortedBy { it.startMillis }
                        .take(SportsConfig.gamesPerTeam)
                }
            }.awaitAll().flatten().sortedBy { it.startMillis }
        }
    }

    // ---------------- NFL this week ----------------

    suspend fun nflThisWeek(): List<SportsEvent>? = runCatchingNull {
        if (!SportsConfig.showAllNfl) return@runCatchingNull emptyList()
        Http.getJson("$ESPN/football/nfl/scoreboard").obj()?.arr("events").orEmpty()
            .mapNotNull { parseEspnEvent(it, "NFL") }
            .filter { it.isUpcomingOrLive() }
            .sortedBy { it.startMillis }
    }

    // ---------------- Fights: UFC + boxing ----------------

    suspend fun fights(): List<SportsEvent>? = coroutineScope {
        val ufc = async { runCatchingNull { ufcBigEvents() } }
        val boxing = async { runCatchingNull { boxing() } }
        val u = ufc.await()
        val b = boxing.await()
        if (u == null && b == null) null
        else (u.orEmpty() + b.orEmpty()).sortedBy { it.startMillis }
    }

    private suspend fun ufcBigEvents(): List<SportsEvent> {
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        val today = LocalDate.now()
        val range = "${today.minusDays(1).format(fmt)}-${today.plusDays(120).format(fmt)}"
        val root = Http.getJson("$ESPN/mma/ufc/scoreboard?dates=$range").obj()
        val events = root?.arr("events").orEmpty()
            .mapNotNull { parseEspnEvent(it, "UFC", useEventName = true) }
        // Fallback: the league calendar lists upcoming cards even when "events" only holds the next one.
        val calendar = root?.arr("leagues")?.firstOrNull()?.obj()?.arr("calendar").orEmpty()
            .mapNotNull { entry ->
                val o = entry.obj() ?: return@mapNotNull null
                val label = o.str("label") ?: return@mapNotNull null
                val start = parseTime(o.str("startDate")) ?: return@mapNotNull null
                SportsEvent(id = "ufc-cal-$label", title = label, league = "UFC", startMillis = start)
            }
        return (events + calendar)
            .filter { SportsConfig.ufcBigEventPattern.containsMatchIn(it.title) && it.isUpcomingOrLive() }
            .distinctBy { SportsConfig.ufcBigEventPattern.find(it.title)?.value ?: it.id }
    }

    private suspend fun boxing(): List<SportsEvent> {
        val key = BuildConfig.SPORTSDB_API_KEY.ifBlank { "123" }
        val url = "https://www.thesportsdb.com/api/v1/json/$key/eventsnextleague.php?id=${SportsConfig.sportsDbBoxingLeague}"
        return Http.getJson(url).obj()?.arr("events").orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val start = parseSportsDbTime(o) ?: return@mapNotNull null
            SportsEvent(
                id = "box-${o.str("idEvent")}",
                title = o.str("strEvent") ?: return@mapNotNull null,
                league = "Boxing",
                startMillis = start,
                imageUrl = o.str("strThumb") ?: o.str("strPoster"),
                venue = listOfNotNull(o.str("strVenue"), o.str("strCity")).joinToString(", ").ifBlank { null },
            )
        }.filter { it.isUpcomingOrLive() }
    }

    // ---------------- Formula 1 ----------------

    suspend fun f1(): List<SportsEvent>? = runCatchingNull {
        if (!SportsConfig.showF1) return@runCatchingNull emptyList()
        val races = Http.getJson("https://api.jolpi.ca/ergast/f1/current/races.json").obj()
            ?.obj("MRData")?.obj("RaceTable")?.arr("Races").orEmpty()
        races.mapNotNull { r ->
            val o = r.obj() ?: return@mapNotNull null
            val start = parseDateAndTime(o.str("date"), o.str("time")) ?: return@mapNotNull null
            val circuit = o.obj("Circuit")
            val loc = circuit?.obj("Location")
            val quali = o.obj("Qualifying")?.let { q -> parseDateAndTime(q.str("date"), q.str("time")) }
            SportsEvent(
                id = "f1-${o.str("season")}-${o.str("round")}",
                title = o.str("raceName") ?: "Grand Prix",
                league = "F1",
                startMillis = start,
                status = quali?.let { "Qualifying ${formatShort(it)}" },
                venue = listOfNotNull(circuit?.str("circuitName"), loc?.str("country")).joinToString(" · "),
            )
        }.filter { it.isUpcomingOrLive() }.sortedBy { it.startMillis }.take(3)
    }

    // ---------------- ESPN parsing ----------------

    private fun parseEspnEvent(el: JsonElement, league: String, useEventName: Boolean = false): SportsEvent? {
        val ev = el.obj() ?: return null
        val id = ev.str("id") ?: return null
        val start = parseTime(ev.str("date")) ?: return null
        val comp = ev.arr("competitions")?.firstOrNull()?.obj()
        val statusType = (comp?.obj("status") ?: ev.obj("status"))?.obj("type")
        val state = statusType?.str("state")             // "pre", "in", "post"
        if (state == "post") return null

        val competitors = comp?.arr("competitors").orEmpty().mapNotNull { it.obj() }
        val home = competitors.firstOrNull { it.str("homeAway") == "home" }
        val away = competitors.firstOrNull { it.str("homeAway") == "away" }
        fun name(c: JsonObject?) = c?.obj("team")?.let { it.str("shortDisplayName") ?: it.str("displayName") }
        fun logo(c: JsonObject?) = c?.obj("team")?.let { t ->
            t.str("logo") ?: t.arr("logos")?.firstOrNull()?.obj()?.str("href")
        }
        fun score(c: JsonObject?): String? = when (val s = c?.get("score")) {
            is JsonPrimitive -> s.contentOrNull
            is JsonObject -> s.str("displayValue")
            else -> null
        }

        val title = if (!useEventName && home != null && away != null) "${name(away)} @ ${name(home)}"
        else ev.str("name") ?: ev.str("shortName") ?: return null

        val live = state == "in"
        val status = if (live) {
            val sc = if (home != null && away != null) "${score(away) ?: 0}-${score(home) ?: 0} " else ""
            sc + (statusType?.str("shortDetail") ?: "Live")
        } else null

        return SportsEvent(
            id = "espn-$id",
            title = title,
            league = league,
            startMillis = start,
            live = live,
            status = status,
            broadcast = espnBroadcasts(comp),
            imageUrl = logo(home) ?: logo(away),
            venue = comp?.obj("venue")?.str("fullName"),
        )
    }

    /** ESPN uses two broadcast shapes depending on the endpoint; accept both. */
    private fun espnBroadcasts(comp: JsonObject?): String? {
        val names = comp?.arr("broadcasts").orEmpty().flatMap { b ->
            val o = b.obj() ?: return@flatMap emptyList()
            listOfNotNull(o.obj("media")?.str("shortName")) +
                o.arr("names").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.distinct()
        return names.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    // ---------------- helpers ----------------

    private fun SportsEvent.isUpcomingOrLive(): Boolean {
        val now = System.currentTimeMillis()
        return live || startMillis > now - (if (league == "F1") 3 * 3600_000L else LIVE_GRACE_MS) &&
            startMillis < now + 150L * 86_400_000L
    }

    /** ESPN dates look like "2026-11-02T18:00Z" (no seconds), which OffsetDateTime accepts. */
    private fun parseTime(s: String?): Long? = s?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun parseDateAndTime(date: String?, time: String?): Long? {
        date ?: return null
        return parseTime("${date}T${time ?: "12:00:00Z"}")
            ?: runCatching { LocalDate.parse(date).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /** TheSportsDB times are UTC without an offset. */
    private fun parseSportsDbTime(o: JsonObject): Long? {
        o.str("strTimestamp")?.let { ts ->
            runCatching { LocalDateTime.parse(ts.removeSuffix("Z").substringBefore("+")).toInstant(ZoneOffset.UTC).toEpochMilli() }
                .getOrNull()?.let { return it }
        }
        val date = o.str("dateEvent") ?: return null
        val time = o.str("strTime")?.takeIf { it.length >= 5 }?.substring(0, 5) ?: "20:00"
        return runCatching { LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    private fun formatShort(millis: Long): String =
        DateTimeFormatter.ofPattern("EEE h:mm a")
            .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

    private inline fun <T> runCatchingNull(block: () -> T): T? =
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
}
