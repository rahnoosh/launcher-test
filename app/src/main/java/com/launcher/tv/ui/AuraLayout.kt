package com.launcher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.launcher.tv.R
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.Spoilers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A fixed clock for screenshots; null means the real time. */
val LocalFixedNow = staticCompositionLocalOf<Long?> { null }

private val Shade = Shadow(Color(0x66000000), Offset(0f, 2f), 8f)

/**
 * Aura-style home, after github.com/cookedzera/aura-tv (MIT): frosted glass over a daily photograph that
 * follows the sun, a slim sidebar, a greeting and a status pill, one large card for whatever you're on,
 * and rows of glass cards. Same rows, actions and overlays as the other layouts.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AuraLayout(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    initialTab: HomeTab = HomeTab.FOR_YOU,
    initialSelectedKey: String? = null,
    initialQuickSettings: Boolean = false,
    sky: Sky = rememberDailySky(state.weather),
    now: Long? = null,
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

    val overlays = remember { OverlayState(initialQuickSettings) }
    var options by remember { mutableStateOf<Tile?>(null) }
    val listState = rememberLazyListState()
    val firstCard = remember { FocusRequester() }
    var screen by remember { mutableStateOf(IntSize.Zero) }

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

    CompositionLocalProvider(LocalBackdrop provides Backdrop(sky.tiny, screen), LocalFixedNow provides now) {
        Box(Modifier.fillMaxSize().onSizeChanged { screen = it }) {
            SkyBackground(sky)

            Column(Modifier.fillMaxSize().padding(start = 100.dp, end = 36.dp, top = 26.dp)) {
                AuraHeader(shown)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(150.dp)) {
                    Crossfade(targetState = preview, animationSpec = tween(350), label = "hero") { t ->
                        if (t != null) AuraHero(t, shown, Modifier.fillMaxSize())
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).focusRestorer(),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 160.dp),
                ) {
                    itemsIndexed(rows, key = { i, r -> "row-$i-${r.title}" }) { rowIndex, row ->
                        Column {
                            if (row.title.isNotBlank()) {
                                val count = if (tab == HomeTab.APPS && rowIndex == 0) rows.sumOf { it.tiles.size } else row.tiles.size
                                SectionLabel(row.title, count)
                            }
                            LazyRow(
                                modifier = Modifier.focusRestorer(),
                                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp, end = 36.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                itemsIndexed(row.tiles, key = { _, t -> t.key }) { i, tile ->
                                    AuraCard(
                                        tile = tile,
                                        onClick = tile.primaryAction(actions),
                                        onLongClick = { if (tile is Tile.Game) callbacks.openGame(tile.event) else options = tile },
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

            AuraSidebar(
                tab = tab,
                state = state,
                onTab = { tab = it },
                onRefresh = callbacks.refresh,
                onControlCenter = { callbacks.refreshSystemState(); overlays.quick = true },
                onSettings = { callbacks.loadIptvStatus(); overlays.settings = true },
                onAccount = { overlays.account = true },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 22.dp),
            )

            if (sky.hasPhoto && !sky.credit.isNullOrBlank()) {
                Text(
                    sky.credit, color = Color.White.copy(alpha = 0.45f), fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 8.dp).widthIn(max = 420.dp),
                )
            }

            options?.let { t -> OptionsSheet(t, actions, onDismiss = { options = null }) }
            HomeOverlays(state, callbacks, overlays, aura = true)
        }
    }
}

// ---------------- header ----------------

@Composable
private fun AuraHeader(state: HomeUiState) {
    val fixed = LocalFixedNow.current
    var now by remember { mutableStateOf(fixed ?: System.currentTimeMillis()) }
    LaunchedEffect(fixed) {
        if (fixed == null) while (true) { now = System.currentTimeMillis(); delay(15_000) }
    }
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(greeting(now), color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Light, style = LocalTextStyle.current.copy(shadow = Shade))
            Text(longDate(now), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, style = LocalTextStyle.current.copy(shadow = Shade), modifier = Modifier.padding(top = 2.dp))
        }
        val busy = state.busy ?: if (state.refreshing) "Updating…" else null
        if (busy != null) {
            Text(
                busy, color = Color.White, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.padding(end = 10.dp, top = 6.dp).glass(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        StatusPill(state, now)
    }
}

@Composable
private fun StatusPill(state: HomeUiState, now: Long) {
    val live = (state.data.myTeams + state.data.nfl + state.data.fights + state.data.f1 + state.data.events).distinctBy { it.id }.count { it.live }
    Row(
        Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (live > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(LiveRed, CircleShape))
                Text(" $live live", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            PillDivider()
        }
        state.weather?.let { w ->
            Text("${w.symbol}  ${w.display()}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            PillDivider()
        }
        Text(timeText(now), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PillDivider() = Box(Modifier.size(1.dp, 14.dp).background(Color.White.copy(alpha = 0.3f)))

@Composable
private fun SectionLabel(title: String, count: Int) {
    Text(
        "${title.uppercase()}  ·  $count", color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, style = LocalTextStyle.current.copy(shadow = Shade),
        modifier = Modifier.padding(start = 2.dp, bottom = 0.dp),
    )
}

// ---------------- the large card ----------------

@Composable
private fun AuraHero(tile: Tile, state: HomeUiState, modifier: Modifier) {
    val b = billboardInfo(tile, state)
    Box(modifier.glass(RoundedCornerShape(26.dp))) {
        HeroArt(tile, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.55f))
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.62f).padding(horizontal = 26.dp, vertical = 14.dp)) {
            b.eyebrow?.let { HeroEyebrow(it, b.live, tile, state) }
            val g = b.game
            if (g != null) ScoreLine(g) else {
                Text(
                    b.title, color = Color.White, fontSize = if (b.title.length > 26) 21.sp else 25.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = (-0.3).sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (b.meta.isNotEmpty()) {
                Text(b.meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.82f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            b.progress?.let { p ->
                Box(Modifier.padding(top = 12.dp).width(200.dp).height(3.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.02f, 1f)).background(Color.White, RoundedCornerShape(50)))
                }
            }
            if (b.progress == null) b.overview?.let {
                Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            b.hint?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 9.5.sp, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
private fun HeroEyebrow(text: String, live: Boolean, tile: Tile, state: HomeUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = when (tile) {
            is Tile.Resume -> state.apps.firstOrNull { it.packageName == AppsRepository.TIVIMATE }?.icon
            is Tile.App -> tile.app.icon
            else -> null
        }
        when {
            live -> Box(Modifier.size(8.dp).background(LiveRed, CircleShape))
            icon != null -> {
                val bmp = remember(icon) { icon.asImageBitmap() }
                Image(bmp, null, Modifier.size(18.dp))
            }
            tile is Tile.Game -> Box(Modifier.size(8.dp).background(leagueColor(tile.event.league), CircleShape))
            else -> Box(Modifier.size(8.dp).background(Color.White.copy(alpha = 0.8f), CircleShape))
        }
        val label = if (tile is Tile.Resume) "$text  ·  TiviMate" else text
        Text(
            label.uppercase(), color = Color.White.copy(alpha = 0.78f), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 9.dp),
        )
    }
}

/** Away crest, score, home crest, on one line. */
@Composable
private fun ScoreLine(e: SportsEvent) {
    val away = e.away ?: return
    val home = e.home ?: return
    val scores = (e.live || e.finished) && (away.score != null || home.score != null)
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Crest(away, 38.dp)
        Text(away.abbr, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 9.dp))
        Text(
            if (scores) "  ${away.score ?: 0}  –  ${home.score ?: 0}  " else "   @   ",
            color = Color.White, fontSize = if (scores) 27.sp else 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
        )
        Text(home.abbr, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 9.dp))
        Crest(home, 38.dp)
    }
}

