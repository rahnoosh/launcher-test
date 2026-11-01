package com.launcher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.launcher.tv.R
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.Spoilers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val RowsBg = Color(0xFF0E0E10)

/**
 * Streaming-app style home: a navigation rail on the left, a large preview of the selected item on
 * top, and rows of wide cards below. OK plays / watches; holding OK opens more options.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RowsLayout(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    initialTab: HomeTab = HomeTab.FOR_YOU,
    initialSelectedKey: String? = null,
    initialRailOpen: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val shown = remember(state) { state.copy(data = Spoilers.apply(state.data, state.spoilers, state.revealed)) }
    val rows = remember(tab, shown) { buildRows(tab, shown) }
    val allTiles = remember(rows) { rows.flatMap { it.tiles } }

    var selectedKey by remember { mutableStateOf(initialSelectedKey) }
    val selected = allTiles.firstOrNull { it.key == selectedKey } ?: allTiles.firstOrNull()
    var preview by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (preview != null && preview?.key != selected?.key) delay(200)
        preview = selected
    }

    val overlays = remember { OverlayState() }
    var options by remember { mutableStateOf<Tile?>(null) }
    var railFocused by remember { mutableStateOf(initialRailOpen) }
    val listState = rememberLazyListState()
    val firstCard = remember { FocusRequester() }

    val actions = remember(callbacks, state.iptv, state.watchApps, state.watchApp) {
        HomeActions(
            openTiviMate = { callbacks.launchPackage(AppsRepository.TIVIMATE) },
            play = callbacks.play,
            removeHistory = callbacks.removeFromHistory,
            launchApp = callbacks.launchPackage,
            showApps = { tab = HomeTab.APPS },
            connectTrakt = { overlays.account = true },
            watchGame = { e, startOver -> callbacks.watchGame(e, null, startOver) },
            pickChannel = callbacks.openPicker,
            playMedia = callbacks.playMedia,
            simklPage = { overlays.simklLink = it },
            pipFullScreen = callbacks.pipFullScreen,
            pipStop = callbacks.pipStop,
            playHere = callbacks.playHere,
            watchIn = callbacks.watchIn,
            watchApps = state.watchApps,
            preferredApp = state.watchApps.firstOrNull { it.key == state.watchApp },
            openGame = callbacks.openGame,
            highlights = callbacks.highlights,
            revealScore = callbacks.revealScore,
            compareProviders = callbacks.compareProviders,
            editTeams = callbacks.editTeams,
            iptv = state.iptv,
        )
    }

    fun focusFirst() {
        scope.launch {
            listState.scrollToItem(0)
            runCatching { firstCard.requestFocus() }
        }
    }

    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler { selectedKey = null; focusFirst() }
        BackHandler(enabled = overlays.quick) { overlays.quick = false }
    }
    var firstTabEffect by remember { mutableStateOf(true) }
    LaunchedEffect(tab) {
        if (firstTabEffect) { firstTabEffect = false; delay(150) }
        selectedKey = null
        listState.scrollToItem(0)
        runCatching { firstCard.requestFocus() }
    }
    LaunchedEffect(preview?.key, state.iptv) {
        (preview as? Tile.Game)?.let { callbacks.resolveChannels(it.event) }
    }

    Box(Modifier.fillMaxSize().background(RowsBg)) {
        // Preview artwork across the top, fading into the rows.
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.72f).align(Alignment.TopEnd)) {
            Crossfade(targetState = preview, animationSpec = tween(450), label = "preview") { HeroBackground(it) }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to RowsBg, 0.28f to RowsBg.copy(alpha = 0.85f), 0.6f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to RowsBg)))
        }

        Column(Modifier.fillMaxSize().padding(start = 84.dp)) {
            // Preview text
            Box(Modifier.fillMaxWidth().height(286.dp).padding(top = 34.dp, end = 44.dp)) {
                preview?.let { Billboard(it, state, Modifier.align(Alignment.CenterStart).padding(bottom = 20.dp)) }
                TopRight(state, Modifier.align(Alignment.TopEnd))
            }
            // Rows
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).focusRestorer(),
                contentPadding = PaddingValues(bottom = 200.dp),
            ) {
                itemsIndexed(rows, key = { i, r -> "row-$i-${r.title}" }) { rowIndex, row ->
                    Column(Modifier.padding(bottom = 6.dp)) {
                        if (row.title.isNotBlank()) {
                            Text(row.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        LazyRow(
                            modifier = Modifier.focusRestorer(),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp, end = 44.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(row.tiles, key = { _, t -> t.key }) { i, tile ->
                                RowCard(
                                    tile = tile,
                                    onClick = tile.primaryAction(actions),
                                    onLongClick = {
                                        if (tile is Tile.Game) callbacks.openGame(tile.event) else options = tile
                                    },
                                    modifier = (if (rowIndex == 0 && i == 0) Modifier.focusRequester(firstCard) else Modifier)
                                        .onFocusChanged {
                                            if (it.isFocused || it.hasFocus) {
                                                selectedKey = tile.key
                                                scope.launch { listState.animateScrollToItem(rowIndex) }
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }

        NavRail(
            tab = tab,
            expanded = railFocused || initialRailOpen,
            onFocus = { railFocused = it },
            onTab = { tab = it },
            onSettings = { callbacks.refreshSystemState(); overlays.quick = true },
            onRefresh = callbacks.refresh,
        )

        options?.let { t -> OptionsSheet(t, actions, onDismiss = { options = null }) }
        HomeOverlays(state, callbacks, overlays)
    }
}

// ---------------- preview ----------------

@Composable
private fun Billboard(tile: Tile, state: HomeUiState, modifier: Modifier) {
    val b = billboardInfo(tile, state)
    Column(modifier.widthIn(max = 520.dp)) {
        b.eyebrow?.let { Eyebrow(it, live = b.live) }
        val g = b.game
        if (g != null) Scoreboard(g) else Title(b.title)
        Meta(b.meta)
        b.progress?.let { Box(Modifier.padding(top = 10.dp).width(220.dp)) { ProgressBar(it) } }
        b.overview?.let { Overview(it) }
        b.hint?.let { Hint(it) }
    }
}

@Composable
private fun Overview(text: String) {
    Text(text, color = Dim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).widthIn(max = 470.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Faint, fontSize = 10.sp, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun TopRight(state: HomeUiState, modifier: Modifier) {
    var clock by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) {
        while (true) { clock = clockText(); delay(10_000) }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val busy = state.busy ?: if (state.refreshing) "updating…" else null
        if (busy != null) {
            Text(
                busy, color = Color.White, fontSize = 10.sp,
                modifier = Modifier.padding(end = 12.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(clock, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- cards ----------------

@Composable
private fun RowCard(tile: Tile, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(196.dp, 110.dp),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Color(0xFF1E1E22), focusedContainerColor = Color(0xFF1E1E22)),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)),
        glow = CardDefaults.glow(),
    ) {
        Box(Modifier.fillMaxSize()) { RowCardFace(tile) }
    }
}

private val bottomScrim = Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))

@Composable
private fun RowCardFace(tile: Tile) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (tile) {
            is Tile.Resume -> {
                Art(tile.entry.imageUrl ?: tile.entry.posterUrl, tile.title)
                Caption(tile.title, tile.entry.subtitle, Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp))
                ProgressBar(tile.entry.progress, Modifier.align(Alignment.BottomStart))
            }
            is Tile.Media -> {
                Art(tile.item.imageUrl ?: tile.item.posterUrl, tile.title)
                Caption(tile.title, tile.subtitle, Modifier.align(Alignment.BottomStart))
                if (tile.isNew) SmallBadge("NEW", Accent, Color(0xFF1A1205), Modifier.align(Alignment.TopStart))
                if (tile.role == Tile.Role.UPCOMING) SmallBadge("SOON", Color.Black.copy(alpha = 0.65f), Color.White, Modifier.align(Alignment.TopStart))
            }
            is Tile.Game -> GameCardFace(tile)
            is Tile.App -> {
                val banner = tile.app.banner
                if (banner != null) {
                    val bmp = remember(banner) { banner.asImageBitmap() }
                    Image(bmp, tile.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B313D), Color(0xFF161A22)))))
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        tile.app.icon?.let { icon ->
                            val bmp = remember(icon) { icon.asImageBitmap() }
                            Image(bmp, null, Modifier.size(40.dp))
                        }
                        Text(tile.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Tile.AllApps -> {
                Box(Modifier.fillMaxSize().background(Color(0xFF22262E)))
                Icon(painterResource(R.drawable.ic_grid), "All apps", Modifier.align(Alignment.Center).size(34.dp), tint = Color.White)
            }
            Tile.EditTeams -> {
                Box(Modifier.fillMaxSize().background(Color(0xFF22262E)))
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+", color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Light)
                    Text("Add or remove teams", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Tile.ConnectTrakt -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF5B1B7A), Color(0xFF1A0A24)))))
                Text("Connect Trakt\nor Simkl", Modifier.align(Alignment.Center), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            is Tile.NowPlaying -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B4D7A), Color(0xFF0B1A2A)))))
                Caption("▶  Now playing", tile.title, Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp))
            }
            is Tile.Message -> {
                Box(Modifier.fillMaxSize().background(Color(0xFF1C1C20)))
                Text(tile.title, Modifier.align(Alignment.Center), color = Dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun GameCardFace(tile: Tile.Game) {
    val e = tile.event
    val home = e.home
    val away = e.away
    Box(Modifier.fillMaxSize()) {
        if (home != null && away != null) {
            val a = away.mainColor()
            val h = home.mainColor()
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(0f to a.darker(0.15f), 0.5f to a.darker(0.35f), 0.5f to h.darker(0.35f), 1f to h.darker(0.15f))))
            Row(Modifier.align(Alignment.Center).padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Crest(away, 36.dp)
                val scores = (e.live || e.finished) && (away.score != null || home.score != null)
                Text(
                    if (scores) "${away.score ?: 0} – ${home.score ?: 0}" else "@",
                    color = Color.White, fontSize = if (scores) 17.sp else 15.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                Crest(home, 36.dp)
            }
        } else {
            val c = leagueColor(e.league)
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c, c.darker(0.7f)))))
            if (e.imageUrl != null) AsyncImage(e.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.5f)
            Text(
                e.league.uppercase(), Modifier.align(Alignment.Center).padding(bottom = 16.dp),
                color = if (e.league == "UFC" || e.league == "F1") Color(0xFFFF4A3A) else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black,
            )
        }
        Box(Modifier.fillMaxSize().background(bottomScrim))
        Caption(
            if (home != null) e.title else e.title.substringBefore(":"),
            when {
                e.live -> (if (home != null) Spoilers.stripScore(e.status) else e.status) ?: "Live"
                e.finished -> if (home != null && home.score == null) "Final · score hidden" else "Final"
                else -> friendlyTime(e.startMillis)
            },
            Modifier.align(Alignment.BottomStart),
        )
        if (e.live) SmallBadge("LIVE", LiveRed, Color.White, Modifier.align(Alignment.TopStart))
        if (e.finished) SmallBadge("FINAL", Color.Black.copy(alpha = 0.7f), Color.White, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Art(url: String?, title: String) {
    if (url != null) {
        AsyncImage(url, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        val c = seedColor(title)
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(c, Color.White, 0.12f), c.darker(0.6f)))))
        Text(
            title.uppercase(), Modifier.align(Alignment.Center).padding(horizontal = 10.dp).padding(bottom = 18.dp),
            color = Color(0xFFFFF3DD), fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
    Box(Modifier.fillMaxSize().background(bottomScrim))
}

@Composable
private fun Caption(title: String, subtitle: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(title, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.72f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallBadge(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Text(
        text, modifier = modifier.padding(6.dp).background(bg, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        color = fg, fontSize = 7.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp,
    )
}

// ---------------- navigation rail ----------------

@Composable
private fun NavRail(
    tab: HomeTab,
    expanded: Boolean,
    onFocus: (Boolean) -> Unit,
    onTab: (HomeTab) -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val width by animateDpAsState(if (expanded) 210.dp else 64.dp, tween(180), label = "rail")
    Box(
        Modifier.fillMaxHeight().width(if (expanded) 420.dp else 64.dp)
            .background(
                if (expanded) Brush.horizontalGradient(0f to Color(0xF2000000), 0.5f to Color(0xCC000000), 1f to Color.Transparent)
                else Brush.horizontalGradient(listOf(Color(0x99000000), Color.Transparent))
            ),
    ) {
        Column(
            Modifier.fillMaxHeight().width(width).padding(vertical = 30.dp, horizontal = 10.dp)
                .onFocusChanged { onFocus(it.hasFocus) },
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            RailItem(R.drawable.ic_home, "Home", tab == HomeTab.FOR_YOU, expanded) { onTab(HomeTab.FOR_YOU) }
            RailItem(R.drawable.ic_tv, "TV & Movies", tab == HomeTab.TV, expanded) { onTab(HomeTab.TV) }
            RailItem(R.drawable.ic_trophy, "Sports", tab == HomeTab.SPORTS, expanded) { onTab(HomeTab.SPORTS) }
            RailItem(R.drawable.ic_grid, "Apps", tab == HomeTab.APPS, expanded) { onTab(HomeTab.APPS) }
            Spacer(Modifier.height(18.dp))
            RailItem(R.drawable.ic_refresh, "Refresh", false, expanded, onRefresh)
            RailItem(R.drawable.ic_settings, "Quick settings", false, expanded, onSettings)
        }
    }
}

@Composable
private fun RailItem(icon: Int, label: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(38.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B0D12),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), label, Modifier.size(20.dp))
            if (expanded) {
                Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 14.dp))
            }
        }
    }
}

// ---------------- more options (hold OK) ----------------

@Composable
internal fun OptionsSheet(tile: Tile, actions: HomeActions, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Color(0xFF1C1C20), RoundedCornerShape(16.dp)).padding(24.dp)) {
            Text(tile.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tile.subtitle.isNotBlank()) Text(tile.subtitle, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fun go(f: () -> Unit): () -> Unit = { onDismiss(); f() }
                when (tile) {
                    is Tile.Resume -> {
                        PillButton("Resume", go { actions.play(tile.entry) }, primary = true, icon = R.drawable.ic_play)
                        PillButton("Remove from Continue Watching", go { actions.removeHistory(tile.entry) })
                    }
                    is Tile.Media -> {
                        val chosen = actions.preferredApp
                        if (tile.role != Tile.Role.UPCOMING) {
                            PillButton(chosen?.let { "Play in ${it.label}" } ?: "Play", go { actions.playMedia(tile.item) }, primary = true, icon = R.drawable.ic_play)
                            actions.watchApps.filter { it != chosen }.forEach { app -> PillButton("Watch in ${app.label}", go { actions.watchIn(tile.item, app) }) }
                            if (chosen != null && actions.iptv) PillButton("Play from your provider", go { actions.playHere(tile.item) })
                        }
                        PillButton("Open TiviMate", go(actions.openTiviMate))
                        if (tile.item.simklUrl != null) PillButton("View on Simkl", go { actions.simklPage(tile.item) })
                    }
                    is Tile.App -> PillButton("Open", go { actions.launchApp(tile.app.packageName) }, primary = true)
                    is Tile.NowPlaying -> {
                        PillButton("Full screen", go(actions.pipFullScreen), primary = true, icon = R.drawable.ic_play)
                        PillButton("Stop", go(actions.pipStop))
                    }
                    else -> PillButton("Close", onDismiss, primary = true)
                }
                PillButton("Cancel", onDismiss)
            }
        }
    }
}
