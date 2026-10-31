package com.launcher.tv.ui

import com.launcher.tv.data.AppEntry
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.TeamInfo
import com.launcher.tv.data.history.HistoryEntry

enum class HomeTab(val label: String) { FOR_YOU("For You"), TV("TV & Movies"), SPORTS("Sports"), APPS("Apps") }

/** One square in the tile row at the top of the home screen. */
sealed interface Tile {
    val key: String
    val title: String
    val subtitle: String

    data class Resume(val entry: HistoryEntry) : Tile {
        override val key get() = "h-${entry.url}"
        override val title get() = entry.title
        override val subtitle get() = when {
            entry.finished -> "Watched"
            entry.progress > 0.01f -> "${(entry.progress * 100).toInt()}% watched"
            else -> entry.subtitle.ifBlank { "Recently played" }
        }
    }

    enum class Role { UP_NEXT, UPCOMING, WATCHLIST }

    data class Media(val item: MediaItem, val role: Role) : Tile {
        override val key get() = "m-${role.name}-${item.id}"
        override val title get() = item.title
        override val subtitle get() = when (role) {
            Role.UP_NEXT -> item.subtitle.substringBefore(" · ") + " · next up"
            Role.UPCOMING -> item.timeMillis?.let(::friendlyTime) ?: item.subtitle
            Role.WATCHLIST -> "On your watchlist"
        }
        /** "NEW" badge: next-up episode that aired in the last week. */
        val isNew get() = role == Role.UP_NEXT && item.timeMillis?.let { System.currentTimeMillis() - it < 7 * 86_400_000L } == true
    }

    data class Game(val event: SportsEvent) : Tile {
        override val key get() = "g-${event.id}"
        override val title get() = event.title
        override val subtitle get() = when {
            event.live -> "Live · ${event.status ?: ""}".trimEnd(' ', '·')
            event.finished && event.home != null && event.home.score == null -> "Final · score hidden"
            event.finished && event.home != null -> "Final · ${event.away?.score ?: 0}–${event.home.score ?: 0}"
            event.finished -> "Finished · highlights"
            else -> friendlyTime(event.startMillis)
        }
    }

    data class App(val app: AppEntry) : Tile {
        override val key get() = "a-${app.packageName}"
        override val title get() = app.label
        override val subtitle get() = "App"
    }

    data object AllApps : Tile {
        override val key = "all-apps"
        override val title = "All apps"
        override val subtitle = ""
    }

    data object ConnectTrakt : Tile {
        override val key = "connect-trakt"
        override val title = "Connect Trakt"
        override val subtitle = "Shows & watchlist"
    }

    data class Message(override val title: String, override val subtitle: String) : Tile {
        override val key get() = "msg-$title"
    }
}

/** A card in the "Coming up" strip under the hero. */
data class StripItem(
    val key: String,
    val kicker: String,
    val title: String,
    val time: String,
    val live: Boolean,
    val teams: List<TeamInfo>,
    val imageUrl: String?,
)

data class Strip(val title: String, val hint: String, val items: List<StripItem>)

fun buildTiles(tab: HomeTab, s: HomeUiState): List<Tile> {
    val d = s.data
    val signedIn = s.trakt == TraktState.SignedIn
    val traktPrompt: List<Tile> = when (s.trakt) {
        TraktState.SignedOut, is TraktState.AwaitingCode -> listOf(Tile.ConnectTrakt)
        TraktState.NotConfigured -> if (tab == HomeTab.TV) listOf(Tile.Message("Trakt not set up", "Add API keys (see README)")) else emptyList()
        TraktState.SignedIn -> emptyList()
    }
    val allSports = (d.myTeams + d.nfl + d.fights + d.f1).distinctBy { it.id }
    val live = allSports.filter { it.live }
    val history = s.history.filter { !it.finished }

    val tiles: List<Tile> = when (tab) {
        HomeTab.FOR_YOU -> buildList<Tile> {
            history.take(3).forEach { add(Tile.Resume(it)) }
            live.forEach { add(Tile.Game(it)) }
            d.results.take(3).forEach { add(Tile.Game(it)) }
            if (signedIn) d.upNext.take(6).forEach { add(Tile.Media(it, Tile.Role.UP_NEXT)) }
            addAll(traktPrompt)
            d.myTeams.filter { !it.live }.take(3).forEach { add(Tile.Game(it)) }
            d.fights.filter { !it.live }.take(1).forEach { add(Tile.Game(it)) }
            d.f1.take(1).forEach { add(Tile.Game(it)) }
            if (signedIn) d.watchlist.take(2).forEach { add(Tile.Media(it, Tile.Role.WATCHLIST)) }
            s.apps.firstOrNull { it.packageName == AppsRepository.TIVIMATE }?.let { add(Tile.App(it)) }
            add(Tile.AllApps)
        }
        HomeTab.TV -> buildList<Tile> {
            s.history.take(10).forEach { add(Tile.Resume(it)) }
            if (signedIn) {
                d.upNext.forEach { add(Tile.Media(it, Tile.Role.UP_NEXT)) }
                d.upcomingEpisodes.forEach { add(Tile.Media(it, Tile.Role.UPCOMING)) }
                d.watchlist.forEach { add(Tile.Media(it, Tile.Role.WATCHLIST)) }
            }
            addAll(traktPrompt)
        }
        HomeTab.SPORTS -> (live + d.results + allSports.filter { !it.live }.sortedBy { it.startMillis }).map<SportsEvent, Tile> { Tile.Game(it) }
        HomeTab.APPS -> s.apps.map<AppEntry, Tile> { Tile.App(it) }
    }.distinctBy { it.key }

    if (tiles.isNotEmpty()) return tiles
    return listOf(
        if (s.refreshing) Tile.Message("Loading…", "Fetching your shows and games")
        else Tile.Message("Nothing here yet", if (tab == HomeTab.SPORTS) "No upcoming games found" else "")
    )
}

