package com.launcher.tv.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.tv.material3.LocalContentColor
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.launcher.tv.R
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.history.HistoryEntry
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.iptv.ChannelCandidate
import com.launcher.tv.data.iptv.CheckRow
import com.launcher.tv.data.Spoilers
import android.content.Intent
import android.provider.Settings
import com.launcher.tv.player.PlayerActivity
import com.launcher.tv.dream.ScreensaverActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PS5-style home: tabs and a row of tiles across the top, the selected tile's artwork filling
 * the screen behind its title and buttons, and a "Coming up" strip along the bottom.
 */
/** Everything the home screen can ask the app to do. */
class HomeCallbacks(
    val refresh: () -> Unit = {},
    val connectTrakt: () -> Unit = {},
    val cancelTraktAuth: () -> Unit = {},
    val signOutTrakt: () -> Unit = {},
    val connectSimkl: () -> Unit = {},
    val cancelSimklAuth: () -> Unit = {},
    val signOutSimkl: () -> Unit = {},
    val launchPackage: (String) -> Unit = {},
    val removeFromHistory: (HistoryEntry) -> Unit = {},
    val play: (HistoryEntry) -> Unit = {},
    val watchGame: (SportsEvent, ChannelCandidate?, Boolean) -> Unit = { _, _, _ -> },
    val resolveChannels: (SportsEvent) -> Unit = {},
    val openPicker: (SportsEvent) -> Unit = {},
    val closePicker: () -> Unit = {},
    val playMedia: (MediaItem) -> Unit = {},
    val playHere: (MediaItem) -> Unit = {},
    val watchIn: (MediaItem, com.launcher.tv.player.WatchApp) -> Unit = { _, _ -> },
    val setWatchApp: (String) -> Unit = {},
    val addXtream: (String, String, String) -> Unit = { _, _, _ -> },
    val addM3u: (String, String) -> Unit = { _, _ -> },
    val removeSource: (String) -> Unit = {},
    val loadIptvStatus: () -> Unit = {},
    val setAlerts: (Boolean) -> Unit = {},
    val setScoringAlerts: (Boolean) -> Unit = {},
    val openAndroidSettings: () -> Unit = {},
    val openSystemSettings: (QuickAction) -> Unit = {},
    val openGame: (SportsEvent) -> Unit = {},
    val highlights: (SportsEvent) -> Unit = {},
    val cycleSleepTimer: () -> Unit = {},
    val previewScreensaver: () -> Unit = {},
    val refreshSystemState: () -> Unit = {},
    val setSpoilers: (Boolean) -> Unit = {},
    val revealScore: (SportsEvent) -> Unit = {},
    val compareProviders: (SportsEvent) -> Unit = {},
    val closeStreamCheck: () -> Unit = {},
    val watchFromCheck: (CheckRow) -> Unit = {},
    val tiviMateFromCheck: (CheckRow) -> Unit = {},
    val setTiviMateTuning: (Boolean) -> Unit = {},
    val setLayout: (String) -> Unit = {},
    val editTeams: () -> Unit = {},
    val testTiviMateTuning: () -> Unit = {},
    val refreshPhone: () -> Unit = {},
    val newPhonePin: () -> Unit = {},
    val togglePhonePage: () -> Unit = {},
    val updateAction: () -> Unit = {},
    /** The picture-in-picture window: back to full screen, or stop it. */
    val pipFullScreen: () -> Unit = {},
    val pipStop: () -> Unit = {},
    val setSaver: (String) -> Unit = {},
    val switchProfile: (com.launcher.tv.data.Profile) -> Unit = {},
    val addProfile: (String, Boolean) -> Unit = { _, _ -> },
    val renameProfile: (com.launcher.tv.data.Profile, String) -> Unit = { _, _ -> },
    val removeProfile: (com.launcher.tv.data.Profile) -> Unit = {},
    val profilesShown: () -> Unit = {},
    /** Trakt client id, Trakt secret, TMDB key, Simkl client id (blank keeps what's saved). */
    val saveKeys: (String, String, String, String) -> Unit = { _, _, _, _ -> },
)

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    val callbacks = remember(vm) {
        HomeCallbacks(
            refresh = vm::manualRefresh,
            connectTrakt = vm::connectTrakt,
            cancelTraktAuth = vm::cancelTraktAuth,
            signOutTrakt = vm::signOutTrakt,
            connectSimkl = vm::connectSimkl,
            cancelSimklAuth = vm::cancelSimklAuth,
            signOutSimkl = vm::signOutSimkl,
            pipFullScreen = { com.launcher.tv.player.PlayerActivity.fullScreen(context) },
            pipStop = { com.launcher.tv.player.PlayerActivity.stopWindow() },
            launchPackage = vm::launchPackage,
            removeFromHistory = vm::removeFromHistory,
            play = { context.startActivity(PlayerActivity.intent(context, it)) },
            watchGame = { e, c, startOver -> vm.watchGame(e, c, startOver) },
            resolveChannels = vm::resolveChannels,
            openPicker = vm::openPicker,
            closePicker = { vm.closePicker() },
            playMedia = vm::playMedia,
            playHere = vm::playHere,
            watchIn = vm::watchIn,
            setWatchApp = vm::setWatchApp,
            addXtream = vm::addXtream,
            addM3u = vm::addM3u,
            removeSource = vm::removeSource,
            loadIptvStatus = vm::loadIptvStatus,
            setAlerts = vm::setAlerts,
            setScoringAlerts = vm::setScoringAlerts,
            openAndroidSettings = { openSystemSettings(context, QuickAction.ALL) },
            openSystemSettings = { openSystemSettings(context, it) },
            cycleSleepTimer = vm::cycleSleepTimer,
            previewScreensaver = { context.startActivity(Intent(context, ScreensaverActivity::class.java)) },
            refreshSystemState = vm::refreshSystemState,
            openGame = { context.startActivity(GameActivity.intent(context, it)) },
            highlights = vm::highlights,
            setSpoilers = vm::setSpoilers,
            revealScore = vm::revealScore,
            compareProviders = vm::compareProviders,
            closeStreamCheck = vm::closeStreamCheck,
            watchFromCheck = vm::watchFromCheck,
            tiviMateFromCheck = vm::tiviMateFromCheck,
            setTiviMateTuning = vm::setTiviMateTuning,
            testTiviMateTuning = vm::testTiviMateTuning,
            setLayout = vm::setLayout,
            editTeams = { guarded(context) { context.startActivity(Intent(context, TeamsActivity::class.java)) } },
            setSaver = vm::setSaver,
            switchProfile = vm::switchProfile,
            addProfile = vm::addProfile,
            renameProfile = vm::renameProfile,
            removeProfile = vm::removeProfile,
            profilesShown = vm::profilesShown,
            refreshPhone = vm::refreshPhone,
            newPhonePin = vm::newPhonePin,
            togglePhonePage = vm::togglePhonePage,
            updateAction = vm::updateAction,
            saveKeys = vm::saveKeys,
        )
    }
    when (state.layout) {
        "tiles" -> HomeLayout(state, callbacks)
        "rows" -> RowsLayout(state, callbacks)
        else -> AuraLayout(state, callbacks)
    }
}

