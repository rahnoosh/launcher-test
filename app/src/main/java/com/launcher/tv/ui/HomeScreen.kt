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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.launcher.tv.R
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.history.HistoryEntry
import com.launcher.tv.player.PlayerActivity
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
) {
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val tiles = remember(tab, state) { buildTiles(tab, state) }
    val strip = remember(tab, state) { buildStrip(tab, state) }

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

    val actions = remember(callbacks) {
        HomeActions(
            openTiviMate = { callbacks.launchPackage(AppsRepository.TIVIMATE) },
            play = callbacks.play,
            removeHistory = callbacks.removeFromHistory,
            launchApp = callbacks.launchPackage,
            showApps = { tab = HomeTab.APPS },
            connectTrakt = callbacks.connectTrakt,
        )
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
                            Column(Modifier.padding(start = 12.dp, end = 6.dp).width(140.dp)) {
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

            hero?.let { HeroContent(it, actions, Modifier.padding(start = 48.dp)) }

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
    }
}

@Composable
private fun TopBar(
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    state: HomeUiState,
    onRefresh: () -> Unit,
    onAccount: () -> Unit,
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

        if (state.refreshing) Text("updating…", color = Faint, fontSize = 10.sp, modifier = Modifier.padding(end = 12.dp))
        CircleButton(onClick = onRefresh) { Icon(painterResource(R.drawable.ic_refresh), "Refresh", Modifier.size(15.dp)) }
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
