package com.launcher.tv.player

import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.iptv.IptvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    private fun game(league: String, startedAgo: Long, finished: Boolean = true) =
        SportsEvent(id = "g", title = "Patriots @ Bills", league = league, startMillis = now - startedAgo, finished = finished)

    @Test
    fun offeredForRecentFinishedGames() {
        assertTrue(Replay.offered(game("NFL", 2 * day), now))
        assertFalse(Replay.offered(game("NFL", 2 * hour, finished = false), now)) // live: that's Start over
        assertFalse(Replay.offered(game("NFL", 20 * day), now)) // no provider keeps it that long
        assertFalse(Replay.offered(game("Golf", 2 * day), now)) // a four-day tournament isn't one recording
    }

    @Test
    fun lengthBySport() {
        assertEquals(240, Replay.minutes(game("NFL", day)))
        assertEquals(240, Replay.minutes(game("College Football", day)))
        assertEquals(180, Replay.minutes(game("NBA", day)))
        assertEquals(150, Replay.minutes(game("Premier League", day)))
        assertEquals(330, Replay.minutes(game("UFC", day)))
        assertEquals(150, Replay.minutes(game("F1", day)))
        assertNull(Replay.minutes(game("Tennis", day)))
    }

    @Test
    fun channelMustStillHaveTheRecording() {
        val e = game("NBA", 3 * day)
        assertFalse(Replay.channelHasIt(IptvChannel("p", "1", "ESPN"), e, now)) // no catch-up at all
        assertTrue(Replay.channelHasIt(IptvChannel("p", "1", "ESPN", archive = true), e, now)) // length unknown: try
        assertTrue(Replay.channelHasIt(IptvChannel("p", "1", "ESPN", archive = true, archiveDays = 7), e, now))
        assertFalse(Replay.channelHasIt(IptvChannel("p", "1", "ESPN", archive = true, archiveDays = 2), e, now))
    }

    @Test
    fun windowStartsBeforeKickoffAndStopsAtNow() {
        val (from, minutes) = Replay.window(game("NBA", day), now)!!
        assertEquals(now - day - 5 * 60_000L, from)
        assertEquals(180, minutes)
        // Finished an hour after a 1-hour-ago start can't ask for 3 hours of recording.
        assertEquals(65, Replay.window(game("NBA", hour), now)!!.second)
        assertNull(Replay.window(game("NBA", 0), now))
    }
}
