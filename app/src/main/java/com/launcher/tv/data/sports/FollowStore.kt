package com.launcher.tv.data.sports

import android.content.Context
import com.launcher.tv.data.ChangeBus
import com.launcher.tv.data.Http
import com.launcher.tv.data.TeamInfo
import kotlinx.serialization.Serializable

@Serializable
data class FollowedTeam(
    /** [TeamLeague.key] */
    val league: String,
    /** ESPN team id */
    val id: String,
    val name: String,
    val abbr: String,
    val color: String? = null,
    val altColor: String? = null,
    val logo: String? = null,
) {
    val key: String get() = "$league:$id"
    fun info() = TeamInfo(name, abbr, fullName = name, color = color, altColor = altColor, logo = logo)
}

@Serializable
data class FollowConfig(
    val teams: List<FollowedTeam> = FollowStore.DEFAULT_TEAMS,
    /** [EventFollow.key]s */
    val events: Set<String> = FollowStore.DEFAULT_EVENTS,
    val gamesPerTeam: Int = 2,
) {
    fun follows(e: EventFollow) = e.key in events
    fun isFollowed(league: String, id: String) = teams.any { it.league == league && it.id == id }
}

/** Your teams and followed events, editable on the TV (Teams screen) and from the phone page. */
object FollowStore {
    val DEFAULT_TEAMS = listOf(
        FollowedTeam("college-football", "333", "Alabama Crimson Tide", "ALA", "9E1B32", "FFFFFF"),
        FollowedTeam("nfl", "17", "New England Patriots", "NE", "002244", "C60C30"),
        FollowedTeam("nba", "13", "Los Angeles Lakers", "LAL", "552583", "FDB927"),
        FollowedTeam("mlb", "19", "Los Angeles Dodgers", "LAD", "005A9C", "EF3E42"),
    )
    val DEFAULT_EVENTS = setOf(EventFollow.NFL_WEEK.key, EventFollow.UFC.key, EventFollow.BOXING.key, EventFollow.F1.key)

    private fun prefs(c: Context) = com.launcher.tv.data.Profiles.prefs(c, "follows")

    fun load(c: Context): FollowConfig =
        prefs(c).getString("config", null)?.let { decode(it) } ?: FollowConfig()

    fun save(c: Context, cfg: FollowConfig) {
        prefs(c).edit().putString("config", encode(cfg)).apply()
        ChangeBus.emit(ChangeBus.FOLLOWS)
    }

    fun setTeam(c: Context, team: FollowedTeam, follow: Boolean) {
        val cfg = load(c)
        val others = cfg.teams.filter { it.key != team.key }
        save(c, cfg.copy(teams = if (follow) others + team else others))
    }

    fun setEvent(c: Context, key: String, on: Boolean) {
        val cfg = load(c)
        save(c, cfg.copy(events = if (on) cfg.events + key else cfg.events - key))
    }

    fun setGamesPerTeam(c: Context, n: Int) = save(c, load(c).copy(gamesPerTeam = n.coerceIn(1, 5)))

    fun encode(cfg: FollowConfig): String = Http.json.encodeToString(FollowConfig.serializer(), cfg)
    fun decode(s: String): FollowConfig? = runCatching { Http.json.decodeFromString(FollowConfig.serializer(), s) }.getOrNull()
}
