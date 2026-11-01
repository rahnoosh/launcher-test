package com.launcher.tv.ui

import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.Spoilers

/** What the large preview says about the selected card, shared by the Rows and Aura layouts. */
data class BillboardInfo(
    val eyebrow: String?,
    val live: Boolean = false,
    val title: String,
    /** Team games show a scoreboard instead of the title. */
    val game: SportsEvent? = null,
    val meta: List<String> = emptyList(),
    /** Resume progress (0..1), when there is one. */
    val progress: Float? = null,
    val overview: String? = null,
    val hint: String? = null,
)

fun billboardInfo(tile: Tile, state: HomeUiState): BillboardInfo = when (tile) {
    is Tile.Resume -> {
        val e = tile.entry
        BillboardInfo(
            eyebrow = "Continue watching",
            title = tile.title,
            meta = listOfNotNull(e.subtitle.ifBlank { null }, if (e.progress > 0.01f) "${(e.progress * 100).toInt()}% watched" else null),
            progress = if (e.durationMs > 0) e.progress else null,
            hint = "OK  Resume   ·   Hold OK  More",
        )
    }
    is Tile.Media -> {
        val i = tile.item
        val chosen = state.watchApps.firstOrNull { it.key == state.watchApp }
        BillboardInfo(
            eyebrow = when (tile.role) {
                Tile.Role.UP_NEXT -> if (tile.isNew) "New episode · next up" else "Next up · ${i.trackerLabel()}"
                Tile.Role.UPCOMING -> "New episode · ${i.timeMillis?.let(::friendlyTime) ?: "soon"}"
                Tile.Role.WATCHLIST -> "My watchlist"
            },
            title = tile.title,
            meta = listOfNotNull(i.subtitle.ifBlank { null }, i.year?.toString()),
            overview = i.overview,
            hint = when {
                tile.role == Tile.Role.UPCOMING -> "OK  Open TiviMate"
                chosen != null -> "OK  Play in ${chosen.label}   ·   Hold OK  More"
                else -> "OK  Play   ·   Hold OK  More"
            },
        )
    }
    is Tile.Game -> {
        val e = tile.event
        val channel = state.channels[e.id]?.candidates?.firstOrNull()?.channel?.name
        BillboardInfo(
            eyebrow = gameEyebrow(e),
            live = e.live,
            title = e.title,
            game = e.takeIf { it.home != null && it.away != null },
            meta = listOfNotNull(
                when {
                    e.finished -> "Final"
                    e.live -> (if (e.home != null) Spoilers.stripScore(e.status) else e.status) ?: "Live"
                    else -> friendlyTime(e.startMillis)
                },
                if (state.iptv && channel != null && !e.finished) "on $channel" else e.venue,
            ),
            hint = when {
                e.finished && state.iptv && com.launcher.tv.player.Replay.offered(e, System.currentTimeMillis()) ->
                    "OK  Highlights   ·   Hold OK  Game page, full game"
                e.finished -> "OK  Highlights   ·   Hold OK  Game page"
                state.iptv -> "OK  Watch   ·   Hold OK  Game page, best stream, start over"
                else -> "OK  Open TiviMate   ·   Hold OK  Game page"
            },
        )
    }
    is Tile.App -> BillboardInfo(eyebrow = "App", title = tile.title, hint = "OK  Open")
    Tile.AllApps -> BillboardInfo(eyebrow = "Apps", title = "All apps")
    Tile.EditTeams -> BillboardInfo(
        eyebrow = "Sports",
        title = "Your teams",
        overview = "Add or remove teams from the NFL, college football, NBA, WNBA, college basketball, MLB, NHL, MLS, Premier League, Champions League and LaLiga, plus fights, F1, golf majors and tennis Grand Slams.",
        hint = "OK  Edit teams",
    )
    Tile.ConnectTrakt -> BillboardInfo(
        eyebrow = "Trakt · Simkl",
        title = "Connect Trakt or Simkl",
        overview = "See your next episodes, new episodes and movie watchlist. Use either service, or both.",
        hint = "OK  Connect",
    )
    is Tile.NowPlaying -> BillboardInfo(
        eyebrow = "Playing in a window",
        title = tile.title,
        overview = "It keeps playing in the corner while you browse.",
        hint = "OK  Full screen   ·   Hold OK  Stop",
    )
    is Tile.Message -> BillboardInfo(eyebrow = null, title = tile.title, meta = listOfNotNull(tile.subtitle.ifBlank { null }))
}
