package com.launcher.tv.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.launcher.tv.R
import com.launcher.tv.data.TeamInfo

val Accent = Color(0xFFE5A00D)
val LiveRed = Color(0xFFE53935)
val Ink = Color(0xFF05070A)
val Dim = Color.White.copy(alpha = 0.68f)
val Faint = Color.White.copy(alpha = 0.42f)

// ---------- colors ----------

fun hexColor(hex: String?): Color? = hex?.let {
    runCatching { Color(android.graphics.Color.parseColor(if (it.startsWith("#")) it else "#$it")) }.getOrNull()
}

private val palette = listOf(
    Color(0xFF3B2412), Color(0xFF0C2B31), Color(0xFF1B0E33), Color(0xFF0B2A5C),
    Color(0xFF3B0A14), Color(0xFF0D2B18), Color(0xFF2A2F3A), Color(0xFF402A06),
)

/** Stable color per title, for items without artwork. */
fun seedColor(seed: String): Color = palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size]

fun leagueColor(league: String): Color = when (league) {
    "UFC" -> Color(0xFF8B1010)
    "Boxing" -> Color(0xFF7A5A12)
    "F1" -> Color(0xFF0F5132)
    "Golf" -> Color(0xFF1E5631)
    "Tennis" -> Color(0xFF3A5F0B)
    "NFL" -> Color(0xFF013369)
    "NBA", "WNBA" -> Color(0xFF1D428A)
    "MLB" -> Color(0xFF041E42)
    "NHL" -> Color(0xFF111111)
    "Premier League" -> Color(0xFF3D195B)
    "Champions League" -> Color(0xFF0E1E5B)
    "MLS", "LaLiga" -> Color(0xFF1B2A41)
    else -> Color(0xFF3B0A14)
}

fun TeamInfo.mainColor(): Color = hexColor(color) ?: seedColor(name)
fun Color.darker(f: Float) = lerp(this, Color.Black, f)

// ---------- team crest ----------

