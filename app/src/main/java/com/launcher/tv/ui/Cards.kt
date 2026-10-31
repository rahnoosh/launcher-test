package com.launcher.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.launcher.tv.data.AppEntry
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.history.HistoryEntry

val Accent = Color(0xFFE5A00D)
val LiveRed = Color(0xFFE53935)
private val CardBg = Color(0xFF1C222B)
private val Shape = RoundedCornerShape(10.dp)

@Composable
private fun FocusCard(
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.size(width, height),
        shape = CardDefaults.shape(Shape),
        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardBg),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Accent), shape = Shape)),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

private val scrim = Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))

/** 16:9 card with artwork and a caption over a dark gradient. */
@Composable
fun WideMediaCard(item: MediaItem, caption: String? = null, onClick: () -> Unit) {
    FocusCard(260.dp, 146.dp, onClick) {
        if (item.imageUrl != null) {
            AsyncImage(item.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(scrim))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            if (caption != null) Text(caption, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, color = Color(0xFFCCCCCC), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Something played through TiviMate, with a resume progress bar. Long-press removes it. */
@Composable
fun HistoryCard(entry: HistoryEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusCard(260.dp, 146.dp, onClick, onLongClick) {
        if (entry.imageUrl != null) {
            AsyncImage(entry.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A2F3A), Color(0xFF151920)))))
        }
        Box(Modifier.fillMaxSize().background(scrim))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 10.dp)) {
                val caption = when {
                    entry.finished -> "WATCHED"
                    entry.progress > 0.01f -> "RESUME · ${(entry.progress * 100).toInt()}%"
                    else -> null
                }
                if (caption != null) Text(caption, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(entry.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotBlank()) {
                    Text(entry.subtitle, color = Color(0xFFCCCCCC), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth(entry.progress).height(4.dp).background(Accent))
            }
        }
    }
}

/** 2:3 poster card for movies. */
@Composable
fun PosterCard(item: MediaItem, onClick: () -> Unit) {
    FocusCard(130.dp, 195.dp, onClick) {
        if (item.imageUrl != null) {
            AsyncImage(item.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center) {
                Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(item.subtitle, color = Color(0xFFAAAAAA), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SportsCard(event: SportsEvent, onClick: () -> Unit) {
    FocusCard(280.dp, 146.dp, onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF23303F), Color(0xFF141A22)))))
        if (event.imageUrl != null) {
            AsyncImage(
                event.imageUrl, null,
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(56.dp),
                contentScale = ContentScale.Fit,
                alpha = 0.9f,
            )
        }
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.league.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (event.live) {
                    Text(
                        "  LIVE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp).background(LiveRed, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                    )
                }
            }
            Text(
                event.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(if (event.imageUrl != null) 0.75f else 1f),
            )
            Box(Modifier.weight(1f))
            Text(
                if (event.live) event.status ?: "Live now" else friendlyTime(event.startMillis),
                color = if (event.live) LiveRed else Color(0xFFDDDDDD),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val extra = listOfNotNull(event.broadcast, if (!event.live) event.status else null, event.venue).firstOrNull()
            if (extra != null) Text(extra, color = Color(0xFF999999), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AppCard(app: AppEntry, onClick: () -> Unit) {
    FocusCard(176.dp, 99.dp, onClick) {
        val bmp = app.banner
        if (bmp != null && app.isBanner) {
            Image(bmp.asImageBitmap(), app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(44.dp))
                Text(app.label, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Card used for prompts like "Connect Trakt". */
@Composable
fun ActionCard(title: String, subtitle: String, onClick: () -> Unit) {
    FocusCard(260.dp, 146.dp, onClick) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFFCCCCCC), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun EmptyNote(text: String) {
    Box(Modifier.height(60.dp).width(400.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = Color(0xFF888888), style = MaterialTheme.typography.bodyMedium)
    }
}
