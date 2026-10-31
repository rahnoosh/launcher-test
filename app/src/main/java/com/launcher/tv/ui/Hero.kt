package com.launcher.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.launcher.tv.R
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.TeamInfo

/** What the hero buttons do. */
class HomeActions(
    val openTiviMate: () -> Unit,
    val play: (com.launcher.tv.data.history.HistoryEntry) -> Unit,
    val removeHistory: (com.launcher.tv.data.history.HistoryEntry) -> Unit,
    val launchApp: (String) -> Unit,
    val showApps: () -> Unit,
    val connectTrakt: () -> Unit,
    val watchGame: (SportsEvent, Boolean) -> Unit = { _, _ -> },
    val pickChannel: (SportsEvent) -> Unit = {},
    val openGame: (SportsEvent) -> Unit = {},
    val highlights: (SportsEvent) -> Unit = {},
    val revealScore: (SportsEvent) -> Unit = {},
    val compareProviders: (SportsEvent) -> Unit = {},
    val playMedia: (com.launcher.tv.data.MediaItem) -> Unit = {},
    /** IPTV login saved: Watch/Play go straight to the stream. */
    val iptv: Boolean = false,
)

/** What pressing OK on a tile does: the same as its hero's primary button. */
fun Tile.primaryAction(a: HomeActions): () -> Unit = when (this) {
    is Tile.Resume -> { { a.play(entry) } }
    is Tile.Media -> if (role == Tile.Role.UPCOMING) a.openTiviMate else { { a.playMedia(item) } }
    is Tile.Game -> if (event.finished) { { a.highlights(event) } } else { { a.watchGame(event, false) } }
    is Tile.App -> { { a.launchApp(app.packageName) } }
    Tile.AllApps -> a.showApps
    Tile.ConnectTrakt -> a.connectTrakt
    is Tile.Message -> { {} }
}

// ---------------- background ----------------

private val Base = Color(0xFF07090D)

@Composable
fun HeroBackground(tile: Tile?) {
    Box(Modifier.fillMaxSize().background(Base)) {
        when (tile) {
            is Tile.Resume -> ArtBackground(tile.entry.imageUrl ?: tile.entry.posterUrl, tile.title)
            is Tile.Media -> ArtBackground(tile.item.imageUrl ?: tile.item.posterUrl, tile.title)
            is Tile.Game -> GameBackground(tile.event)
            is Tile.App -> AppBackground(tile)
            Tile.ConnectTrakt -> GlowBackground(Color(0xFF5B1B7A))
            else -> GlowBackground(Color(0xFF2B313D))
        }
    }
}

@Composable
private fun ArtBackground(url: String?, seed: String) {
    if (url == null) {
        GlowBackground(seedColor(seed).copy(alpha = 1f).let { lerpBright(it) })
        return
    }
    AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}

private fun lerpBright(c: Color) = androidx.compose.ui.graphics.lerp(c, Color(0xFFE5A00D), 0.25f)

@Composable
private fun GlowBackground(c: Color) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                listOf(lerp(c, Color.White, 0.18f), c.darker(0.35f).copy(alpha = 0.8f), Base),
                center = androidx.compose.ui.geometry.Offset(1400f, 300f),
                radius = 1400f,
            )
        )
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.GameBackground(e: SportsEvent) {
    val home = e.home
    val away = e.away
    if (home == null || away == null) {
        GlowBackground(leagueColor(e.league))
        if (e.imageUrl != null) {
            AsyncImage(e.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.6f)
        }
        return
    }
    val homeC = home.mainColor()
    val awayC = away.mainColor()
    val accentC = hexColor(home.altColor)?.takeIf { it != Color.White } ?: lerp(homeC, Color.White, 0.3f)
    Box(Modifier.fillMaxSize().background(homeC.darker(0.55f)))
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                listOf(lerp(awayC, Color.White, 0.12f).copy(alpha = 0.85f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(1250f, 180f),
                radius = 950f,
            )
        )
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                listOf(accentC.copy(alpha = 0.5f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(1780f, 760f),
                radius = 800f,
            )
        )
    )
    // Oversized, faint team logo on the right, like key art.
    val logo = home.logo ?: away.logo
    if (logo != null) {
        AsyncImage(
            logo, null,
            Modifier.align(Alignment.CenterEnd).offset(x = 60.dp, y = 20.dp).size(420.dp),
            contentScale = ContentScale.Fit, alpha = 0.16f,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.AppBackground(tile: Tile.App) {
    GlowBackground(Color(0xFF1B2470))
    val banner = tile.app.banner ?: tile.app.icon
    if (banner != null) {
        val bmp = remember(banner) { banner.asImageBitmap() }
        Image(
            bmp, null,
            Modifier.align(Alignment.CenterEnd).padding(end = 70.dp).size(width = 380.dp, height = 214.dp),
            contentScale = ContentScale.Fit, alpha = 0.9f,
        )
    }
}

/** Darkens the art so text on the left and the strip at the bottom stay readable. */
@Composable
fun HeroShade() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Color(0xEB05070A), 0.3f to Color(0xB805070A), 0.62f to Color(0x2605070A), 1f to Color.Transparent
            )
        )
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color(0xB305070A), 0.22f to Color.Transparent, 0.52f to Color.Transparent,
                0.74f to Color(0x8C05070A), 1f to Color(0xF505070A),
            )
        )
    )
}

