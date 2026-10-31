package com.launcher.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.iptv.CheckRow

/** Results of trying each provider's stream for one event. */
data class StreamCheckState(
    val event: SportsEvent,
    val rows: List<CheckRow> = emptyList(),
    val running: Boolean = true,
    val error: String? = null,
)

fun CheckRow.metrics(running: Boolean): String {
    val r = result ?: return if (running) "Waiting to test…" else "Not tested"
    if (!r.ok) return "Didn't play" + (r.error?.let { ": $it" } ?: "")
    val parts = mutableListOf<String>()
    r.startMs?.let { parts += "starts in %.1f s".format(it / 1000.0) }
    r.kbps?.let { parts += "%.1f Mbps".format(it / 1000.0) }
    (r.resolution ?: com.launcher.tv.data.iptv.StreamProbe.guessResolution(candidate.channel.name))?.let { parts += "${it}p" }
    if (candidate.channel.archive) parts += "catch-up"
    if (candidate.reason.contains("guide")) parts += "in TV guide"
    return parts.joinToString("  ·  ").replaceFirstChar { it.uppercase() }
}

@Composable
fun StreamCheckDialog(
    state: StreamCheckState,
    onWatch: (CheckRow) -> Unit,
    onTiviMate: (CheckRow) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        StreamCheckContent(state, onWatch, onTiviMate)
    }
}

@Composable
fun StreamCheckContent(state: StreamCheckState, onWatch: (CheckRow) -> Unit, onTiviMate: (CheckRow) -> Unit) {
    run {
        Column(Modifier.width(640.dp).heightIn(max = 480.dp).background(Color(0xFF1C222B), RoundedCornerShape(16.dp)).padding(22.dp)) {
            Text("Best stream", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "${state.event.title}. Each provider's stream is tried for a few seconds, one at a time so your connection limits aren't exceeded." +
                    if (state.running) " Testing…" else "",
                color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp),
            )
            when {
                state.error != null -> Text(state.error, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
                state.rows.isEmpty() && !state.running -> Text("None of your providers seem to carry this event.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
                state.rows.isEmpty() -> Text("Finding channels…", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
            }
            val best = state.rows.firstOrNull()?.takeIf { it.rating > 0 }
            LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.rows, key = { it.candidate.channel.key }) { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = { onWatch(row) },
                            modifier = Modifier.weight(1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (row == best) Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                                contentColor = Color.White,
                                focusedContainerColor = Color.White,
                                focusedContentColor = Color(0xFF0B0D12),
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (row == best && !state.running) {
                                        Text(
                                            "BEST", color = Color(0xFF1A1205), fontSize = 8.sp, fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.background(Accent, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        "${row.provider}  ·  ${row.candidate.channel.name}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(row.metrics(state.running), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = 0.7f))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        PillButton("TiviMate", { onTiviMate(row) })
                    }
                }
            }
            Text(
                "Select a row to watch it in HomeBase, or TiviMate to open TiviMate with that provider and channel shown on screen.",
                color = Faint, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