fun buildStrip(tab: HomeTab, s: HomeUiState): Strip {
    val d = s.data
    val upcomingEps = if (s.trakt == TraktState.SignedIn) d.upcomingEpisodes else emptyList()
    return when (tab) {
        HomeTab.FOR_YOU -> {
            val events = (d.myTeams + d.fights + d.f1 + d.nfl.filter { it.live }).distinctBy { it.id }
            val dated = events.map { (if (it.live) 0L else it.startMillis) to it.toStrip() } +
                upcomingEps.take(3).map { (it.timeMillis ?: Long.MAX_VALUE) to it.toStrip() }
            Strip("Coming up", "from Trakt and your teams", dated.sortedBy { it.first }.map { it.second }.distinctBy { it.key }.take(8))
        }
        HomeTab.TV -> Strip("New episodes", "next 14 days", upcomingEps.map { it.toStrip() })
        HomeTab.SPORTS -> Strip(
            "NFL this week", "every game, with channels",
            d.nfl.sortedWith(compareBy({ !it.live }, { it.startMillis })).map { it.toStrip() },
        )
        HomeTab.APPS -> Strip("", "", emptyList())
    }
}

private fun SportsEvent.toStrip() = StripItem(
    key = "s-$id",
    kicker = listOfNotNull(if (live) "Live now" else league, broadcast).joinToString(" · "),
    title = title,
    time = if (live) status ?: "Live" else friendlyTime(startMillis),
    live = live,
    teams = listOfNotNull(away, home),
    imageUrl = imageUrl.takeIf { home == null },
)

private fun MediaItem.toStrip() = StripItem(
    key = "e-$id",
    kicker = "New episode",
    title = "$title · ${subtitle.substringBefore(" · ")}",
    time = timeMillis?.let(::friendlyTime).orEmpty(),
    live = false,
    teams = emptyList(),
    imageUrl = imageUrl,
)

/** One row of cards in the Rows layout. */
data class HomeRow(val title: String, val tiles: List<Tile>)

fun buildRows(tab: HomeTab, s: HomeUiState): List<HomeRow> {
    val d = s.data
    val signedIn = s.trakt == TraktState.SignedIn
    val traktPrompt: List<Tile> = when (s.trakt) {
        TraktState.SignedOut, is TraktState.AwaitingCode -> listOf(Tile.ConnectTrakt)
        else -> emptyList()
    }
    val sports = (d.myTeams + d.nfl + d.fights + d.f1).distinctBy { it.id }
    val live = sports.filter { it.live }
    val liveIds = live.map { it.id }.toSet()
    val teamIds = d.myTeams.map { it.id }.toSet()
    fun games(list: List<com.launcher.tv.data.SportsEvent>) = list.filter { it.id !in liveIds }.map<com.launcher.tv.data.SportsEvent, Tile> { Tile.Game(it) }
    fun media(list: List<com.launcher.tv.data.MediaItem>, role: Tile.Role) = list.map<com.launcher.tv.data.MediaItem, Tile> { Tile.Media(it, role) }

    val continueWatching = HomeRow("Continue Watching", s.history.filter { !it.finished }.take(15).map { Tile.Resume(it) })
    val liveNow = HomeRow("Live Now", live.map { Tile.Game(it) })
    val nextUp = HomeRow("Next Up", (if (signedIn) media(d.upNext, Tile.Role.UP_NEXT) else emptyList()) + traktPrompt)
    val teams = HomeRow("Your Teams", games(d.myTeams))
    val results = HomeRow("Recent Results", d.results.map { Tile.Game(it) })
    val newEpisodes = HomeRow("New Episodes", if (signedIn) media(d.upcomingEpisodes, Tile.Role.UPCOMING) else emptyList())
    val fights = HomeRow("Fight Night", games(d.fights))
    val nfl = HomeRow("NFL This Week", games(d.nfl.filter { it.id !in teamIds }))
    val f1 = HomeRow("Formula 1", games(d.f1))
    val watchlist = HomeRow("My Watchlist", if (signedIn) media(d.watchlist, Tile.Role.WATCHLIST) else emptyList())
    val apps = s.apps.map { Tile.App(it) }

    val rows = when (tab) {
        HomeTab.FOR_YOU -> listOf(continueWatching, liveNow, nextUp, teams, results, newEpisodes, fights, nfl, f1, watchlist, HomeRow("Apps", apps.take(12)))
        HomeTab.TV -> listOf(continueWatching, nextUp, newEpisodes, watchlist)
        HomeTab.SPORTS -> listOf(liveNow, teams, results, fights, nfl, f1)
        HomeTab.APPS -> apps.chunked(6).mapIndexed { i, chunk -> HomeRow(if (i == 0) "Apps" else "", chunk) }
    }.filter { it.tiles.isNotEmpty() }

    if (rows.isNotEmpty()) return rows
    return listOf(
        HomeRow(
            "",
            listOf(
                if (s.refreshing) Tile.Message("Loading…", "Fetching your shows and games")
                else Tile.Message("Nothing here yet", if (tab == HomeTab.SPORTS) "No upcoming games found" else ""),
            ),
        )
    )
}
