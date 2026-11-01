package com.launcher.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.launcher.tv.R
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.sports.GameDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the buttons on a game page do. */
class GameActions(
    val watch: () -> Unit = {},
    val startOver: () -> Unit = {},
    /** A finished game, whole, from your provider's recordings. */
    val replay: () -> Unit = {},
    val channels: () -> Unit = {},
    val highlights: () -> Unit = {},
    val revealScore: () -> Unit = {},
    val compare: () -> Unit = {},
    val iptv: Boolean = false,
)

/**
 * A game's page: scoreboard and buttons on top, then cards for the box score, scoring plays and
 * team stats (team sports), the race weekend, results and standings (F1), or the fight card (UFC).
 */
@Composable
fun GamePageLayout(event: SportsEvent, detail: GameDetail?, loading: Boolean, actions: GameActions, hideScores: Boolean = false) {
    // Merge the latest score from the summary into the event shown in the header.
    val team = detail as? GameDetail.Team
    val merged = if (team != null) event.copy(
        home = event.home?.let { it.copy(score = team.homeScore ?: it.score) },
        away = event.away?.let { it.copy(score = team.awayScore ?: it.score) },
    ) else event
    // Spoiler mode: nothing on this page may give the result away.
    val e = if (hideScores) com.launcher.tv.data.Spoilers.safe(merged) else merged

    Box(Modifier.fillMaxSize()) {
        HeroBackground(Tile.Game(e))
        HeroShade()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 26.dp)) {
            Eyebrow(gameEyebrow(e), live = e.live)
            if (e.home != null && e.away != null) Scoreboard(e, team?.awayRecord ?: "Away", team?.homeRecord ?: "Home") else Title(e.title)
            Meta(
                listOfNotNull(
                    (if (hideScores) null else team?.status) ?: if (e.finished) "Final" else if (e.live) e.status ?: "Live" else friendlyTime(e.startMillis),
                    (detail as? GameDetail.F1)?.circuit ?: team?.venue ?: e.venue,
                )
            )
            Buttons {
                when {
                    e.finished -> {
                        // With spoiler mode on you probably haven't seen it: the full game comes first.
                        val replay = actions.iptv && com.launcher.tv.player.Replay.offered(e, System.currentTimeMillis())
                        if (replay && hideScores) PillButton("Watch full game", actions.replay, primary = true, icon = R.drawable.ic_play)
                        PillButton("Highlights", actions.highlights, primary = !(replay && hideScores), icon = if (replay && hideScores) null else R.drawable.ic_play)
                        if (replay && !hideScores) PillButton("Watch full game", actions.replay)
                    }
                    actions.iptv -> {
                        PillButton(if (e.live) "Watch live" else "Watch", actions.watch, primary = true, icon = R.drawable.ic_play)
                        if (e.live) PillButton("Start over", actions.startOver)
                        PillButton("Best stream", actions.compare)
                        PillButton("Channels", actions.channels)
                    }
                    else -> PillButton("Watch in TiviMate", actions.watch, primary = true, icon = R.drawable.ic_play)
                }
                if (hideScores) PillButton("Show score", actions.revealScore)
                if (!e.finished && !hideScores && (team?.highlights?.isNotEmpty() == true)) PillButton("Highlights", actions.highlights)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    hideScores && detail is GameDetail.Team -> InfoCard("Spoiler mode", 380.dp) {
                        Text("Score, scoring plays, leaders and stats are hidden.", color = Color.White, fontSize = 13.sp)
                        Text("Choose Show score when you're ready.", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    detail is GameDetail.Team -> TeamCards(detail, e.away?.abbr ?: "Away", e.home?.abbr ?: "Home")
                    // After the race, results and standings would spoil it; the schedule is fine.
                    detail is GameDetail.F1 -> F1Cards(if (hideScores) detail.copy(results = emptyList(), standings = emptyList()) else detail)
                    detail is GameDetail.Fight -> FightCard(if (hideScores) detail.copy(bouts = detail.bouts.map { it.copy(winner = null) }) else detail)
                    else -> InfoCard(if (loading) "Loading" else "Details", 360.dp) {
                        Text(
                            if (loading) "Loading game details…" else "No extra details for this event yet.",
                            color = Dim, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.width(width).heightIn(max = 290.dp)
            .background(Color(0xD90D1117), RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Text(title.uppercase(), color = Faint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

// ---------------- team sports ----------------

@Composable
private fun TeamCards(d: GameDetail.Team, awayAbbr: String, homeAbbr: String) {
    InfoCard("Box score", 290.dp) {
        val line = d.line
        if (line == null) {
            Text("Starts soon", color = Dim, fontSize = 12.sp)
        } else {
            val cols = line.labels.takeLast(9)
            val drop = line.labels.size - cols.size
            @Composable
            fun RowOf(first: String, cells: List<String>, total: String?, bold: Boolean) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(first, color = if (bold) Color.White else Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                    cells.forEach { Text(it, color = if (bold) Dim else Faint, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
                    Text(total.orEmpty(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
                }
            }
            RowOf("", cols, "T", bold = false)
            RowOf(awayAbbr, line.away.drop(drop).padEnd(cols.size), line.awayTotal, bold = true)
            RowOf(homeAbbr, line.home.drop(drop).padEnd(cols.size), line.homeTotal, bold = true)
        }
        if (d.leaders.isNotEmpty()) {
            Text("LEADERS", color = Faint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            d.leaders.forEach { t ->
                t.leaders.take(2).forEach { l ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(t.team, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                        Column {
                            Text("${l.athlete} · ${l.category}", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(l.value, color = Dim, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    InfoCard("Scoring plays", 330.dp) {
        if (d.plays.isEmpty()) Text("No scoring yet.", color = Dim, fontSize = 12.sp)
        d.plays.take(7).forEach { p ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Column(Modifier.width(52.dp)) {
                    Text(p.period, color = Faint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text(p.clock, color = Faint, fontSize = 9.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull(p.team, p.type).joinToString(" · "), color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.text, color = Dim, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(p.score, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
    if (d.stats.isNotEmpty()) {
        InfoCard("Team stats", 230.dp) {
            d.stats.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.away, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp))
                    Text(s.label, color = Dim, fontSize = 9.5.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(s.home, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(50.dp))
                }
            }
        }
    }
}

private fun List<String>.padEnd(n: Int) = this + List((n - size).coerceAtLeast(0)) { "" }

// ---------------- F1 ----------------

private val sessionTime = DateTimeFormatter.ofPattern("EEE h:mm a")

@Composable
private fun F1Cards(d: GameDetail.F1) {
    InfoCard("Race weekend", 270.dp) {
        val now = System.currentTimeMillis()
        val next = d.sessions.firstOrNull { it.startMillis > now - 2 * 3600_000L }
        d.sessions.forEach { s ->
            val done = s.startMillis < now - 2 * 3600_000L
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sessionTime.format(Instant.ofEpochMilli(s.startMillis).atZone(ZoneId.systemDefault())),
                    color = if (s == next) Accent else Faint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(92.dp),
                )
                Text(s.name, color = if (done) Dim else Color.White, fontSize = 12.sp, fontWeight = if (s == next) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    if (d.results.isNotEmpty()) StandingCard("Race result", d.results)
    if (d.standings.isNotEmpty()) StandingCard("Drivers' standings", d.standings)
}

@Composable
private fun StandingCard(title: String, rows: List<GameDetail.Standing>) {
    InfoCard(title, 270.dp) {
        rows.take(10).forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.position, color = Accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(r.value, color = Dim, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

// ---------------- UFC ----------------

@Composable
private fun FightCard(d: GameDetail.Fight) {
    InfoCard("Fight card", 600.dp) {
        if (d.bouts.isEmpty()) Text("Card not announced yet.", color = Dim, fontSize = 12.sp)
        d.bouts.take(9).forEachIndexed { i, b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (i == 0) "MAIN" else "${i + 1}", color = if (i == 0) Accent else Faint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                Fighter(b.a, b.recordA, won = b.winner == 0, Modifier.weight(1f))
                Text("vs", color = Faint, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp))
                Fighter(b.b, b.recordB, won = b.winner == 1, Modifier.weight(1f))
                Text(b.weightClass.orEmpty(), color = Dim, fontSize = 9.5.sp, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(110.dp))
            }
        }
    }
}

@Composable
private fun Fighter(name: String, record: String?, won: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text((if (won) "✓ " else "") + name, color = Color.White, fontSize = 12.sp, fontWeight = if (won) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (record != null) Text(record, color = Faint, fontSize = 9.sp)
    }
}