/**
 * PS5-style home: tabs and a row of tiles across the top, the selected tile's artwork filling
 * the screen behind its title and buttons, and a "Coming up" strip along the bottom.
 * Stateless apart from UI state, so it can be rendered with sample data in screenshot tests.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeLayout(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    initialTab: HomeTab = HomeTab.FOR_YOU,
    initialSelectedKey: String? = null,
    initialQuickSettings: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Spoiler mode: everything below sees games without their scores.
    val shown = remember(state) { state.copy(data = Spoilers.apply(state.data, state.spoilers, state.revealed)) }
    val tiles = remember(tab, shown) { buildTiles(tab, shown) }
    val strip = remember(tab, shown) { buildStrip(tab, shown) }

    var selectedKey by remember { mutableStateOf(initialSelectedKey) }
    val selected = tiles.firstOrNull { it.key == selectedKey } ?: tiles.firstOrNull()

    // The hero follows the selected tile, after a short pause so fast scrolling stays smooth.
    var hero by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (hero != null && hero?.key != selected?.key) delay(180)
        hero = selected
    }

    val overlays = remember { OverlayState(initialQuickSettings) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val firstTile = remember { FocusRequester() }

    val actions = remember(callbacks, state.iptv, state.watchApps, state.watchApp) {
        HomeActions(
            openTiviMate = { callbacks.launchPackage(AppsRepository.TIVIMATE) },
            play = callbacks.play,
            removeHistory = callbacks.removeFromHistory,
            launchApp = callbacks.launchPackage,
            showApps = { tab = HomeTab.APPS },
            connectTrakt = { guarded(context) { overlays.account = true } },
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

    // Look up the channel for the selected game so the hero can say where it's on.
    LaunchedEffect(hero?.key, state.iptv) {
        (hero as? Tile.Game)?.let { callbacks.resolveChannels(it.event) }
    }

    fun focusFirstTile() {
        scope.launch {
            listState.scrollToItem(0)
            runCatching { firstTile.requestFocus() }
        }
    }

    // Back returns to the first tile, like a console home screen.
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler { selectedKey = null; focusFirstTile() }
        // Registered after the one above, so it wins while Quick settings is open.
        BackHandler(enabled = overlays.quick) { overlays.quick = false }
    }

    var firstTabEffect by remember { mutableStateOf(true) }
    LaunchedEffect(tab) {
        if (firstTabEffect) { firstTabEffect = false; return@LaunchedEffect }
        selectedKey = null
        listState.scrollToItem(0)
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { firstTile.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF07090D))) {
        Crossfade(targetState = hero, animationSpec = tween(450), label = "hero") { HeroBackground(it) }
        HeroShade()

        Column(Modifier.fillMaxSize()) {
            TopBar(
                tab = tab,
                onTab = { tab = it },
                state = state,
                onRefresh = callbacks.refresh,
                onAccount = { overlays.profiles = true },
                onSettings = { guarded(context) { callbacks.refreshSystemState(); overlays.quick = true } },
            )

            // ---- tile row ----
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(118.dp).focusRestorer(),
                contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                itemsIndexed(tiles, key = { _, t -> t.key }) { index, tile ->
                    val isSelected = tile.key == selected?.key
                    val size by animateDpAsState(if (isSelected) 100.dp else 64.dp, tween(180), label = "tile")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HomeTileCard(
                            tile = tile,
                            size = size,
                            selected = isSelected,
                            onClick = tile.primaryAction(actions),
                            onLongClick = (tile as? Tile.Resume)?.let { r -> { callbacks.removeFromHistory(r.entry) } },
                            modifier = (if (index == 0) Modifier.focusRequester(firstTile) else Modifier)
                                .onFocusChanged { if (it.isFocused || it.hasFocus) selectedKey = tile.key },
                        )
                        AnimatedVisibility(visible = isSelected, enter = fadeIn(tween(200)), exit = fadeOut(tween(100))) {
                            Column(Modifier.padding(start = 12.dp, end = 8.dp).widthIn(max = 150.dp)) {
                                Text(tile.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                                if (tile.subtitle.isNotBlank()) {
                                    Text(tile.subtitle, color = Dim, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            hero?.let { h ->
                HeroContent(h, actions, Modifier.padding(start = 48.dp), channels = (h as? Tile.Game)?.let { state.channels[it.event.id] })
            }

            Spacer(Modifier.height(22.dp))

            if (strip.items.isNotEmpty()) {
                Row(Modifier.padding(start = 44.dp, bottom = 9.dp), verticalAlignment = Alignment.Bottom) {
                    Text(strip.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("  ${strip.hint}", color = Faint, fontSize = 10.sp)
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusRestorer(),
                    contentPadding = PaddingValues(horizontal = 44.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(strip.items, key = { it.key }) { StripCard(it, actions.openTiviMate) }
                }
                Spacer(Modifier.height(22.dp))
            } else {
                Spacer(Modifier.height(60.dp))
            }
        }

        HomeOverlays(state, callbacks, overlays)
    }
}

@Composable
private fun TopBar(
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    state: HomeUiState,
    onRefresh: () -> Unit,
    onAccount: () -> Unit,
    onSettings: () -> Unit,
) {
    var clock by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = clockText()
            delay(10_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(50.dp).padding(start = 36.dp, end = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeTab.entries.forEach { t ->
            val on = t == tab
            Surface(
                onClick = { onTab(t) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = if (on) Color.White else Faint,
                    focusedContainerColor = Color.White.copy(alpha = 0.16f),
                    focusedContentColor = Color.White,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                    Box(Modifier.padding(top = 4.dp).width(if (on) 22.dp else 0.dp).height(2.dp).background(Color.White, RoundedCornerShape(1.dp)))
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        Spacer(Modifier.weight(1f))

        val busy = state.busy
        if (busy != null) {
            Text(
                busy, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 12.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        } else if (state.refreshing) {
            Text("updating…", color = Faint, fontSize = 10.sp, modifier = Modifier.padding(end = 12.dp))
        }
        CircleButton(onClick = onRefresh) { Icon(painterResource(R.drawable.ic_refresh), "Refresh", Modifier.size(15.dp)) }
        Spacer(Modifier.width(10.dp))
        CircleButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), "Settings", Modifier.size(15.dp)) }
        Spacer(Modifier.width(10.dp))
        CircleButton(onClick = onAccount) {
            Box(
                Modifier.size(22.dp).background(Brush.linearGradient(listOf(Accent, Color(0xFFB23A1C))), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val badge = (if (state.trakt == TraktState.SignedIn) "T" else "") + (if (state.simkl == SimklState.SignedIn) "S" else "")
                Text(badge.ifEmpty { "+" }, color = Color.White, fontSize = if (badge.length > 1) 8.sp else 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(clock, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Dim,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B0D12),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
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
            Box(Modifier.padding(top = 20.dp)) { PillButton("Cancel", onCancel) }
        }
    }
}

/** Trakt and Simkl: connect either, or both. */
@Composable
private fun AccountsDialog(state: HomeUiState, callbacks: HomeCallbacks, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(440.dp).background(Color(0xFF1C222B), RoundedCornerShape(16.dp)).padding(28.dp)) {
            Text("Accounts", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Next up, new episodes and your watchlist come from these. Use one or both.", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            AccountRow(
                "Trakt",
                when (state.trakt) {
                    TraktState.SignedIn -> "Connected"
                    TraktState.NotConfigured -> "Needs a Trakt client ID and secret (Settings → API keys or the phone page)"
                    else -> "Not connected"
                },
            ) {
                when (state.trakt) {
                    TraktState.SignedIn -> PillButton("Sign out", { callbacks.signOutTrakt() })
                    TraktState.NotConfigured -> Unit
                    else -> PillButton("Connect", { onDismiss(); callbacks.connectTrakt() }, primary = true)
                }
            }
            AccountRow(
                "Simkl",
                when (state.simkl) {
                    SimklState.SignedIn -> "Connected"
                    SimklState.NotConfigured -> "Needs a Simkl client ID (Settings → API keys or the phone page)"
                    else -> "Not connected"
                },
            ) {
                when (state.simkl) {
                    SimklState.SignedIn -> PillButton("Disconnect", { callbacks.signOutSimkl() })
                    SimklState.NotConfigured -> Unit
                    else -> PillButton("Connect", { onDismiss(); callbacks.connectSimkl() }, primary = state.trakt == TraktState.SignedIn)
                }
            }
            Row(Modifier.padding(top = 20.dp)) { PillButton("Close", onDismiss) }
        }
    }
}

