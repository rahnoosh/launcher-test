package com.launcher.tv.alerts

import android.content.Context
import com.launcher.tv.data.HomeData
import com.launcher.tv.data.Http
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.TeamInfo
import com.launcher.tv.data.sports.Leagues
import com.launcher.tv.data.sports.SportKind
import com.launcher.tv.data.sports.SportsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

data class LiveAlert(
    val key: String,
    /** "TOUCHDOWN", "STARTING IN 10 MIN", "FINAL"... */
    val kicker: String,
    val title: String,
    val detail: String,
    val teams: List<TeamInfo>,
    val event: SportsEvent,
)

/** Alerts already shown (so nothing repeats, even after a restart). */
interface FiredStore {
    fun contains(key: String): Boolean
    fun put(key: String, at: Long)
    fun pruneBefore(time: Long)
}

private class PrefsFiredStore(context: Context) : FiredStore {
    private val prefs = context.getSharedPreferences("alerts_fired", Context.MODE_PRIVATE)
    override fun contains(key: String) = prefs.contains(key)
    override fun put(key: String, at: Long) = prefs.edit().putLong(key, at).apply()
    override fun pruneBefore(time: Long) {
        val old = prefs.all.filter { (_, v) -> (v as? Long ?: 0L) < time }.keys
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it) } }.apply()
    }
}

/**
 * Watches your teams (plus UFC, boxing and F1 from the home screen's schedule) and raises alerts:
 * starting in 10 minutes, under way, scoring plays (football/baseball), close finish (basketball), final.
 * Polls ESPN every ~45 s only while one of your games is live; otherwise it sleeps until the next one.
 */