@Composable
fun Crest(team: TeamInfo, size: Dp, ring: Color = Color.White.copy(alpha = 0.18f)) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(team.mainColor(), team.mainColor().darker(0.35f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (team.logo != null) {
            AsyncImage(team.logo, team.name, Modifier.fillMaxSize().padding(size * 0.16f), contentScale = ContentScale.Fit)
        } else {
            Text(team.abbr, color = hexColor(team.altColor) ?: Color.White, fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

// ---------- tiles ----------

@Composable
fun HomeTileCard(
    tile: Tile,
    size: Dp,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(if (selected) 12.dp else 9.dp)
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(size),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Color(0xFF161A22), focusedContainerColor = Color(0xFF161A22)),
        scale = CardDefaults.scale(focusedScale = 1f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
        glow = CardDefaults.glow(),
    ) {
        Box(Modifier.fillMaxSize()) { TileFace(tile) }
    }
}

@Composable
private fun TileFace(tile: Tile) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        when (tile) {
            is Tile.Resume -> {
                Artwork(tile.entry.posterUrl ?: tile.entry.imageUrl, tile.title, w)
                ProgressBar(tile.entry.progress, Modifier.align(Alignment.BottomStart))
            }
            is Tile.Media -> {
                Artwork(tile.item.posterUrl ?: tile.item.imageUrl, tile.title, w)
                if (tile.isNew) Badge("NEW", Accent, Color(0xFF1A1205), Modifier.align(Alignment.TopStart))
                if (tile.role == Tile.Role.UPCOMING) Badge("SOON", Color.Black.copy(alpha = 0.6f), Color.White, Modifier.align(Alignment.TopStart))
            }
            is Tile.Game -> {
                val e = tile.event
                val home = e.home
                val away = e.away
                if (home != null && away != null) {
                    val a = away.mainColor()
                    val h = home.mainColor()
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(0f to a, 0.5f to a, 0.5f to h, 1f to h)
                        )
                    )
                    if (e.finished) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                        Column(Modifier.align(Alignment.Center).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${away.abbr} ${away.score ?: ""}", color = Color.White, fontSize = (w * 0.17f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            Text("${home.abbr} ${home.score ?: ""}", color = Color.White, fontSize = (w * 0.17f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        }
                    } else {
                        Column(Modifier.align(Alignment.Center).padding(top = if (e.live) 8.dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(away.abbr, color = Color.White, fontSize = (w * 0.19f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            Text("at", color = Color.White.copy(alpha = 0.7f), fontSize = (w * 0.1f).sp, fontWeight = FontWeight.Bold)
                            Text(home.abbr, color = Color.White, fontSize = (w * 0.19f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        }
                    }
                } else {
                    val c = leagueColor(e.league)
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c, c.darker(0.7f)))))
                    if (e.imageUrl != null) {
                        AsyncImage(e.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f)
                    }
                    Column(Modifier.align(Alignment.Center).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            e.league.uppercase(),
                            color = if (e.league == "UFC" || e.league == "F1") Color(0xFFFF4A3A) else Color.White,
                            fontSize = (w * 0.22f).sp, fontWeight = FontWeight.Black, maxLines = 1,
                        )
                        Text(
                            e.title.substringBefore(":").substringBefore(" Grand Prix").uppercase(),
                            color = Color.White.copy(alpha = 0.85f), fontSize = (w * 0.085f).sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (e.live) Badge("LIVE", LiveRed, Color.White, Modifier.align(Alignment.TopStart))
                if (e.finished) Badge("FINAL", Color.Black.copy(alpha = 0.7f), Color.White, Modifier.align(Alignment.TopStart))
            }
            is Tile.App -> {
                val banner = tile.app.banner
                val icon = tile.app.icon
                if (banner != null) {
                    val bmp = remember(banner) { banner.asImageBitmap() }
                    Image(bmp, tile.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B313D), Color(0xFF161A22)))))
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (icon != null) {
                            val bmp = remember(icon) { icon.asImageBitmap() }
                            Image(bmp, null, Modifier.size((w * 0.45f).dp))
                        }
                        Text(tile.title, color = Color.White, fontSize = (w * 0.1f).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
            Tile.AllApps -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B313D), Color(0xFF161A22)))))
                Icon(painterResource(R.drawable.ic_grid), "All apps", Modifier.align(Alignment.Center).size((w * 0.38f).dp), tint = Color.White)
            }
            Tile.EditTeams -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B313D), Color(0xFF161A22)))))
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+", color = Accent, fontSize = (w * 0.3f).sp, fontWeight = FontWeight.Light)
                    Text("Teams", color = Color.White, fontSize = (w * 0.12f).sp, fontWeight = FontWeight.Bold)
                }
            }
            Tile.ConnectTrakt -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF5B1B7A), Color(0xFF1A0A24)))))
                Text("Connect\nTrakt or\nSimkl", Modifier.align(Alignment.Center), color = Accent, fontSize = (w * 0.13f).sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            }
            is Tile.NowPlaying -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B4D7A), Color(0xFF0B1A2A)))))
                Column(Modifier.align(Alignment.Center).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶", color = Accent, fontSize = (w * 0.22f).sp)
                    Text("Now playing", color = Color.White, fontSize = (w * 0.11f).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            is Tile.Message -> {
                Box(Modifier.fillMaxSize().background(Color(0xFF1C222B)))
                Text(tile.title, Modifier.align(Alignment.Center).padding(6.dp), color = Dim, fontSize = (w * 0.11f).sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Artwork(url: String?, title: String, w: Float) {
    if (url != null) {
        AsyncImage(url, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        val c = seedColor(title)
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.copy(alpha = 1f), c.darker(0.6f)))))
        val longest = title.split(' ').maxOfOrNull { it.length }?.coerceAtLeast(3) ?: 3
        val fs = minOf(w * 0.16f, (w - 14f) / (longest * 0.9f))
        Box(Modifier.fillMaxSize().padding(7.dp), contentAlignment = Alignment.Center) {
            Text(
                title.uppercase(),
                color = Color(0xFFFFF3DD), fontSize = fs.sp, lineHeight = (fs * 1.15f).sp,
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Text(
        text,
        modifier = modifier.padding(5.dp).background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        color = fg, fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp,
    )
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).background(Accent))
    }
}

// ---------- buttons ----------

/** PS5-style pill: the primary action is solid white; others are glass and turn white when focused. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, primary: Boolean = false, @DrawableRes icon: Int? = null, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Color.White else Color.White.copy(alpha = 0.14f),
            contentColor = if (primary) Color(0xFF0B0D12) else Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B0D12),
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = PaddingValues(horizontal = if (primary) 26.dp else 20.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- coming-up strip ----------

@Composable
fun StripCard(item: StripItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Card(
        onClick = onClick,
        modifier = Modifier.size(186.dp, 84.dp),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Color(0xC7161B24), focusedContainerColor = Color(0xF0262D3A)),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        border = CardDefaults.border(
            border = Border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (item.imageUrl != null && item.teams.isEmpty()) {
                AsyncImage(item.imageUrl, null, Modifier.align(Alignment.CenterEnd).width(70.dp).fillMaxHeight(), contentScale = ContentScale.Crop, alpha = 0.45f)
            }
            Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.live) {
                        Box(Modifier.size(5.dp).background(LiveRed, CircleShape))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(item.kicker.uppercase(), color = if (item.live) Color.White else Accent, fontSize = 7.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                }
                Text(item.title, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                item.time, Modifier.align(Alignment.BottomStart).padding(start = 11.dp, bottom = 8.dp).fillMaxWidth(if (item.teams.isEmpty()) 0.95f else 0.62f),
                color = Dim, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (item.teams.isNotEmpty()) {
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.teams.forEach { Crest(it, 24.dp) }
                }
            }
        }
    }
}
