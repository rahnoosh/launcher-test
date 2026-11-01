package com.launcher.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.launcher.tv.data.sky.DailyPhoto
import com.launcher.tv.data.sky.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Where the sun is, which sets the light over the photograph. */
enum class SkyPhase(val label: String) {
    NIGHT("Night sky"),
    DAWN("Sunrise"),
    DAY("Daylight"),
    DUSK("Golden hour"),
}

/** From today's sunrise and sunset when the weather knows them; otherwise 6:45 and 18:15. */
fun skyPhase(now: Long, sunrise: Long?, sunset: Long?, zone: ZoneId = ZoneId.systemDefault()): SkyPhase {
    val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val rise = sunrise ?: day.atTime(6, 45).atZone(zone).toInstant().toEpochMilli()
    val set = sunset ?: day.atTime(18, 15).atZone(zone).toInstant().toEpochMilli()
    val edge = 40 * 60_000L
    return when {
        now in (rise - edge)..(rise + edge) -> SkyPhase.DAWN
        now in (set - edge)..(set + edge) -> SkyPhase.DUSK
        now in rise..set -> SkyPhase.DAY
        else -> SkyPhase.NIGHT
    }
}

/**
 * The home screen's backdrop: [photo] filling the screen, and [tiny], the same picture blurred into a
 * small copy that every glass panel samples (one cheap draw per panel, no live blur).
 */
class Sky(val photo: ImageBitmap, val tiny: ImageBitmap, val phase: SkyPhase, val credit: String?, val hasPhoto: Boolean)

/** The light laid over a photograph, by time of day. */
fun skyTints(phase: SkyPhase, photo: Boolean): List<Color> = if (!photo) listOf(Color.Black.copy(alpha = 0.16f)) else when (phase) {
    SkyPhase.NIGHT -> listOf(Color(0xFF060A1A).copy(alpha = 0.60f))
    SkyPhase.DAWN -> listOf(Color(0xFF241A3A).copy(alpha = 0.32f), Color(0xFFFFA65C).copy(alpha = 0.10f))
    SkyPhase.DAY -> listOf(Color(0xFF0A0F1A).copy(alpha = 0.28f))
    SkyPhase.DUSK -> listOf(Color(0xFF1C1020).copy(alpha = 0.34f), Color(0xFFFF8A3D).copy(alpha = 0.13f))
}

/** A painted sky for when there's no photograph yet (or no internet). */
fun skyGradient(phase: SkyPhase, w: Int = 320, h: Int = 180): Bitmap {
    val (colors, glow) = when (phase) {
        SkyPhase.NIGHT -> intArrayOf(0xFF04060F.toInt(), 0xFF0B1430.toInt(), 0xFF1A2752.toInt()) to Triple(0.78f, 0.18f, 0x1AFFFFFF)
        SkyPhase.DAWN -> intArrayOf(0xFF1E2350.toInt(), 0xFF8C5A84.toInt(), 0xFFF0A86E.toInt()) to Triple(0.70f, 0.78f, 0x40FFD7A0)
        SkyPhase.DAY -> intArrayOf(0xFF1F5FA8.toInt(), 0xFF4C8FD0.toInt(), 0xFFA9CCEB.toInt()) to Triple(0.76f, 0.16f, 0x40FFFFFF)
        SkyPhase.DUSK -> intArrayOf(0xFF1A1A44.toInt(), 0xFF7A3F73.toInt(), 0xFFF08A4B.toInt()) to Triple(0.28f, 0.82f, 0x4DFFB26B)
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    })
    val (gx, gy, gc) = glow
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
        shader = RadialGradient(gx * w, gy * h, h * 0.9f, intArrayOf(gc, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    })
    return bmp
}

fun buildSky(photo: Bitmap?, phase: SkyPhase, credit: String?): Sky {
    val source = photo ?: skyGradient(phase)
    val tiny = Bitmap.createScaledBitmap(source, 96, 54, true).copy(Bitmap.Config.ARGB_8888, true)
    val c = Canvas(tiny)
    skyTints(phase, photo != null).forEach { c.drawColor(it.toArgb()) }
    boxBlur(tiny, radius = 2, passes = 3)
    return Sky(source.asImageBitmap(), tiny.asImageBitmap(), phase, credit, photo != null)
}

/** A few passes of a box blur over a small bitmap, in place. */
private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
    val w = bmp.width
    val h = bmp.height
    var px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    var tmp = IntArray(w * h)
    repeat(passes) {
        for (horizontal in listOf(true, false)) {
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
                for (k in -radius..radius) {
                    val xx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
                    val yy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
                    val p = px[yy * w + xx]
                    a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                }
                tmp[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
            val t = px; px = tmp; tmp = t
        }
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
}

/** Today's photograph and the light for the time of day, refreshed as the day goes on. */
@Composable
fun rememberDailySky(weather: Weather.Now?): Sky {
    val context = LocalContext.current
    var photo by remember { mutableStateOf<Pair<Bitmap, String>?>(null) }
    var phase by remember { mutableStateOf(skyPhase(System.currentTimeMillis(), weather?.sunrise, weather?.sunset)) }
    LaunchedEffect(weather?.sunrise, weather?.sunset) {
        while (true) {
            phase = skyPhase(System.currentTimeMillis(), weather?.sunrise, weather?.sunset)
            delay(60_000)
        }
    }
    LaunchedEffect(Unit) {
        var day: LocalDate? = null
        while (true) {
            if (day != LocalDate.now()) {
                val p = DailyPhoto.today(context)
                if (p != null) {
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeFile(p.file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }.getOrNull()
                    }
                    if (bmp != null) { photo = bmp to p.credit; day = LocalDate.now() }
                }
            }
            delay(30 * 60_000L)
        }
    }
    return remember(photo, phase) { buildSky(photo?.first, phase, photo?.second) }
}

/** The photograph with its light, plus shade where text sits. */
@Composable
fun SkyBackground(sky: Sky) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B1020))) {
        Image(sky.photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        skyTints(sky.phase, sky.hasPhoto).forEach { Box(Modifier.fillMaxSize().background(it)) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x40000000), 0.3f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0x8C000000))))
    }
}

// ---------------- glass ----------------

/** What glass panels see through: the blurred copy of the sky, laid over the whole screen. */
class Backdrop(val tiny: ImageBitmap?, val screen: IntSize)

val LocalBackdrop = staticCompositionLocalOf { Backdrop(null, IntSize.Zero) }

val GlassFrost = Color.White.copy(alpha = 0.11f)
val GlassFrostFocused = Color.White.copy(alpha = 0.24f)
val GlassEdge = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))

/**
 * Frosted glass: the part of the blurred sky behind this panel, a little white, and a hairline edge.
 * Inside a dialog (a separate window) there's no sky to sample, so it's a dark translucent panel.
 */
fun Modifier.glass(shape: Shape, frost: Color = GlassFrost, edge: BorderStroke? = GlassEdge): Modifier = composed {
    val backdrop = LocalBackdrop.current
    val origin = remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin.value = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            val tiny = backdrop.tiny
            if (tiny != null && backdrop.screen.width > 0) {
                val o = origin.value
                drawImage(
                    tiny,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(tiny.width, tiny.height),
                    dstOffset = IntOffset(-o.x.roundToInt(), -o.y.roundToInt()),
                    dstSize = backdrop.screen,
                    filterQuality = FilterQuality.High,
                )
            } else {
                drawRect(Color(0xE6182030))
            }
            drawRect(frost)
        }
        .then(if (edge != null) Modifier.border(edge, shape) else Modifier)
}
