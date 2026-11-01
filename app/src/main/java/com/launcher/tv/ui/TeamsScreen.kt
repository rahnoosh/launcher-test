package com.launcher.tv.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.launcher.tv.data.sports.EventFollow
import com.launcher.tv.data.sports.FollowConfig
import com.launcher.tv.data.sports.FollowStore
import com.launcher.tv.data.sports.FollowedTeam
import com.launcher.tv.data.sports.Leagues
import com.launcher.tv.data.sports.SportsRepository
import com.launcher.tv.data.sports.TeamLeague
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Teams screen: follow teams from any supported league, plus fights, F1, golf and tennis. */
class TeamsActivity : ComponentActivity() {
    private var config by mutableStateOf(FollowConfig())
    private var league by mutableStateOf(Leagues.all.first())
    private var teams by mutableStateOf<List<FollowedTeam>?>(null)
    private var error by mutableStateOf<String?>(null)
    private var query by mutableStateOf("")
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = FollowStore.load(this)
        config.teams.firstOrNull()?.let { t -> Leagues.byKey(t.league)?.let { league = it } }
        loadTeams()
        setContent {
            HomeBaseTheme {
                TeamsScreen(
                    config = config,
                    league = league,
                    teams = teams?.filter { query.isBlank() || it.name.contains(query, true) || it.abbr.equals(query, true) },
                    error = error,
                    query = query,
                    onLeague = { league = it; query = ""; loadTeams() },
                    onToggleTeam = { t ->
                        FollowStore.setTeam(this, t, !config.isFollowed(t.league, t.id))
                        config = FollowStore.load(this)
                    },
                    onToggleEvent = { e ->
                        FollowStore.setEvent(this, e.key, !config.follows(e))
                        config = FollowStore.load(this)
                    },
                    onGamesPerTeam = {
                        FollowStore.setGamesPerTeam(this, config.gamesPerTeam % 3 + 1)
                        config = FollowStore.load(this)
                    },
                    onSearch = ::askSearch,
                    onClearSearch = { query = "" },
                )
            }
        }
    }

    private fun loadTeams() {
        job?.cancel()
        teams = null
        error = null
        val l = league
        job = lifecycleScope.launch {
            runCatching { SportsRepository.teams(l) }
                .onSuccess { teams = it; if (it.isEmpty()) error = "No teams found for ${l.label}" }
                .onFailure { teams = emptyList(); error = "Couldn't load ${l.label} teams: ${it.message}" }
        }
    }

    private fun askSearch() {
        val input = EditText(this).apply {
            hint = "Team or city"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setText(query)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Search ${league.label} teams")
            .setView(input)
            .setPositiveButton("Search") { _, _ -> query = input.text.toString().trim() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

@Composable
fun TeamsScreen(
    config: FollowConfig,
    league: TeamLeague,
    teams: List<FollowedTeam>?,
    error: String?,
    query: String,
    onLeague: (TeamLeague) -> Unit,
    onToggleTeam: (FollowedTeam) -> Unit,
    onToggleEvent: (EventFollow) -> Unit,
    onGamesPerTeam: () -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0E0E10)).padding(horizontal = 36.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Your teams", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Text("Changes show on Home right away  ·  BACK when you're done", color = Faint, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        }

        // Followed teams: select one to remove it.
        LazyRow(Modifier.padding(top = 10.dp).height(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (config.teams.isEmpty()) item { Text("No teams yet. Pick some below.", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
            items(config.teams, key = { it.key }) { t ->
                Chip(selected = true, onClick = { onToggleTeam(t) }) {
                    Crest(t.info(), 24.dp)
                    Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                    Text("  ✕", fontSize = 11.sp, color = LocalContentColor.current.copy(alpha = 0.6f))
                }
            }
        }

        // Other things to follow.
        LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EventFollow.entries) { e ->
                val on = config.follows(e)
                Chip(selected = on, onClick = { onToggleEvent(e) }) {
                    Text((if (on) "✓  " else "+  ") + e.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                Chip(selected = false, onClick = onGamesPerTeam) {
                    Text("Games per team: ${config.gamesPerTeam}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(Modifier.padding(top = 16.dp).fillMaxSize()) {
            // Leagues
            LazyColumn(Modifier.width(200.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(Leagues.all, key = { it.key }) { l ->
                    val count = config.teams.count { it.league == l.key }
                    Surface(
                        onClick = { onLeague(l) },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (l == league) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                            contentColor = if (l == league) Color.White else Dim,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color(0xFF0B0D12),
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(l.label, fontSize = 13.sp, fontWeight = if (l == league) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (count > 0) Text("$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Accent)
                        }
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
            // Teams of the selected league
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        league.label + (teams?.let { "  ·  ${it.size} teams" } ?: ""),
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    PillButton(if (query.isBlank()) "Search" else "Search: $query", onSearch)
                    if (query.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        PillButton("Clear", onClearSearch)
                    }
                }
                when {
                    error != null -> Text(error, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
                    teams == null -> Text("Loading teams…", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.padding(top = 10.dp).fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(teams, key = { it.key }) { t ->
                            val on = config.isFollowed(t.league, t.id)
                            Surface(
                                onClick = { onToggleTeam(t) },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (on) Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                                    contentColor = Color.White,
                                    focusedContainerColor = Color.White,
                                    focusedContentColor = Color(0xFF0B0D12),
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                            ) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Crest(t.info(), 30.dp)
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(t.abbr, fontSize = 9.sp, color = LocalContentColor.current.copy(alpha = 0.6f))
                                    }
                                    if (on) Text("✓", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Accent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B0D12),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}
