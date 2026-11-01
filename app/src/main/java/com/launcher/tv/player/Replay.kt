package com.launcher.tv.player

import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.iptv.IptvChannel
import com.launcher.tv.data.sports.Leagues
import com.launcher.tv.data.sports.SportKind

/**
 * Full-game replays from your provider's catch-up (the recordings TiviMate shows as "archive"):
 * which finished games to offer, which channels still have them, and how long a recording to ask for.
 */
object Replay {
    /** No provider keeps recordings longer than this, so older games aren't offered. */
    const val MAX_AGE_DAYS = 14

    /** Start a few minutes before the listed time: broadcasts begin before kickoff. */
    private const val LEAD_MS = 5 * 60_000L

    private const val DAY_MS = 86_400_000L

    /** Show "Watch full game" for this finished game? */
    fun offered(event: SportsEvent, now: Long): Boolean =
        event.finished && minutes(event) != null && now - event.startMillis in 0..MAX_AGE_DAYS * DAY_MS

    /** How long a recording to ask for, by sport (with room for overtime); null for multi-day events. */
    fun minutes(event: SportsEvent): Int? {
        val league = event.league
        return when (Leagues.byLabel(league)?.kind) {
            SportKind.FOOTBALL, SportKind.BASEBALL -> 240
            SportKind.BASKETBALL, SportKind.HOCKEY -> 180
            SportKind.SOCCER -> 150
            null -> when {
                league.equals("Golf", ignoreCase = true) || league.equals("Tennis", ignoreCase = true) -> null
                league.contains("UFC", ignoreCase = true) || league.contains("Boxing", ignoreCase = true) ||
                    league.contains("MMA", ignoreCase = true) -> 330
                league.equals("F1", ignoreCase = true) || league.contains("Formula", ignoreCase = true) -> 150
                else -> 240
            }
        }
    }

    /** The channel keeps recordings and, if the provider says for how long, still has this one. */
    fun channelHasIt(channel: IptvChannel, event: SportsEvent, now: Long): Boolean =
        channel.archive && (channel.archiveDays == null || now - event.startMillis < channel.archiveDays * DAY_MS)

    /** Where the recording starts and how many minutes to ask for (never past now). */
    fun window(event: SportsEvent, now: Long): Pair<Long, Int>? {
        val length = minutes(event) ?: return null
        val from = event.startMillis - LEAD_MS
        val available = ((now - from) / 60_000L).toInt()
        val m = minOf(length, available)
        return if (m >= 15) from to m else null
    }
}
