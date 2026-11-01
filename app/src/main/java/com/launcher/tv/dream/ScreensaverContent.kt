package com.launcher.tv.dream

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.launcher.tv.data.HomeData
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.ui.Accent
import com.launcher.tv.ui.Crest
import com.launcher.tv.ui.Dim
import com.launcher.tv.ui.Faint
import com.launcher.tv.ui.LiveRed
import com.launcher.tv.ui.darker
import com.launcher.tv.ui.leagueColor
import com.launcher.tv.ui.mainColor
import com.launcher.tv.ui.seedColor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One full-screen picture in the slideshow. */
data class Slide(val key: String, val kicker: String, val title: String, val subtitle: String, val imageUrl: String?, val colors: List<Color>)

/** A row in the "Coming up" panel. */
data class BoardRow(val key: String, val time: String, val title: String, val detail: String, val event: SportsEvent?, val live: Boolean)

fun buildSlides(d: HomeData, now: Long): List<Slide> {
    val media = (d.upNext.map { "Up next" to it } + d.upcomingEpisodes.map { "New episode" to it } + d.watchlist.map { "On your watchlist" to it })
        .filter { it.second.imageUrl != null }
        .distinctBy { it.second.title }
        .map { (k, m) -> Slide("m-${m.id}", k, m.title, m.subtitle.substringBefore(" · "), m.imageUrl, listOf(seedColor(m.title))) }
    val games = (d.myTeams + d.fights + d.f1 + d.events).filter { it.live || it.startMillis > now - 3 * 3600_000L }
        .sortedBy { it.startMillis }.take(3)
        .map { e ->
            val colors = listOfNotNull(e.away?.mainColor(), e.home?.mainColor()).ifEmpty { listOf(leagueColor(e.league)) }
            Slide(
                "g-${e.id}", if (e.live) "Live now · ${e.league}" else e.league, e.title,
                if (e.live) listOfNotNull(e.status, e.broadcast).joinToString(" · ") else listOfNotNull(whenText(e.startMillis, now), e.broadcast).joinToString(" · "),
                e.imageUrl.takeIf { e.home == null }, colors,
            )
        }
    // Interleave so sports and shows alternate.
    val out = mutableListOf<Slide>()
    for (i in 0 until maxOf(media.size, games.size)) {
        media.getOrNull(i)?.let(out::add)
        games.getOrNull(i)?.let(out::add)
    }
    return out.ifEmpty { listOf(Slide("empty", "HomeBase", "Nothing scheduled", "", null, listOf(Color(0xFF1B2470)))) }
}

fun buildBoard(d: HomeData, now: Long): Pair<List<BoardRow>, List<BoardRow>> {
    val sports = (d.myTeams + d.fights + d.f1 + d.events + d.nfl).distinctBy { it.id }
    val live = sports.filter { it.live }.map {
        BoardRow("l-${it.id}", "LIVE", it.title, listOfNotNull(it.status, it.broadcast).joinToString(" · "), it, live = true)
    }
    val horizon = now + 3 * 86_400_000L
    val upcoming = (
        (d.myTeams + d.fights + d.f1 + d.events).distinctBy { it.id }.filter { !it.live && !it.finished && it.startMillis in now..horizon }
            .map { it.startMillis to BoardRow("s-${it.id}", whenText(it.startMillis, now), it.title, listOfNotNull(it.league, it.broadcast).joinToString(" · "), it, false) } +
            d.upcomingEpisodes.filter { (it.timeMillis ?: 0L) in now..horizon }
                .map { it.timeMillis!! to BoardRow("e-${it.id}", whenText(it.timeMillis!!, now), it.title, "New episode · ${it.subtitle.substringBefore(" · ")}", null, false) }
        ).sortedBy { it.first }.map { it.second }.take(6)
    return live to upcoming
}

private fun whenText(millis: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), t.toLocalDate())
    val time = DateTimeFormatter.ofPattern("h:mm a").format(t)
    return when (days) {
        0L -> time
        1L -> "Tmrw $time"
        else -> DateTimeFormatter.ofPattern("EEE h:mm a").format(t)
    }
}

/**
 * The screensaver: slowly zooming artwork of your shows and games, a big clock, and a board of
 * live scores and what's on next. Everything drifts a little every minute so nothing burns in.
 */