// ---------------- foreground ----------------

@Composable
fun HeroContent(tile: Tile, actions: HomeActions, modifier: Modifier = Modifier, channels: ChannelLookup? = null) {
    Column(modifier.widthIn(max = 560.dp)) {
        when (tile) {
            is Tile.Resume -> {
                val e = tile.entry
                Eyebrow("Continue watching · TiviMate")
                Title(tile.title)
                Meta(listOfNotNull(e.subtitle.takeIf { it.isNotBlank() }, "Played ${friendlyTime(e.lastPlayedAt)}"))
                if (e.durationMs > 0 && !e.finished) {
                    Row(Modifier.padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(170.dp)) { ProgressBar(e.progress, height = 3.dp) }
                        Text("  ${minutesLeft(e.durationMs - e.positionMs)} left", color = Dim, fontSize = 11.sp)
                    }
                }
                Buttons {
                    PillButton(if (e.progress > 0.01f && !e.finished) "Resume" else "Play", { actions.play(e) }, primary = true, icon = R.drawable.ic_play)
                    PillButton("Remove", { actions.removeHistory(e) })
                }
            }
            is Tile.Media -> {
                val i = tile.item
                Eyebrow(
                    when (tile.role) {
                        Tile.Role.UP_NEXT -> if (tile.isNew) "New episode · next up" else "Next up · Trakt"
                        Tile.Role.UPCOMING -> "New episode · ${i.timeMillis?.let(::friendlyTime) ?: "soon"}"
                        Tile.Role.WATCHLIST -> "On your watchlist"
                    }
                )
                Title(tile.title)
                Meta(listOf(i.subtitle).filter { it.isNotBlank() })
                Buttons {
                    when {
                        tile.role == Tile.Role.UPCOMING -> PillButton("Open TiviMate", actions.openTiviMate, primary = true, icon = R.drawable.ic_play)
                        actions.iptv -> {
                            val label = if (i.season != null && i.episode != null) "Play S%02dE%02d".format(i.season, i.episode) else "Play"
                            PillButton(label, { actions.playMedia(i) }, primary = true, icon = R.drawable.ic_play)
                            PillButton("TiviMate", actions.openTiviMate)
                        }
                        else -> PillButton("Watch in TiviMate", actions.openTiviMate, primary = true, icon = R.drawable.ic_play)
                    }
                }
            }
            is Tile.Game -> GameHero(tile.event, actions, channels)
            is Tile.App -> {
                Eyebrow("App")
                Title(tile.title)
                Buttons { PillButton("Open", { actions.launchApp(tile.app.packageName) }, primary = true, icon = R.drawable.ic_play) }
            }
            Tile.AllApps -> {
                Eyebrow("Apps")
                Title("All apps")
                Buttons { PillButton("Show all apps", actions.showApps, primary = true, icon = R.drawable.ic_grid) }
            }
            Tile.ConnectTrakt -> {
                Eyebrow("Trakt")
                Title("Connect Trakt")
                Meta(listOf("See your next episodes, new episodes and movie watchlist"))
                Buttons { PillButton("Connect", actions.connectTrakt, primary = true) }
            }
            is Tile.Message -> {
                Title(tile.title)
                if (tile.subtitle.isNotBlank()) Meta(listOf(tile.subtitle))
            }
        }
    }
}

