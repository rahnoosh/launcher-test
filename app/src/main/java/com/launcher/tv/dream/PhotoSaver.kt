package com.launcher.tv.dream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.launcher.tv.data.HomeData
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.Spoilers
import com.launcher.tv.data.sky.PhotoArchive
import com.launcher.tv.data.sky.Quote
import com.launcher.tv.data.sky.Quotes
import com.launcher.tv.ui.Backdrop
import com.launcher.tv.ui.Crest
import com.launcher.tv.ui.LiveRed
import com.launcher.tv.ui.LocalBackdrop
import com.launcher.tv.ui.Sky
import com.launcher.tv.ui.SkyPhase
import com.launcher.tv.ui.buildSky
import com.launcher.tv.ui.friendlyTime
import com.launcher.tv.ui.glass
import com.launcher.tv.ui.leagueColor
import com.launcher.tv.ui.skyPhase
import com.launcher.tv.ui.timeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** What the screensaver shows. */
enum class SaverStyle(val key: String, val label: String) {
    /** Photos and quotes in turn. */
    AUTO("auto", "Auto"),
    PHOTOS("photos", "Photos"),
    QUOTES("quotes", "Quotes"),
    /** HomeBase's original: scores and tonight's schedule. */
    SCORES("scores", "Scores");

    companion object {
        fun of(key: String?): SaverStyle = entries.firstOrNull { it.key == key } ?: AUTO
        fun current(c: Context): SaverStyle = of(com.launcher.tv.data.Profiles.prefs(c, "ui").getString("saver", null))
        fun set(c: Context, style: SaverStyle) = com.launcher.tv.data.Profiles.prefs(c, "ui").edit().putString("saver", style.key).apply()
    }
}

/** One photograph, ready to show: the picture, its blurred copy for glass, and its caption. */
class SaverPhoto(val sky: Sky, val place: String?, val credit: String?)

private const val SLIDE_MS = 45_000L

/** The style chosen in Settings: scores, or photographs with quotes. */
@Composable
fun Screensaver(data: HomeData) {
    val context = LocalContext.current
    val style = remember { SaverStyle.current(context) }
    if (style == SaverStyle.SCORES) ScreensaverLayout(data) else PhotoSaver(data, style)
}

/** Loads the photographs (today's first), then shows them. */
@Composable
fun PhotoSaver(data: HomeData, style: SaverStyle) {
    val context = LocalContext.current
    val files by produceState(initialValue = emptyList<PhotoArchive.Photo>()) { value = PhotoArchive.photos(context) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(SLIDE_MS); index++ }
    }
    val now = System.currentTimeMillis()
    val phase = skyPhase(now, null, null)
    // Decode one photograph at a time (they're big); a painted sky until the first is ready.
    val photo by produceState<SaverPhoto?>(initialValue = null, files, index) {
        val f = files.getOrNull(index % files.size.coerceAtLeast(1))
        value = if (f == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val bmp: Bitmap = BitmapFactory.decodeFile(f.file.path) ?: return@runCatching null
                SaverPhoto(buildSky(bmp, phase, f.credit), f.place, f.credit)
            }.getOrNull()
        } ?: value
    }
    val fallback = remember(phase) { SaverPhoto(buildSky(null, phase, null), null, null) }
    PhotoSaverLayout(data, style, photo ?: fallback, quote = Quotes.all[(index + (now / 86_400_000L).toInt()) % Quotes.all.size], slide = index)
}

/**
 * Aura-style screensaver: a photograph drifting slowly, with its place and photographer, or a quote over
 * it lighting up line by line. Your live game (or next one) sits in a small glass card at the bottom.
 * [animate] false draws the finished frame (screenshots).
 */