@Composable
fun ScreensaverLayout(data: HomeData, startNow: Long = System.currentTimeMillis()) {
    var now by remember { mutableLongStateOf(startNow) }
    var slideIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
            slideIndex++
        }
    }
    val slides = remember(data, now / 60_000) { buildSlides(data, now) }
    val (live, upcoming) = remember(data, now / 60_000) { buildBoard(data, now) }
    val slide = slides[slideIndex % slides.size]

    // Burn-in protection: move everything a few dp, a different way each minute.
    val minute = (now / 60_000).toInt()
    val driftX = listOf(0, 6, 12, 6, 0, -6)[minute % 6].dp
    val driftY = listOf(0, 4, 0, -4)[minute % 4].dp

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = slide, animationSpec = tween(1500), label = "slide") { s -> SlideBackground(s) }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0x99000000), 0.45f to Color(0x33000000), 0.7f to Color(0x66000000), 1f to Color(0xD9000000))
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))))

        Box(Modifier.fillMaxSize().offset(driftX, driftY).padding(horizontal = 44.dp, vertical = 34.dp)) {
            // Clock
            val zone = ZoneId.systemDefault()
            val t = Instant.ofEpochMilli(now).atZone(zone)
            Column(Modifier.align(Alignment.TopStart)) {
                Text(DateTimeFormatter.ofPattern("h:mm").format(t), color = Color.White, fontSize = 76.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)
                Text(DateTimeFormatter.ofPattern("EEEE, MMMM d").format(LocalDate.from(t)), color = Dim, fontSize = 16.sp)
            }

            // Caption of the current picture
            Column(Modifier.align(Alignment.BottomStart).width(460.dp)) {
                Text(slide.kicker.uppercase(), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(slide.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (slide.subtitle.isNotBlank()) Text(slide.subtitle, color = Dim, fontSize = 13.sp)
            }

            // Board
            Board(live, upcoming)
        }
    }
}

@Composable
private fun SlideBackground(s: Slide) {
    val zoom by rememberInfiniteTransition(label = "zoom").animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom",
    )
    Box(Modifier.fillMaxSize().scale(zoom)) {
        val a = s.colors.first()
        val b = s.colors.getOrNull(1) ?: lighten(a)
        Box(Modifier.fillMaxSize().background(a.darker(0.55f)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(a.copy(alpha = 0.9f), Color.Transparent), center = Offset(1300f, 250f), radius = 1000f)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(b.copy(alpha = 0.7f), Color.Transparent), center = Offset(500f, 900f), radius = 900f)))
        if (s.imageUrl != null) AsyncImage(s.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

private fun lighten(c: Color) = androidx.compose.ui.graphics.lerp(c, Color.White, 0.25f)

@Composable
private fun BoxScope.Board(live: List<BoardRow>, upcoming: List<BoardRow>) {
    if (live.isEmpty() && upcoming.isEmpty()) return
    Column(
        Modifier.align(Alignment.TopEnd).width(330.dp)
            .background(Color(0xB30D1117), RoundedCornerShape(16.dp)).padding(18.dp),
    ) {
        if (live.isNotEmpty()) {
            Header("Live now")
            live.take(3).forEach { LiveRow(it) }
            Spacer(Modifier.height(14.dp))
        }
        if (upcoming.isNotEmpty()) {
            Header("Coming up")
            upcoming.forEach { UpcomingRow(it) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text.uppercase(), color = Faint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun LiveRow(r: BoardRow) {
    val e = r.event
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (e?.home != null && e.away != null) {
            Crest(e.away, 26.dp)
            if (e.away.score == null && e.home.score == null) {
                Text("vs", color = Faint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp))
            } else {
                Text(e.away.score ?: "0", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp))
                Text("–", color = Faint, fontSize = 16.sp)
                Text(e.home.score ?: "0", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Crest(e.home, 26.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(LiveRed, CircleShape))
                    Text(" " + (e.status?.replace(Regex("""^\d+-\d+\s*"""), "") ?: "Live"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                e.broadcast?.let { Text(it, color = Dim, fontSize = 10.sp) }
            }
        } else {
            Box(Modifier.size(6.dp).background(LiveRed, CircleShape))
            Column(Modifier.padding(start = 8.dp)) {
                Text(r.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.detail, color = Dim, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun UpcomingRow(r: BoardRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(r.time, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(78.dp), maxLines = 1)
        Column(Modifier.weight(1f)) {
            Text(r.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.detail, color = Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