class LiveAlertEngine internal constructor(
    private val enabled: () -> Boolean,
    private val scoringOn: () -> Boolean,
    private val store: FiredStore,
    private val schedule: suspend () -> HomeData?,
    private val scoreboard: suspend (league: String) -> List<SportsEvent>?,
    private val clock: () -> Long,
    private val show: suspend (LiveAlert) -> Unit,
    /** Spoiler mode: true if this game's score must not be shown. */
    private val hidesScore: (SportsEvent) -> Boolean = { false },
) {
    constructor(context: Context, show: suspend (LiveAlert) -> Unit) : this(
        enabled = { AlertSettings.enabled(context) },
        scoringOn = { AlertSettings.scoring(context) },
        store = PrefsFiredStore(context),
        schedule = {
            withContext(Dispatchers.IO) {
                runCatching { Http.json.decodeFromString(HomeData.serializer(), com.launcher.tv.data.Profiles.file(context, "home_cache.json").readText()) }.getOrNull()
            }
        },
        scoreboard = { SportsRepository.liveScoreboard(it) },
        clock = System::currentTimeMillis,
        show = show,
        hidesScore = { com.launcher.tv.data.Spoilers.hides(context, it) },
    )

    private val lastSeen = mutableMapOf<String, SportsEvent>()

    suspend fun run() = coroutineScope {
        while (isActive) {
            val wait = try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                120_000L
            }
            delay(wait)
        }
    }

    /** One pass. Returns how long to sleep. */
    internal suspend fun tick(): Long {
        if (!enabled()) return 5 * 60_000L
        val data = schedule() ?: return 10 * 60_000L
        val now = clock()
        store.pruneBefore(now - 3 * 86_400_000L)

        val followed = (data.myTeams + data.fights + data.f1 + data.events).distinctBy { it.id }
        // 1) Starting soon / starting now, straight from the schedule.
        for (e in followed) {
            val mins = (e.startMillis - now) / 60_000.0
            if (mins in 8.5..11.0) fire(e, "soon", "STARTING IN 10 MIN", e.title, listOfNotNull(e.broadcast, e.venue).joinToString(" · "))
            if (e.home == null && mins in -2.0..0.5) fire(e, "start", "STARTING NOW", e.title, listOfNotNull(e.league, e.broadcast).joinToString(" · "))
        }

        // 2) Live tracking for team games in their window.
        val active = followed.filter { it.home != null && now in (it.startMillis - 15 * 60_000L)..(it.startMillis + 5 * 3600_000L) }
        for (league in active.map { it.league }.distinct()) {
            val board = scoreboard(league) ?: continue
            for (game in active.filter { it.league == league }) {
                val live = board.firstOrNull { it.id == game.id } ?: continue
                compare(lastSeen[game.id], live, now)
                lastSeen[game.id] = live
            }
        }
        if (active.isNotEmpty()) return 45_000L

        val next = followed.map { it.startMillis - 11 * 60_000L - now }.filter { it > 0 }.minOrNull()
        return (next ?: 30 * 60_000L).coerceIn(30_000L, 15 * 60_000L)
    }

    private suspend fun compare(before: SportsEvent?, now: SportsEvent, time: Long) {
        val home = now.home ?: return
        val away = now.away ?: return
        val score = "${away.abbr} ${away.score ?: 0} – ${home.score ?: 0} ${home.abbr}"
        val clockText = now.status?.replace(Regex("""^\d+-\d+\s*"""), "").orEmpty()
        val matchup = "${away.name} @ ${home.name}"
        if (before == null) {
            // First look (e.g. after a restart): only announce a game that just got under way.
            if (now.live && time - now.startMillis < 10 * 60_000L) {
                fire(now, "start", startWord(now.league), matchup, listOfNotNull(now.broadcast, clockText.ifBlank { null }).joinToString(" · "))
            }
            return
        }
        if (!before.live && !before.finished && now.live) {
            fire(now, "start", startWord(now.league), matchup, now.broadcast.orEmpty())
        }
        val hidden = hidesScore(now)
        if (now.live && scoringOn() && !hidden) {
            val dA = (away.score?.toIntOrNull() ?: 0) - (before.away?.score?.toIntOrNull() ?: 0)
            val dH = (home.score?.toIntOrNull() ?: 0) - (before.home?.score?.toIntOrNull() ?: 0)
            val scorer = if (dA > 0) away else if (dH > 0) home else null
            val league = Leagues.byLabel(now.league)
            val basketball = league?.kind == SportKind.BASKETBALL
            if (scorer != null && !basketball) {
                fire(now, "score-${away.score}-${home.score}", scoringWord(now.league, maxOf(dA, dH)), "${scorer.name} score", "$score · $clockText")
            }
            // Basketball scores constantly: only flag a close finish (last quarter / second half).
            val diff = kotlin.math.abs((away.score?.toIntOrNull() ?: 0) - (home.score?.toIntOrNull() ?: 0))
            if (basketball && (now.period ?: 0) >= (league?.periods ?: 4) && diff <= 5) {
                fire(now, "close", "CLOSE GAME", score, clockText)
            }
        }
        if (!before.finished && now.finished) {
            if (hidden) fire(now, "final", "FINAL", matchup, "Score hidden (spoiler mode)")
            else fire(now, "final", "FINAL", score, now.broadcast.orEmpty())
        }
    }

    private suspend fun fire(e: SportsEvent, type: String, kicker: String, title: String, detail: String) {
        val key = "${e.id}|$type"
        if (store.contains(key)) return
        store.put(key, clock())
        if (NowPlaying.eventId == e.id && type != "final") return
        show(LiveAlert(key, kicker, title, detail, listOfNotNull(e.away, e.home), e))
    }

    private fun startWord(league: String) = when (Leagues.byLabel(league)?.kind) {
        SportKind.FOOTBALL, SportKind.SOCCER -> "KICKOFF"
        SportKind.BASKETBALL -> "TIP-OFF"
        SportKind.BASEBALL -> "FIRST PITCH"
        SportKind.HOCKEY -> "PUCK DROP"
        null -> "UNDER WAY"
    }

    private fun scoringWord(league: String, points: Int) = when (Leagues.byLabel(league)?.kind) {
        SportKind.FOOTBALL -> when (points) {
            in 6..8 -> "TOUCHDOWN"
            3 -> "FIELD GOAL"
            2 -> "SAFETY"
            1 -> "EXTRA POINT"
            else -> "SCORE"
        }
        SportKind.BASEBALL -> if (points == 1) "RUN SCORES" else "$points RUNS SCORE"
        SportKind.HOCKEY, SportKind.SOCCER -> "GOAL"
        else -> "SCORE"
    }
}
