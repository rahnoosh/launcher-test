package com.launcher.tv.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.launcher.tv.R
import com.launcher.tv.data.HomeData
import com.launcher.tv.data.Spoilers
import com.launcher.tv.system.SleepTimer
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Aura-style Control Center: the time large on the left with what's on tonight, glass tiles on the
 * right for the same Quick settings. Focus stays inside; Back closes it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.AuraControlCenter(state: HomeUiState, onAction: (QuickAction) -> Unit, onAccounts: () -> Unit) {
    val fixed = LocalFixedNow.current
    var now by remember { mutableStateOf(fixed ?: System.currentTimeMillis()) }
    LaunchedEffect(fixed) {
        if (fixed == null) while (true) { now = System.currentTimeMillis(); delay(10_000) }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    val backdrop = LocalBackdrop.current
    val data = remember(state) { Spoilers.apply(state.data, state.spoilers, state.revealed) }

    // The sky, blurred all over and dimmed.
    Box(
        Modifier.fillMaxSize().drawBehind {
            val tiny = backdrop.tiny
            if (tiny != null) {
                drawImage(tiny, IntOffset.Zero, IntSize(tiny.width, tiny.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.High)
            } else {
                drawRect(Color(0xFF0B1426))
            }
            drawRect(Color(0x8005080F))
        },
    )
    Row(
        Modifier.fillMaxSize().padding(horizontal = 64.dp).focusGroup().focusProperties { exit = { FocusRequester.Cancel } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 28.dp)) {
            Text("CONTROL CENTER", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.2.sp)
            Text(timeText(now), color = Color.White, fontSize = 62.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            Text(longDate(now), color = Color.White.copy(alpha = 0.88f), fontSize = 17.sp)
            Spacer(Modifier.height(26.dp))
            TonightCard(data, now)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(196.dp).glass(RoundedCornerShape(28.dp))) {
                    Column(Modifier.fillMaxSize().padding(vertical = 12.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RoundToggle(R.drawable.ic_wifi, "Wi-Fi", false, Modifier.focusRequester(first)) { onAction(QuickAction.WIFI) }
                            RoundToggle(R.drawable.ic_bluetooth, "Bluetooth", false) { onAction(QuickAction.BLUETOOTH) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            val sleep = state.sleepMinutes
                            RoundToggle(R.drawable.ic_moon, if (sleep > 0) SleepTimer.label(sleep).removeSuffix(" left") else "Sleep timer", sleep > 0) { onAction(QuickAction.SLEEP) }
                            RoundToggle(R.drawable.ic_visibility_off, "Spoilers", state.spoilers) { onAction(QuickAction.SPOILERS) }
                        }
                    }
                }
                GlassTile(R.drawable.ic_trophy, "Your teams", "${state.teamsCount} teams", Modifier.size(92.dp, 196.dp)) { onAction(QuickAction.TEAMS) }
                SkyTile(state, now)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTile(R.drawable.ic_tv, "Picture", null) { onAction(QuickAction.DISPLAY) }
                GlassTile(R.drawable.ic_volume, "Sound", null) { onAction(QuickAction.SOUND) }
                GlassTile(R.drawable.ic_image, "Screensaver", null) { onAction(QuickAction.SCREENSAVER) }
                GlassTile(R.drawable.ic_phone, "Phone page", if (state.phone.enabled) "Scan to open" else "Off") { onAction(QuickAction.PHONE) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val update = state.update.available
                GlassTile(R.drawable.ic_download, if (update != null) "Update" else "Updates", update?.versionName ?: com.launcher.tv.BuildConfig.VERSION_NAME, highlighted = update != null) { onAction(QuickAction.UPDATE) }
                GlassTile(R.drawable.ic_tune, "HomeBase", "Settings") { onAction(QuickAction.HOMEBASE) }
                GlassTile(R.drawable.ic_settings, "Android", "Settings") { onAction(QuickAction.ALL) }
                GlassTile(R.drawable.ic_person, "Accounts", accountsText(state), onClick = onAccounts)
            }
        }
    }
}

private fun accountsText(state: HomeUiState): String {
    val on = listOfNotNull("Trakt".takeIf { state.trakt == TraktState.SignedIn }, "Simkl".takeIf { state.simkl == SimklState.SignedIn })
    return if (on.isEmpty()) "Connect" else on.joinToString(" · ")
}

/** Wi-Fi, Bluetooth, sleep timer and spoiler mode: round buttons, filled blue while on. */
@Composable
private fun RoundToggle(@DrawableRes icon: Int, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(52.dp),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (active) Color(0xFF2F7BFF) else Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = Color(0xFF0B0D12),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(painterResource(icon), label, Modifier.size(22.dp)) }
        }
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).width(84.dp))
    }
}

