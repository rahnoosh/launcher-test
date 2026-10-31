package com.launcher.tv.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.player.PlayerActivity
import kotlinx.coroutines.delay

private val Background = Color(0xFF0E1116)

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val data = state.data
    val openTiviMate = { vm.launchPackage(AppsRepository.TIVIMATE) }

    BackHandler { /* a launcher has nowhere to go back to */ }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        LazyColumn(
            contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item { Header(state, vm) }

            // ---- What you played in TiviMate (via the HomeBase player bridge) ----
            if (state.history.isNotEmpty()) row("Recently watched in TiviMate") {
                items(state.history.take(20), key = { it.url }) { entry ->
                    HistoryCard(
                        entry,
                        onClick = { context.startActivity(PlayerActivity.intent(context, entry)) },
                        onLongClick = { vm.removeFromHistory(entry) },
                    )
                }
            }

            // ---- TV & movies (Trakt) ----
            when (state.trakt) {
                TraktState.NotConfigured -> row("Continue watching") {
                    item { EmptyNote("Add your Trakt API keys to local.properties and rebuild (see README).") }
                }
                TraktState.SignedOut, is TraktState.AwaitingCode -> row("Continue watching") {
                    item { ActionCard("Connect Trakt", "See your next episodes and watchlist", vm::connectTrakt) }
                }
                TraktState.SignedIn -> {
                    row("Continue watching") {
                        if (data.upNext.isEmpty()) item { EmptyNote(if (state.refreshing) "Loading…" else "You're all caught up.") }
                        items(data.upNext, key = { it.id }) { WideMediaCard(it, "NEXT UP", openTiviMate) }
                    }
                    if (data.upcomingEpisodes.isNotEmpty()) row("New episodes coming up") {
                        items(data.upcomingEpisodes, key = { it.id }) {
                            WideMediaCard(it, it.timeMillis?.let(::friendlyTime), openTiviMate)
                        }
                    }
                }
            }

            // ---- Sports ----
            row("Your teams") {
                if (data.myTeams.isEmpty()) item { EmptyNote(if (state.refreshing) "Loading…" else "No upcoming games found.") }
                items(data.myTeams, key = { it.id }) { SportsCard(it, openTiviMate) }
            }
            if (data.fights.isNotEmpty()) row("Fight night · UFC & boxing") {
                items(data.fights, key = { it.id }) { SportsCard(it, openTiviMate) }
            }
            if (data.nfl.isNotEmpty()) row("NFL this week") {
                items(data.nfl, key = { it.id }) { SportsCard(it, openTiviMate) }
            }
            if (data.f1.isNotEmpty()) row("Formula 1") {
                items(data.f1, key = { it.id }) { SportsCard(it, openTiviMate) }
            }

            // ---- Movies ----
            if (state.trakt == TraktState.SignedIn && data.watchlist.isNotEmpty()) row("Movie watchlist") {
                items(data.watchlist, key = { it.id }) { PosterCard(it, openTiviMate) }
            }

            // ---- Apps ----
            row("Apps") {
                items(state.apps, key = { it.packageName }) { app -> AppCard(app) { vm.launchPackage(app.packageName) } }
            }
        }

        (state.trakt as? TraktState.AwaitingCode)?.let { TraktCodeDialog(it, vm::cancelTraktAuth) }
    }
}

private fun LazyListScope.row(title: String, content: LazyListScope.() -> Unit) {
    item(key = "row-$title") {
        Column {
            Text(
                title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun Header(state: HomeUiState, vm: HomeViewModel) {
    var clock by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = clockText()
            delay(10_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("HomeBase", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (state.refreshing) Text("  updating…", color = Color(0xFF777777), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { vm.refresh(force = true) }) { Text("Refresh") }
        Spacer(Modifier.width(12.dp))
        if (state.trakt == TraktState.SignedIn) {
            OutlinedButton(onClick = vm::signOutTrakt) { Text("Sign out of Trakt") }
            Spacer(Modifier.width(24.dp))
        }
        Text(clock, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun TraktCodeDialog(s: TraktState.AwaitingCode, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.background(Color(0xFF1C222B), RoundedCornerShape(16.dp)).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connect Trakt", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("On your phone, go to", color = Color(0xFFCCCCCC), modifier = Modifier.padding(top = 16.dp))
            Text(s.url, color = Accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("and enter this code:", color = Color(0xFFCCCCCC), modifier = Modifier.padding(top = 12.dp))
            Text(s.userCode, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
            Button(onClick = onCancel, modifier = Modifier.padding(top = 20.dp)) { Text("Cancel") }
        }
    }
}

@Composable
fun HomeBaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme(primary = Accent), content = content)
}