@Composable
private fun GameHero(e: SportsEvent, actions: HomeActions, channels: ChannelLookup?) {
    val home = e.home
    Eyebrow(gameEyebrow(e), live = e.live)
    if (home != null && e.away != null) Scoreboard(e) else Title(e.title)
    val scoreHidden = home != null && (e.live || e.finished) && home.score == null && e.away?.score == null
    if (e.finished) {
        Meta(listOfNotNull("Final", e.broadcast, e.venue))
        Buttons {
            PillButton("Highlights", { actions.highlights(e) }, primary = true, icon = R.drawable.ic_play)
            if (scoreHidden) PillButton("Show score", { actions.revealScore(e) })
            PillButton("Game details", { actions.openGame(e) })
        }
        return
    }
    val best = channels?.candidates?.firstOrNull()
    val channelText = when {
        !actions.iptv -> null
        channels == null || channels.loading -> "Finding channel…"
        best != null -> "on ${best.channel.name}"
        else -> "No channel found"
    }
    Meta(
        if (e.live) listOfNotNull(if (home != null) e.status?.replace(Regex("""^\d+-\d+\s*"""), "") else e.status, channelText ?: e.venue)
        else listOfNotNull(friendlyTime(e.startMillis), if (home == null) e.status else null, channelText ?: e.venue)
    )
    Buttons {
        if (actions.iptv) {
            PillButton(if (e.live) "Watch live" else "Watch", { actions.watchGame(e, false) }, primary = true, icon = R.drawable.ic_play)
            if (e.live && best?.channel?.archive == true) PillButton("Start over", { actions.watchGame(e, true) })
            PillButton("Best stream", { actions.compareProviders(e) })
        } else {
            PillButton("Watch in TiviMate", actions.openTiviMate, primary = true, icon = R.drawable.ic_play)
        }
        if (scoreHidden) PillButton("Show score", { actions.revealScore(e) })
        PillButton("Game details", { actions.openGame(e) })
    }
}

internal fun gameEyebrow(e: SportsEvent): String =
    listOfNotNull(if (e.live) "Live now" else if (e.finished) "Final" else null, e.league, e.broadcast).joinToString(" · ")

/** Crests, names and (once started) the score; the leading team is brighter. */
@Composable
internal fun Scoreboard(e: SportsEvent, awayLabel: String = "Away", homeLabel: String = "Home") {
    val home = e.home ?: return
    val away = e.away ?: return
    val a = away.score?.toIntOrNull()
    val h = home.score?.toIntOrNull()
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TeamBlock(away, awayLabel)
        if ((e.live || e.finished) && away.score == null && home.score == null) {
            Text(
                "Score hidden", color = Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        } else if (e.live || e.finished) {
            Score(away.score ?: "0", bright = a == null || h == null || a >= h)
            Text("–", color = Faint, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Score(home.score ?: "0", bright = a == null || h == null || h >= a)
        } else {
            Text("@", color = Faint, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
        }
        TeamBlock(home, homeLabel)
    }
}

@Composable
private fun TeamBlock(team: TeamInfo, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Crest(team, 50.dp)
        Column(Modifier.padding(start = 10.dp)) {
            Text(team.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = Dim, fontSize = 9.5.sp)
        }
    }
}

@Composable
private fun Score(value: String, bright: Boolean) {
    Text(value, color = if (bright) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 54.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
}

@Composable
internal fun Eyebrow(text: String, live: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (live) {
            Box(Modifier.size(7.dp).background(LiveRed, CircleShape))
            Spacer(Modifier.width(7.dp))
        }
        Text(text.uppercase(), color = if (live) Color.White else Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, maxLines = 1)
    }
}

@Composable
internal fun Title(text: String) {
    val size = when {
        text.length <= 14 -> 50.sp
        text.length <= 22 -> 40.sp
        else -> 32.sp
    }
    Text(
        text, color = Color.White, fontSize = size, lineHeight = size * 1.02f, fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun Meta(parts: List<String>) {
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  •  "), color = Dim, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 9.dp),
    )
}

@Composable
internal fun Buttons(content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

private fun minutesLeft(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