@Composable
private fun GlassTile(
    @DrawableRes icon: Int,
    label: String,
    sub: String?,
    modifier: Modifier = Modifier.size(92.dp),
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.glass(shape, frost = if (highlighted) Color(0x332F7BFF) else GlassFrost),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B0D12),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(icon), null, Modifier.size(24.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
            if (!sub.isNullOrBlank()) {
                Text(sub, fontSize = 8.5.sp, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Where the sun is: an arc with the sun (or moon) on it, the next sunrise or sunset, and the weather. */
@Composable
private fun SkyTile(state: HomeUiState, now: Long) {
    val w = state.weather
    val phase = skyPhase(now, w?.sunrise, w?.sunset)
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val rise = w?.sunrise ?: day.atTime(6, 45).atZone(zone).toInstant().toEpochMilli()
    val set = w?.sunset ?: day.atTime(18, 15).atZone(zone).toInstant().toEpochMilli()
    val daytime = now in rise..set
    // The sun along the arc by day; at night, on the horizon it set behind (or will rise from).
    val fraction = when {
        daytime -> ((now - rise).toFloat() / (set - rise)).coerceIn(0f, 1f)
        now > set -> 1f
        else -> 0f
    }
    val fmt = DateTimeFormatter.ofPattern(if (Locale.getDefault().country in setOf("US", "CA", "AU", "IN", "PH")) "h:mm a" else "HH:mm")
    Column(Modifier.size(92.dp, 196.dp).glass(RoundedCornerShape(24.dp)).padding(horizontal = 10.dp, vertical = 12.dp)) {
        Text(phase.label.uppercase(), color = Color(0xFFFFC48A), fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
        Canvas(Modifier.fillMaxWidth().height(44.dp).padding(top = 8.dp)) {
            val r = size.width / 2 - 4
            val c = Offset(size.width / 2, size.height)
            drawArc(
                Color.White.copy(alpha = 0.45f), 180f, 180f, false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f))),
            )
            val a = PI * (1 - fraction)
            val p = Offset(c.x + (r * cos(a)).toFloat(), c.y - (r * sin(a)).toFloat())
            drawCircle(if (daytime) Color(0xFFFFC857) else Color(0xFFE8ECFF), radius = 4.5.dp.toPx(), center = p)
        }
        Spacer(Modifier.height(8.dp))
        val (label, at) = if (now < rise) "SUNRISE" to rise else if (now < set) "SUNSET" to set else "SUNRISE" to rise + 86_400_000L
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(fmt.format(Instant.ofEpochMilli(at).atZone(zone)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (w != null) {
            Spacer(Modifier.weight(1f))
            Text("${w.symbol} ${w.display()}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            w.city?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** What's on: a live game, else your next game, else your next episode. */
@Composable
private fun TonightCard(data: HomeData, now: Long) {
    val sports = (data.myTeams + data.nfl + data.fights + data.f1 + data.events).distinctBy { it.id }
    val game = sports.firstOrNull { it.live }
        ?: sports.filter { !it.finished && !it.live && it.startMillis > now - 3 * 3_600_000L }.minByOrNull { it.startMillis }
    val episode = data.upNext.firstOrNull()
    if (game == null && episode == null) return
    Row(
        Modifier.width(340.dp).glass(RoundedCornerShape(22.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (game != null) {
            val home = game.home
            val away = game.away
            Box(Modifier.size(64.dp, 86.dp).background(leagueColor(game.league).copy(alpha = 0.35f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                if (home != null && away != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Crest(away, 28.dp)
                        Spacer(Modifier.height(6.dp))
                        Crest(home, 28.dp)
                    }
                } else {
                    Text(game.league.uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Column(Modifier.padding(start = 14.dp)) {
                val kicker = if (game.live) "LIVE NOW" else friendlyDay(game.startMillis, now)
                Text("$kicker  ·  ${game.league.uppercase()}", color = if (game.live) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.65f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
                Text(game.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                Text(
                    if (game.live) (if (game.home != null) Spoilers.stripScore(game.status) else game.status) ?: "Live" else friendlyTime(game.startMillis),
                    color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp),
                )
                game.broadcast?.let { Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        } else if (episode != null) {
            val poster = episode.posterUrl ?: episode.imageUrl
            Box(Modifier.size(64.dp, 86.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))) {
                if (poster != null) AsyncImage(poster, null, Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text("UP NEXT", color = Color.White.copy(alpha = 0.65f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(episode.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                Text(episode.subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private fun friendlyDay(start: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        d == today -> if (Instant.ofEpochMilli(start).atZone(zone).hour >= 17) "TONIGHT" else "TODAY"
        d == today.plusDays(1) -> "TOMORROW"
        else -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()).uppercase()
    }
}