@Composable
fun PhotoSaverLayout(
    data: HomeData,
    style: SaverStyle,
    photo: SaverPhoto,
    quote: Quote,
    slide: Int = 0,
    now: Long? = null,
    animate: Boolean = true,
) {
    var clock by remember { mutableLongStateOf(now ?: System.currentTimeMillis()) }
    LaunchedEffect(now) {
        if (now == null) while (true) { clock = System.currentTimeMillis(); delay(10_000) }
    }
    val showQuote = when (style) {
        SaverStyle.QUOTES -> true
        SaverStyle.PHOTOS, SaverStyle.SCORES -> false
        SaverStyle.AUTO -> slide % 2 == 1
    }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    CompositionLocalProvider(LocalBackdrop provides Backdrop(photo.sky.tiny, screen)) {
        Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { screen = it }) {
            Crossfade(targetState = photo, animationSpec = tween(1600), label = "photo") { p -> DriftingPhoto(p, animate) }
            // Shade where the words sit.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0x99000000), 0.55f to Color(0x33000000), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0x99000000))))

            Text(
                timeText(clock), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 44.dp),
            )

            Column(Modifier.align(Alignment.CenterStart).padding(start = 64.dp, bottom = 70.dp).widthIn(max = 640.dp)) {
                if (showQuote) {
                    Crossfade(targetState = quote, animationSpec = tween(900), label = "quote") { q -> LightingQuote(q, animate) }
                }
            }

            Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 34.dp, end = 64.dp)) {
                gameFor(data, clock)?.let { GameStrip(it, clock) }
                if (!showQuote && photo.place != null) {
                    Text(photo.place, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                }
                photo.credit?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) }
            }
            SlideProgress(slide, animate, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** The photograph, slowly zooming and drifting (a Ken Burns pan). */
@Composable
private fun DriftingPhoto(p: SaverPhoto, animate: Boolean) {
    val t = remember(p) { Animatable(0f) }
    LaunchedEffect(p, animate) {
        if (animate) t.animateTo(1f, tween(SLIDE_MS.toInt() + 2000, easing = LinearEasing))
    }
    val shift = with(LocalDensity.current) { 24.dp.toPx() }
    Image(
        p.sky.photo, null,
        Modifier.fillMaxSize().graphicsLayer {
            val k = t.value
            scaleX = 1.04f + 0.08f * k
            scaleY = 1.04f + 0.08f * k
            translationX = -shift * k
        },
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.High,
    )
}

/** The quote in large type, its lines lighting up one after another; then who said it. */
@Composable
private fun LightingQuote(q: Quote, animate: Boolean) {
    val lines = remember(q) { Quotes.wrap(q.text, 30) }
    val lit = remember(q) { Animatable(if (animate) 0f else lines.size + 1f) }
    LaunchedEffect(q, animate) {
        if (animate) lit.animateTo(lines.size + 1f, tween((lines.size + 1) * 900, easing = LinearEasing))
    }
    Column {
        lines.forEachIndexed { i, line ->
            val a = (lit.value - i).coerceIn(0f, 1f)
            Text(
                line, color = Color.White.copy(alpha = 0.28f + 0.72f * a), fontSize = 38.sp, lineHeight = 46.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
            )
        }
        Text(
            q.author, color = Color.White.copy(alpha = 0.72f * (lit.value - lines.size).coerceIn(0f, 1f)),
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp),
        )
    }
}

/** A live game of yours, else your next one in the coming day. */
private fun gameFor(data: HomeData, now: Long): SportsEvent? {
    val all = (data.myTeams + data.nfl + data.fights + data.f1).distinctBy { it.id }
    return all.firstOrNull { it.live }
        ?: all.filter { !it.finished && it.startMillis in now..(now + 24 * 3_600_000L) }.minByOrNull { it.startMillis }
}

@Composable
private fun GameStrip(e: SportsEvent, now: Long) {
    Row(Modifier.glass(RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val home = e.home
        val away = e.away
        if (home != null && away != null) {
            Crest(away, 30.dp)
            Spacer(Modifier.width(6.dp))
            Crest(home, 30.dp)
        } else {
            Box(Modifier.size(30.dp).background(leagueColor(e.league), CircleShape))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.live) Box(Modifier.padding(end = 6.dp).size(6.dp).background(LiveRed, CircleShape))
                Text(
                    "${if (e.live) "LIVE" else day(e.startMillis, now)}  ·  ${e.league.uppercase()}",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                )
            }
            val score = if (home != null && away != null && e.live && (home.score != null || away.score != null)) "  ${away.score ?: 0} – ${home.score ?: 0}" else ""
            Text(e.title + score, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (e.live) (if (home != null) Spoilers.stripScore(e.status) else e.status) ?: "Live" else friendlyTime(e.startMillis),
                color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1,
            )
        }
    }
}

private fun day(start: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(start).atZone(zone)
    return when (d.toLocalDate()) {
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate() -> if (d.hour >= 17) "TONIGHT" else "TODAY"
        else -> "TOMORROW"
    }
}

/** A thin line along the bottom: how long until the next photograph. */
@Composable
private fun SlideProgress(slide: Int, animate: Boolean, modifier: Modifier) {
    val p = remember(slide) { Animatable(0f) }
    LaunchedEffect(slide, animate) {
        if (animate) p.animateTo(1f, tween(SLIDE_MS.toInt(), easing = LinearEasing))
    }
    Box(modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(if (animate) p.value else 0.35f).background(Color.White.copy(alpha = 0.6f)))
    }
}

/** For previews and screenshots: a painted sky as the "photograph". */
fun paintedPhoto(phase: SkyPhase, place: String?, credit: String?) = SaverPhoto(buildSky(null, phase, credit), place, credit)