/** Artwork on the right of the large card, fading into the glass. */
@Composable
private fun HeroArt(tile: Tile, modifier: Modifier) {
    val faded = modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.Black), blendMode = BlendMode.DstIn)
        }
    when (tile) {
        is Tile.Resume -> (tile.entry.imageUrl ?: tile.entry.posterUrl)?.let { AsyncImage(it, null, faded, contentScale = ContentScale.Crop) }
        is Tile.Media -> (tile.item.imageUrl ?: tile.item.posterUrl)?.let { AsyncImage(it, null, faded, contentScale = ContentScale.Crop) }
        is Tile.Game -> {
            val e = tile.event
            val home = e.home
            val away = e.away
            Box(faded) {
                if (home != null && away != null) {
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(away.mainColor().copy(alpha = 0.55f), home.mainColor().copy(alpha = 0.55f)))))
                    Row(Modifier.align(Alignment.Center).padding(start = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                        Crest(away, 82.dp)
                        Spacer(Modifier.width(26.dp))
                        Crest(home, 82.dp)
                    }
                } else {
                    val c = leagueColor(e.league)
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.2f), c.copy(alpha = 0.6f)))))
                    if (e.imageUrl != null) AsyncImage(e.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
                    Text(
                        e.league.uppercase(), Modifier.align(Alignment.Center).padding(start = 60.dp),
                        color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp,
                    )
                }
            }
        }
        is Tile.App -> tile.app.banner?.let { banner ->
            val bmp = remember(banner) { banner.asImageBitmap() }
            Image(bmp, null, faded, contentScale = ContentScale.Crop)
        }
        is Tile.NowPlaying -> Box(faded.background(Brush.horizontalGradient(listOf(Color(0x001B4D7A), Color(0xCC1B4D7A))))) {
            Text("▶", Modifier.align(Alignment.Center).padding(start = 60.dp), color = Color.White, fontSize = 54.sp)
        }
        Tile.EditTeams -> Box(faded) {
            Icon(painterResource(R.drawable.ic_trophy), null, Modifier.align(Alignment.Center).padding(start = 60.dp).size(84.dp), tint = Color.White.copy(alpha = 0.85f))
        }
        Tile.ConnectTrakt -> Box(faded.background(Brush.horizontalGradient(listOf(Color(0x005B1B7A), Color(0xCC5B1B7A)))))
        else -> Unit
    }
}