@Composable
private fun AccountRow(name: String, status: String, buttons: @Composable () -> Unit) {
    Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(status, color = Dim, fontSize = 11.sp)
        }
        buttons()
    }
}

/** Simkl's sign-in, as Simkl recommends for TVs: a QR code that opens the approval page with the code filled in. */
@Composable
private fun SimklCodeDialog(s: SimklState.AwaitingCode, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) { SimklCodePanel(s, onCancel) }
}

@Composable
fun SimklCodePanel(s: SimklState.AwaitingCode, onCancel: () -> Unit) {
    val qr = remember(s.qrUrl) { qrBitmap(s.qrUrl, 480).asImageBitmap() }
    Row(
        Modifier.background(Color(0xFF1C222B), RoundedCornerShape(16.dp)).padding(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(200.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp)) {
            androidx.compose.foundation.Image(qr, "QR code for Simkl sign-in", Modifier.fillMaxSize())
        }
        Column(Modifier.padding(start = 28.dp).width(300.dp)) {
            Text("Connect Simkl", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Scan with your phone and choose Allow.", color = Color(0xFFCCCCCC), modifier = Modifier.padding(top = 10.dp))
            Text("Or go to ${s.url.removePrefix("https://")} and enter:", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
            Text(s.userCode, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Box(Modifier.padding(top = 18.dp)) { PillButton("Cancel", onCancel) }
        }
    }
}

/** A link you can't click on a TV: shown as a QR code for your phone. */
@Composable
private fun LinkQrDialog(title: String, note: String, url: String, onDismiss: () -> Unit) {
    val qr = remember(url) { qrBitmap(url, 420).asImageBitmap() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(Color(0xFF1C222B), RoundedCornerShape(16.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Box(Modifier.size(180.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp)) {
                androidx.compose.foundation.Image(qr, "QR code", Modifier.fillMaxSize())
            }
            Text(url.removePrefix("https://"), color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            Box(Modifier.padding(top = 16.dp)) { PillButton("Close", onDismiss, primary = true) }
        }
    }
}

/** Inter (SIL Open Font License), the typeface Aura uses. */
val Inter = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.launcher.tv.R.font.inter_light, FontWeight.Light),
    androidx.compose.ui.text.font.Font(com.launcher.tv.R.font.inter_regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(com.launcher.tv.R.font.inter_medium, FontWeight.Medium),
    androidx.compose.ui.text.font.Font(com.launcher.tv.R.font.inter_semibold, FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(com.launcher.tv.R.font.inter_bold, FontWeight.Bold),
    androidx.compose.ui.text.font.Font(com.launcher.tv.R.font.inter_extrabold, FontWeight.ExtraBold),
)

private val InterTypography = androidx.tv.material3.Typography().let { t ->
    fun androidx.compose.ui.text.TextStyle.inter() = copy(fontFamily = Inter)
    t.copy(
        displayLarge = t.displayLarge.inter(), displayMedium = t.displayMedium.inter(), displaySmall = t.displaySmall.inter(),
        headlineLarge = t.headlineLarge.inter(), headlineMedium = t.headlineMedium.inter(), headlineSmall = t.headlineSmall.inter(),
        titleLarge = t.titleLarge.inter(), titleMedium = t.titleMedium.inter(), titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(), bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter(), labelMedium = t.labelMedium.inter(), labelSmall = t.labelSmall.inter(),
    )
}

@Composable
fun HomeBaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme(primary = Accent), typography = InterTypography, content = content)
}

@Composable
private fun SettingsDialog(state: HomeUiState, callbacks: HomeCallbacks, onAddXtream: () -> Unit, onAddM3u: () -> Unit, onProfiles: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(560.dp).heightIn(max = 500.dp).background(Color(0xFF1C222B), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState()).padding(26.dp),
        ) {
            Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            SettingsSection(
                "IPTV providers",
                if (state.sources.isEmpty()) "None yet: Watch and Play open TiviMate instead" else "Channels from all of them are searched together",
            ) {
                PillButton("Add Xtream", onAddXtream, primary = state.sources.isEmpty())
                PillButton("Add M3U playlist", onAddM3u)
            }
            state.sources.forEach { src ->
                Row(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${src.name}  ·  ${src.kind}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(src.status ?: "Checking…", color = Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PillButton("Remove", { callbacks.removeSource(src.id) })
                }
            }
            SettingsSection(
                "Live alerts",
                "Pop-ups over any app for your teams, fights and F1. Needs the HomeBase Home button service switched on.",
            ) {
                PillButton("Alerts: " + if (state.alerts) "On" else "Off", { callbacks.setAlerts(!state.alerts) })
                PillButton("Scoring plays: " + if (state.scoringAlerts) "On" else "Off", { callbacks.setScoringAlerts(!state.scoringAlerts) })
            }
            SettingsSection("Your teams", "${state.teamsCount} teams, plus fights, F1, golf and tennis") {
                PillButton("Edit teams", { onDismiss(); callbacks.editTeams() }, primary = true)
            }
            SettingsSection(
                "Updates",
                state.update.available?.let { "Update ${it.versionName} is ready (you have ${com.launcher.tv.BuildConfig.VERSION_NAME})" }
                    ?: "You have ${com.launcher.tv.BuildConfig.VERSION_NAME}",
            ) {
                PillButton(if (state.update.available != null) "Install update" else "Check for updates", callbacks.updateAction)
            }
            SettingsSection(
                "Movies and shows",
                if (state.watchApps.isEmpty()) "Play uses your providers in HomeBase's player, or TiviMate. Install Stremio or Nuvio to play them there instead."
                else "Where Play sends next episodes and watchlist movies. Stremio and Nuvio open on the list of streams from your add-ons.",
            ) {
                val chosen = state.watchApps.firstOrNull { it.key == state.watchApp }
                PillButton("HomeBase", { callbacks.setWatchApp(com.launcher.tv.player.WatchApps.HOMEBASE) }, primary = chosen == null)
                state.watchApps.forEach { app -> PillButton(app.label, { callbacks.setWatchApp(app.key) }, primary = chosen == app) }
            }
            SettingsSection(
                "Screensaver",
                "Photos drift slowly with where they were taken; Quotes light up line by line over them; Auto does both in turn. Scores is HomeBase's scoreboard.",
            ) {
                com.launcher.tv.dream.SaverStyle.entries.forEach { s ->
                    PillButton(s.label, { callbacks.setSaver(s.key) }, primary = state.saver == s.key)
                }
                PillButton("Preview", callbacks.previewScreensaver)
            }
            SettingsSection("Home screen layout", "Aura: glass over a daily photograph. Rows: streaming-app style. Tiles: console style.") {
                PillButton("Aura", { callbacks.setLayout("aura") }, primary = state.layout != "tiles" && state.layout != "rows")
                PillButton("Rows", { callbacks.setLayout("rows") }, primary = state.layout == "rows")
                PillButton("Tiles", { callbacks.setLayout("tiles") }, primary = state.layout == "tiles")
            }
            SettingsSection("Spoiler mode", "Hides scores of live and finished games until you choose Show score") {
                PillButton("Spoiler mode: " + if (state.spoilers) "On" else "Off", { callbacks.setSpoilers(!state.spoilers) })
            }
            SettingsSection(
                "One-press tuning in TiviMate (experimental)",
                "Types the channel number into TiviMate for you, using the box's own network debugging. See README.",
            ) {
                PillButton("Tuning: " + if (state.tiviMateTuning) "On" else "Off", { callbacks.setTiviMateTuning(!state.tiviMateTuning) })
                if (state.tiviMateTuning) PillButton("Test connection", callbacks.testTiviMateTuning)
            }
            SettingsSection(
                "API keys",
                "Trakt: " + (if (state.keys.first) "set" else "not set") + "  ·  Simkl: " + (if (state.simklKey) "set" else "not set") +
                    "  ·  TMDB artwork: " + (if (state.keys.second) "set" else "not set") +
                    ". Stored only on this box. Easier to type on the phone page.",
            ) {
                PillButton("Enter keys", { showKeysForm(context) { id, secret, tmdb, simkl -> callbacks.saveKeys(id, secret, tmdb, simkl) } })
            }
            SettingsSection("Trakt", if (state.trakt == TraktState.SignedIn) "Connected" else "Not connected") {
                if (state.trakt == TraktState.SignedIn) PillButton("Sign out", callbacks.signOutTrakt)
                else PillButton("Connect", callbacks.connectTrakt)
            }
            SettingsSection(
                "Simkl",
                when (state.simkl) {
                    SimklState.SignedIn -> "Connected. Checks Simkl for changes when you come back to the home screen."
                    SimklState.NotConfigured -> "Add a Simkl client ID under API keys first"
                    else -> "Not connected. Works alongside Trakt, or on its own."
                },
            ) {
                when (state.simkl) {
                    SimklState.SignedIn -> PillButton("Disconnect", callbacks.signOutSimkl)
                    SimklState.NotConfigured -> Unit
                    else -> PillButton("Connect", callbacks.connectSimkl)
                }
            }
            SettingsSection(
                "Profiles",
                "${state.profile.name} is watching. Everyone keeps their own Trakt and Simkl, teams, settings and Continue Watching." +
                    if (state.profiles.any { it.kids }) " Kids profiles ask for the PIN for grown-up things." else "",
            ) {
                PillButton("Who's watching", onProfiles, primary = true)
            }
            SettingsSection("System", "Wi-Fi, display, inputs, accounts") {
                PillButton("Android settings", callbacks.openAndroidSettings)
                PillButton("Close", onDismiss, primary = true)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, subtitle: String, buttons: @Composable () -> Unit) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) { buttons() }
    }
}

@Composable
internal fun ChannelPicker(event: SportsEvent, lookup: ChannelLookup?, onPick: (ChannelCandidate) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(580.dp).heightIn(max = 470.dp).background(Color(0xFF1C222B), RoundedCornerShape(16.dp)).padding(22.dp),
        ) {
            Text("Pick a channel", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "${event.title} · ${event.broadcast ?: event.league}. HomeBase remembers your pick for this team and network.",
                color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp),
            )
            when {
                lookup == null || lookup.loading -> Text("Searching your channels…", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
                lookup.candidates.isEmpty() -> Text(lookup.error ?: "No matching channels found.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
            }
            LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(lookup?.candidates.orEmpty(), key = { it.channel.key }) { c ->
                    Surface(
                        onClick = { onPick(c) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.06f),
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color(0xFF0B0D12),
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                            Text(c.channel.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(c.epgTitle?.let { "Guide: $it" }, c.reason.ifBlank { null }).joinToString("  ·  "),
                                fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Trakt client id/secret, TMDB key and Simkl client id; blank fields keep what's saved. */
private fun showKeysForm(context: android.content.Context, onSave: (String, String, String, String) -> Unit) {
    val pad = (20 * context.resources.displayMetrics.density).toInt()
    fun field(hint: String) = EditText(context).apply {
        this.hint = hint
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }
    val id = field("Trakt Client ID")
    val secret = field("Trakt Client Secret")
    val tmdb = field("TMDB API key (optional, for artwork)")
    val simkl = field("Simkl Client ID (optional)")
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
        addView(id); addView(secret); addView(tmdb); addView(simkl)
    }
    AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("API keys")
        .setMessage("From trakt.tv/oauth/applications, themoviedb.org/settings/api and simkl.com/settings/developer. Leave a field empty to keep what's saved.")
        .setView(layout)
        .setPositiveButton("Save") { _, _ -> onSave(id.text.toString(), secret.text.toString(), tmdb.text.toString(), simkl.text.toString()) }
        .setNegativeButton("Cancel", null)
        .show()
}

/** M3U playlist URL plus an optional XMLTV guide URL. */
private fun showM3uForm(context: android.content.Context, onSave: (String, String) -> Unit) {
    val pad = (20 * context.resources.displayMetrics.density).toInt()
    fun field(hint: String) = EditText(context).apply {
        this.hint = hint
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    val url = field("Playlist URL (http://…/playlist.m3u)")
    val epg = field("TV guide URL, optional (XMLTV, .xml or .xml.gz)")
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
        addView(url); addView(epg)
    }
    AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("Add M3U playlist")
        .setMessage("If the playlist names its own TV guide (url-tvg), leave the guide empty.")
        .setView(layout)
        .setPositiveButton("Add") { _, _ -> onSave(url.text.toString(), epg.text.toString()) }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Native form: the TV keyboard works far better with real EditTexts than with Compose text fields. */
private fun showXtreamForm(context: android.content.Context, onSave: (String, String, String) -> Unit) {
    val pad = (20 * context.resources.displayMetrics.density).toInt()
    fun field(hint: String, password: Boolean = false) = EditText(context).apply {
        this.hint = hint
        isSingleLine = true
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    val server = field("Server, e.g. http://line.example.com:8080 (or paste your M3U link)")
    val user = field("Username")
    val pass = field("Password", password = true)
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
        addView(server); addView(user); addView(pass)
    }
    AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("IPTV provider login")
        .setMessage("Use the same Xtream Codes details as in TiviMate. They're stored only on this box.")
        .setView(layout)
        .setPositiveButton("Connect") { _, _ -> onSave(server.text.toString(), user.text.toString(), pass.text.toString()) }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Which home-screen overlays are open; shared by both layouts. */
class OverlayState(quick: Boolean = false) {
    var quick by mutableStateOf(quick)
    var phone by mutableStateOf(false)
    var settings by mutableStateOf(false)
    var account by mutableStateOf(false)
    /** Who's watching. */
    var profiles by mutableStateOf(false)
    /** A title whose Simkl page is shown as a QR code. */
    var simklLink by mutableStateOf<MediaItem?>(null)
}

/** Dialogs and panels on top of the home screen (both layouts). */
@Composable
fun BoxScope.HomeOverlays(state: HomeUiState, callbacks: HomeCallbacks, o: OverlayState, aura: Boolean = false) {
    val context = LocalContext.current
    (state.trakt as? TraktState.AwaitingCode)?.let { TraktCodeDialog(it, callbacks.cancelTraktAuth) }
    (state.simkl as? SimklState.AwaitingCode)?.let { SimklCodeDialog(it, callbacks.cancelSimklAuth) }
    if (o.account) AccountsDialog(state, callbacks, onDismiss = { o.account = false })
    o.simklLink?.let { item ->
        LinkQrDialog(
            title = item.title,
            note = "Scan to open it on Simkl",
            url = item.simklUrl ?: "https://simkl.com",
            onDismiss = { o.simklLink = null },
        )
    }
    val onQuick: (QuickAction) -> Unit = { a ->
        when (a) {
            QuickAction.SLEEP -> callbacks.cycleSleepTimer()
            QuickAction.SPOILERS -> callbacks.setSpoilers(!state.spoilers)
            QuickAction.TEAMS -> { o.quick = false; callbacks.editTeams() }
            QuickAction.PHONE -> { o.quick = false; callbacks.refreshPhone(); o.phone = true }
            QuickAction.UPDATE -> callbacks.updateAction()
            QuickAction.SCREENSAVER -> { o.quick = false; callbacks.previewScreensaver() }
            QuickAction.HOMEBASE -> { o.quick = false; callbacks.loadIptvStatus(); o.settings = true }
            else -> { o.quick = false; callbacks.openSystemSettings(a) }
        }
    }
    if (o.profiles || state.askProfiles) {
        val close = { o.profiles = false; callbacks.profilesShown() }
        if (androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
            androidx.activity.compose.BackHandler { close() }
        }
        ProfilePicker(
            profiles = state.profiles,
            activeId = state.profile.id,
            onPick = { p ->
                when {
                    p.id == state.profile.id -> close()
                    // Leaving a kids profile needs the PIN.
                    state.profile.kids -> guarded(context) { callbacks.switchProfile(p) }
                    else -> callbacks.switchProfile(p)
                }
            },
            onEdit = { p -> guarded(context) { showEditProfile(context, p, onRename = { callbacks.renameProfile(p, it) }, onRemove = { callbacks.removeProfile(p) }) } },
            onAdd = { guarded(context) { showAddProfile(context) { name, kids -> callbacks.addProfile(name, kids) } } },
            onAccounts = { guarded(context) { close(); o.account = true } },
            onDismiss = close,
        )
    }
    if (o.quick && aura) {
        AuraControlCenter(state, onAction = onQuick, onAccounts = { o.quick = false; o.account = true })
    } else if (o.quick) {
        ControlCenter(
            sleepMinutesLeft = state.sleepMinutes,
            status = mapOf(
                QuickAction.SCREENSAVER to "Preview",
                QuickAction.HOMEBASE to if (state.iptv) "Provider connected" else "Provider, alerts",
                QuickAction.WIFI to "Wi-Fi & Ethernet",
                QuickAction.DISPLAY to "Picture & HDR",
                QuickAction.SPOILERS to if (state.spoilers) "On" else "Off",
                QuickAction.TEAMS to "${state.teamsCount} teams",
                QuickAction.PHONE to if (state.phone.enabled) "Scan to open" else "Off",
                QuickAction.UPDATE to (state.update.available?.let { "Update to ${it.versionName}" } ?: "Version ${com.launcher.tv.BuildConfig.VERSION_NAME}"),
            ),
            active = setOfNotNull(
                QuickAction.SPOILERS.takeIf { state.spoilers },
                QuickAction.UPDATE.takeIf { state.update.available != null },
            ),
            onAction = onQuick,
        )
    }
    if (o.settings) {
        SettingsDialog(
            state = state,
            callbacks = callbacks,
            onAddXtream = { showXtreamForm(context) { s, u, p -> callbacks.addXtream(s, u, p) } },
            onAddM3u = { showM3uForm(context) { url, epg -> callbacks.addM3u(url, epg) } },
            onProfiles = { o.settings = false; o.profiles = true },
            onDismiss = { o.settings = false },
        )
    }
    if (o.phone) {
        PhonePanel(state.phone, onNewPin = callbacks.newPhonePin, onToggle = callbacks.togglePhonePage, onClose = { o.phone = false })
    }
    state.streamCheck?.let { check ->
        StreamCheckDialog(check, onWatch = callbacks.watchFromCheck, onTiviMate = callbacks.tiviMateFromCheck, onDismiss = callbacks.closeStreamCheck)
    }
    state.picker?.let { event ->
        ChannelPicker(
            event = event,
            lookup = state.channels[event.id],
            onPick = { callbacks.watchGame(event, it, false) },
            onDismiss = callbacks.closePicker,
        )
    }
}
