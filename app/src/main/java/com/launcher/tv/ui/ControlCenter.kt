package com.launcher.tv.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.launcher.tv.R
import kotlinx.coroutines.delay

/** The tiles in Quick settings. */
enum class QuickAction(val label: String, @DrawableRes val icon: Int) {
    WIFI("Network", R.drawable.ic_wifi),
    BLUETOOTH("Remotes & Bluetooth", R.drawable.ic_bluetooth),
    DISPLAY("Display", R.drawable.ic_tv),
    SOUND("Sound", R.drawable.ic_volume),
    SLEEP("Sleep timer", R.drawable.ic_moon),
    SPOILERS("Spoiler mode", R.drawable.ic_visibility_off),
    SCREENSAVER("Screensaver", R.drawable.ic_image),
    HOMEBASE("HomeBase", R.drawable.ic_tune),
    ALL("All settings", R.drawable.ic_settings),
}

/** Opens the matching Android settings page, falling back to the main settings screen. */
fun openSystemSettings(context: Context, action: QuickAction) {
    val specific = when (action) {
        QuickAction.WIFI -> Settings.ACTION_WIFI_SETTINGS
        QuickAction.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
        QuickAction.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
        QuickAction.SOUND -> Settings.ACTION_SOUND_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }
    for (a in listOf(specific, Settings.ACTION_SETTINGS)) {
        val ok = runCatching { context.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return
    }
}

/**
 * PS5-style control centre: a strip of tiles along the bottom of the home screen.
 * Focus stays inside while it's open; Back closes it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.ControlCenter(
    sleepMinutesLeft: Int,
    status: Map<QuickAction, String>,
    active: Set<QuickAction> = emptySet(),
    onAction: (QuickAction) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color(0xF0151A22), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 24.dp)
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quick settings", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("BACK to close", color = Faint, fontSize = 10.sp)
        }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction.entries.forEachIndexed { i, a ->
                val sub = if (a == QuickAction.SLEEP) com.launcher.tv.system.SleepTimer.label(sleepMinutesLeft) else status[a].orEmpty()
                QuickTile(
                    action = a,
                    subtitle = sub,
                    highlighted = (a == QuickAction.SLEEP && sleepMinutesLeft > 0) || a in active,
                    onClick = { onAction(a) },
                    modifier = Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun QuickTile(action: QuickAction, subtitle: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(96.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlighted) Accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.07f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B0D12),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(action.icon), null, Modifier.size(24.dp))
            Text(action.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp, modifier = Modifier.padding(top = 7.dp))
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 8.5.sp, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}
