package com.launcher.tv.alerts

import com.launcher.tv.data.HomeData
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.TeamInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAlertEngineTest {
    private class MemStore : FiredStore {
        val keys = mutableMapOf<String, Long>()
        override fun contains(key: String) = key in keys
        override fun put(key: String, at: Long) { keys[key] = at }
        override fun pruneBefore(time: Long) { keys.entries.removeIf { it.value < time } }
    }

    private val start = 1_800_000_000_000L
    private var now = start - 10 * 60_000L
    private val shown = mutableListOf<LiveAlert>()
    private var board: List<SportsEvent> = emptyList()

    private fun team(name: String, abbr: String, score: String? = null) = TeamInfo(name, abbr, score = score)
    private fun nfl(away: Int?, home: Int?, live: Boolean = true, finished: Boolean = false) = SportsEvent(
        "espn-1", "Patriots @ Bills", "NFL", start, live = live, finished = finished, broadcast = "CBS",
        status = "Q2 4:12", away = team("Patriots", "NE", away?.toString()), home = team("Bills", "BUF", home?.toString()),
    )
    private fun nba(away: Int, home: Int, period: Int) = SportsEvent(
        "espn-2", "Lakers @ Warriors", "NBA", start, live = true, period = period, status = "Q$period 2:00",
        away = team("Lakers", "LAL", "$away"), home = team("Warriors", "GSW", "$home"),
    )

    private fun engine(schedule: HomeData) = LiveAlertEngine(
        enabled = { true }, scoringOn = { true }, store = MemStore(),
        schedule = { schedule }, scoreboard = { board }, clock = { now }, show = { shown += it },
    )

    @Test fun footballGameFromTenMinutesOutToFinal() = runBlocking {
        val e = engine(HomeData(myTeams = listOf(nfl(null, null, live = false))))
        board = listOf(nfl(0, 0, live = false))
        e.tick()                                   // 10 min before: "starting in 10"
        e.tick()                                   // same again: no repeat
        now = start + 60_000; board = listOf(nfl(0, 0)); e.tick()        // kickoff
        now += 60_000; board = listOf(nfl(7, 0)); e.tick()               // TD Patriots
        now += 60_000; board = listOf(nfl(7, 3)); e.tick()               // FG Bills
        now += 60_000; board = listOf(nfl(7, 3)); e.tick()               // nothing new
        now += 60_000; board = listOf(nfl(7, 3, live = false, finished = true)); e.tick()
        assertEquals(listOf("STARTING IN 10 MIN", "KICKOFF", "TOUCHDOWN", "FIELD GOAL", "FINAL"), shown.map { it.kicker })
        assertEquals("Patriots score", shown[2].title)
        assertEquals("NE 7 – 3 BUF", shown[4].title)
    }

    @Test fun spoilerModeKeepsScoresOutOfAlerts() = runBlocking {
        val e = LiveAlertEngine(
            enabled = { true }, scoringOn = { true }, store = MemStore(),
            schedule = { HomeData(myTeams = listOf(nfl(null, null, live = false))) }, scoreboard = { board }, clock = { now },
            show = { shown += it }, hidesScore = { true },
        )
        now = start + 60_000; board = listOf(nfl(0, 0)); e.tick()
        now += 60_000; board = listOf(nfl(7, 0)); e.tick()
        now += 60_000; board = listOf(nfl(7, 3, live = false, finished = true)); e.tick()
        assertEquals(listOf("KICKOFF", "FINAL"), shown.map { it.kicker })
        assertEquals("Patriots @ Bills", shown[1].title)
        assertEquals("Score hidden (spoiler mode)", shown[1].detail)
    }

    @Test fun basketballOnlyAlertsOnCloseFinish() = runBlocking {
        now = start + 3600_000
        val e = engine(HomeData(myTeams = listOf(nba(0, 0, 1).copy(live = false))))
        board = listOf(nba(80, 70, 3)); e.tick()   // first look, game long under way: silent
        board = listOf(nba(84, 72, 3)); e.tick()   // baskets: silent
        board = listOf(nba(90, 87, 4)); e.tick()   // close in the 4th
        board = listOf(nba(92, 89, 4)); e.tick()   // still close: no repeat
        assertEquals(listOf("CLOSE GAME"), shown.map { it.kicker })
    }

    @Test fun nothingWhileYoureWatchingThatGame() = runBlocking {
        val e = engine(HomeData(myTeams = listOf(nfl(null, null, live = false))))
        NowPlaying.eventId = "espn-1"
        try {
            now = start + 60_000; board = listOf(nfl(0, 0)); e.tick()
            now += 60_000; board = listOf(nfl(7, 0)); e.tick()
            assertEquals(emptyList<String>(), shown.map { it.kicker })
        } finally {
            NowPlaying.eventId = null
        }
    }

    @Test fun fightNightStartsFromSchedule() = runBlocking {
        val ufc = SportsEvent("ufc-329", "UFC 329: Main Card", "UFC", start, broadcast = "Paramount+")
        val e = engine(HomeData(fights = listOf(ufc)))
        e.tick()
        now = start; e.tick()
        assertEquals(listOf("STARTING IN 10 MIN", "STARTING NOW"), shown.map { it.kicker })
    }
}