// ---------------- cards ----------------

@Composable
private fun AuraCard(tile: Tile, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.size(176.dp, 99.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            scale = CardDefaults.scale(focusedScale = 1.07f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)),
            glow = CardDefaults.glow(),
        ) {
            Box(Modifier.fillMaxSize().glass(shape, frost = if (focused) GlassFrostFocused else GlassFrost)) { AuraCardFace(tile) }
        }
        // Like Aura: the focused app's name under it.
        if (tile is Tile.App) {
            Text(
                if (focused) tile.title else "", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).height(15.dp).widthIn(max = 176.dp),
            )
        }
    }
}

private val cardScrim = Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xD9000000))

@Composable
private fun AuraCardFace(tile: Tile) {
    Box(Modifier.fillMaxSize()) {
        when (tile) {
            is Tile.Resume -> {
                val art = tile.entry.imageUrl ?: tile.entry.posterUrl
                CardArt(art, tile.title)
                CardCaption(tile.title, tile.entry.subtitle, Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp), large = art == null)
                if (tile.entry.progress > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).height(2.5.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(tile.entry.progress.coerceIn(0.02f, 1f)).background(Color.White, RoundedCornerShape(50)))
                    }
                }
            }
            is Tile.Media -> {
                val art = tile.item.imageUrl ?: tile.item.posterUrl
                CardArt(art, tile.title)
                CardCaption(tile.title, tile.subtitle, Modifier.align(Alignment.BottomStart), large = art == null)
                if (tile.isNew) Tag("NEW", Accent, Color(0xFF1A1205), Modifier.align(Alignment.TopStart))
                if (tile.role == Tile.Role.UPCOMING) Tag("SOON", Color.Black.copy(alpha = 0.55f), Color.White, Modifier.align(Alignment.TopStart))
            }
            is Tile.Game -> GameGlass(tile.event)
            is Tile.App -> {
                val banner = tile.app.banner
                if (banner != null) {
                    val bmp = remember(banner) { banner.asImageBitmap() }
                    Image(bmp, tile.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        tile.app.icon?.let { icon ->
                            val bmp = remember(icon) { icon.asImageBitmap() }
                            Image(bmp, null, Modifier.size(42.dp))
                        }
                        Text(tile.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Tile.AllApps -> Icon(painterResource(R.drawable.ic_grid), "All apps", Modifier.align(Alignment.Center).size(30.dp), tint = Color.White)
            Tile.EditTeams -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("+", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
                Text("Add or remove teams", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Tile.ConnectTrakt -> {
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x995B1B7A), Color.Transparent))))
                Text("Connect Trakt\nor Simkl", Modifier.align(Alignment.Center), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            is Tile.NowPlaying -> {
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x991B6DCC), Color.Transparent))))
                CardCaption("▶  Now playing", tile.title, Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp))
            }
            is Tile.Message -> Text(tile.title, Modifier.align(Alignment.Center).padding(8.dp), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

/** A game on glass: the teams' colours glowing from each side, crests and the score in the middle. */
@Composable
private fun BoxScope.GameGlass(e: SportsEvent) {
    val home = e.home
    val away = e.away
    if (home != null && away != null) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to away.mainColor().copy(alpha = 0.5f), 0.45f to Color.Transparent, 0.55f to Color.Transparent, 1f to home.mainColor().copy(alpha = 0.5f))))
        Row(Modifier.align(Alignment.Center).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Crest(away, 34.dp)
            val scores = (e.live || e.finished) && (away.score != null || home.score != null)
            Text(
                if (scores) "${away.score ?: 0} – ${home.score ?: 0}" else "@",
                color = Color.White, fontSize = if (scores) 17.sp else 14.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Crest(home, 34.dp)
        }
    } else {
        val c = leagueColor(e.league)
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent))))
        if (e.imageUrl != null) AsyncImage(e.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.45f)
        Text(
            e.league.uppercase(), Modifier.align(Alignment.Center).padding(bottom = 16.dp),
            color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold,
        )
    }
    CardCaption(
        if (home != null) e.title else e.title.substringBefore(":"),
        when {
            e.live -> (if (home != null) Spoilers.stripScore(e.status) else e.status) ?: "Live"
            e.finished -> if (home != null && home.score == null) "Final · score hidden" else "Final"
            else -> friendlyTime(e.startMillis)
        },
        Modifier.align(Alignment.BottomStart),
    )
    if (e.live) Tag("LIVE", LiveRed, Color.White, Modifier.align(Alignment.TopStart))
    if (e.finished) Tag("FINAL", Color.Black.copy(alpha = 0.5f), Color.White, Modifier.align(Alignment.TopStart))
}

