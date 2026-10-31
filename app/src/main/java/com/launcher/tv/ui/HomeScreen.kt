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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    val launchPackage: (String) -> Unit = {},
    val removeFromHistory: (HistoryEntry) -> Unit = {},
    val play: (HistoryEntry) -> Unit = {},
    val watchGame: (SportsEvent, ChannelCandidate?, Boolean) -> Unit = { _, _, _ -> },
    val resolveChannels: (SportsEvent) -> Unit = {},
    val openPicker: (SportsEvent) -> Unit = {},
    val closePicker: () -> Unit = {},
    val playMedia: (MediaItem) -> Unit = {},
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
    val testTiviMateTuning: () -> Unit = {},
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
            refresh = { vm.refresh(force = true) },
            connectTrakt = vm::connectTrakt,
            cancelTraktAuth = vm::cancelTraktAuth,
            signOutTrakt = vm::signOutTrakt,
            launchPackage = vm::launchPackage,
            removeFromHistory = vm::removeFromHistory,
            play = { context.startActivity(PlayerActivity.intent(context, it)) },
            watchGame = { e, c, startOver -> vm.watchGame(e, c, startOver) },
            resolveChannels = vm::resolveChannels,
            openPicker = vm::openPicker,
            closePicker = { vm.closePicker() },
            playMedia = vm::playMedia,
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
        )
    }
    HomeLayout(state, callbacks)
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

    var showAccount by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val firstTile = remember { FocusRequester() }

    val actions = remember(callbacks, state.iptv) {
        HomeActions(
            openTiviMate = { callbacks.launchPackage(AppsRepository.TIVIMATE) },
            play = callbacks.play,
            removeHistory = callbacks.removeFromHistory,
            launchApp = callbacks.launchPackage,
            showApps = { tab = HomeTab.APPS },
            connectTrakt = callbacks.connectTrakt,
            watchGame = { e, startOver -> callbacks.watchGame(e, null, startOver) },
            pickChannel = callbacks.openPicker,
            playMedia = callbacks.playMedia,
            openGame = callbacks.openGame,
            highlights = callbacks.highlights,
            revealScore = callbacks.revealScore,
            compareProviders = callbacks.compareProviders,
            iptv = state.iptv,
        )
    }
    var showSettings by remember { mutableStateOf(false) }
    var showQuick by remember { mutableStateOf(initialQuickSettings) }
    val context = LocalContext.current

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
        BackHandler(enabled = showQuick) { showQuick = false }
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
                onAccount = { if (state.trakt == TraktState.SignedIn) showAccount = true else callbacks.connectTrakt() },
                onSettings = { callbacks.refreshSystemState(); showQuick = true },
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

        (state.trakt as? TraktState.AwaitingCode)?.let { TraktCodeDialog(it, callbacks.cancelTraktAuth) }
        if (showAccount) AccountDialog(onSignOut = { callbacks.signOutTrakt(); showAccount = false }, onDismiss = { showAccount = false })
        if (showQuick) {
            ControlCenter(
                sleepMinutesLeft = state.sleepMinutes,
                status = mapOf(
                    QuickAction.SCREENSAVER to "Preview",
                    QuickAction.HOMEBASE to if (state.iptv) "Provider connected" else "Provider, alerts",
                    QuickAction.WIFI to "Wi-Fi & Ethernet",
                    QuickAction.DISPLAY to "Picture & HDR",
                    QuickAction.SPOILERS to if (state.spoilers) "On" else "Off",
                ),
                active = if (state.spoilers) setOf(QuickAction.SPOILERS) else emptySet(),
                onAction = { a ->
                    when (a) {
                        QuickAction.SLEEP -> callbacks.cycleSleepTimer()
                        QuickAction.SPOILERS -> callbacks.setSpoilers(!state.spoilers)
                        QuickAction.SCREENSAVER -> { showQuick = false; callbacks.previewScreensaver() }
                        QuickAction.HOMEBASE -> { showQuick = false; callbacks.loadIptvStatus(); showSettings = true }
                        else -> { showQuick = false; callbacks.openSystemSettings(a) }
                    }
                },
            )
        }
        if (showSettings) {
            SettingsDialog(
                state = state,
                callbacks = callbacks,
                onAddXtream = { showXtreamForm(context) { s, u, p -> callbacks.addXtream(s, u, p) } },
                onAddM3u = { showM3uForm(context) { url, epg -> callbacks.addM3u(url, epg) } },
                onDismiss = { showSettings = false },
            )
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
                Text(if (state.trakt == TraktState.SignedIn) "T" else "+", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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

@Composable
private fun AccountDialog(onSignOut: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(Color(0xFF1C222B), RoundedCornerShape(16.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Trakt", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("You're connected.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Close", onDismiss, primary = true)
                PillButton("Sign out", onSignOut)
            }
        }
    }
}

@Composable
fun HomeBaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme(primary = Accent), content = content)
}

@Composable
private fun SettingsDialog(state: HomeUiState, callbacks: HomeCallbacks, onAddXtream: () -> Unit, onAddM3u: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(540.dp).background(Color(0xFF1C222B), RoundedCornerShape(16.dp)).padding(26.dp),
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
            SettingsSection("Trakt", if (state.trakt == TraktState.SignedIn) "Connected" else "Not connected") {
                if (state.trakt == TraktState.SignedIn) PillButton("Sign out", callbacks.signOutTrakt)
                else PillButton("Connect", callbacks.connectTrakt)
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