/** Artwork with a shade for the caption; without artwork the card stays plain glass. */
@Composable
private fun CardArt(url: String?, title: String) {
    if (url == null) return
    AsyncImage(url, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(cardScrim))
}

@Composable
private fun CardCaption(title: String, subtitle: String, modifier: Modifier, large: Boolean = false) {
    Column(modifier.padding(horizontal = 11.dp, vertical = 7.dp)) {
        Text(
            title, color = Color.White, fontSize = if (large) 15.sp else 11.5.sp, fontWeight = if (large) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = if (large) 18.sp else 14.sp,
        )
        if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Text(
        text, modifier = modifier.padding(8.dp).background(bg, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
        color = fg, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
    )
}

// ---------------- sidebar ----------------

@Composable
private fun AuraSidebar(
    tab: HomeTab,
    state: HomeUiState,
    onTab: (HomeTab) -> Unit,
    onRefresh: () -> Unit,
    onControlCenter: () -> Unit,
    onSettings: () -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.width(56.dp).glass(RoundedCornerShape(28.dp)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SideItem(R.drawable.ic_home, "Home", tab == HomeTab.FOR_YOU) { onTab(HomeTab.FOR_YOU) }
        SideItem(R.drawable.ic_tv, "TV & Movies", tab == HomeTab.TV) { onTab(HomeTab.TV) }
        SideItem(R.drawable.ic_trophy, "Sports", tab == HomeTab.SPORTS) { onTab(HomeTab.SPORTS) }
        SideItem(R.drawable.ic_grid, "Apps", tab == HomeTab.APPS) { onTab(HomeTab.APPS) }
        Box(Modifier.padding(vertical = 3.dp).size(24.dp, 1.dp).background(Color.White.copy(alpha = 0.25f)))
        SideItem(R.drawable.ic_refresh, "Refresh", false, onRefresh)
        SideItem(R.drawable.ic_tune, "Control Center", false, onControlCenter)
        SideItem(R.drawable.ic_settings, "Settings", false, onSettings)
        val badge = (if (state.trakt == TraktState.SignedIn) "T" else "") + (if (state.simkl == SimklState.SignedIn) "S" else "")
        Surface(
            onClick = onAccount,
            modifier = Modifier.size(40.dp),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(26.dp).background(Brush.linearGradient(listOf(Color(0xFFC9B6FF), Color(0xFF7FB2FF))), CircleShape), contentAlignment = Alignment.Center) {
                    Text(badge.ifEmpty { "+" }, color = Color(0xFF0B0D12), fontSize = if (badge.length > 1) 9.sp else 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SideItem(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B0D12),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(painterResource(icon), label, Modifier.size(18.dp)) }
    }
}

// ---------------- time ----------------

private fun zoned(now: Long) = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())

fun greeting(now: Long): String = when (zoned(now).hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

fun longDate(now: Long): String {
    val pattern = if (Locale.getDefault().country == "US") "EEEE, MMMM d" else "EEEE, d MMMM"
    return DateTimeFormatter.ofPattern(pattern).format(zoned(now))
}

fun timeText(now: Long): String =
    DateTimeFormatter.ofPattern(if (Locale.getDefault().country in setOf("US", "CA", "AU", "IN", "PH")) "h:mm a" else "HH:mm").format(zoned(now))
