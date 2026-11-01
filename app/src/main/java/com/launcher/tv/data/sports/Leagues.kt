package com.launcher.tv.data.sports

enum class SportKind { FOOTBALL, BASKETBALL, BASEBALL, HOCKEY, SOCCER }

/**
 * A league whose teams you can follow. [label] is what appears in [com.launcher.tv.data.SportsEvent.league];
 * [periods] is regulation length (quarters, halves, innings, periods), used for "OT" labels and close-game alerts.
 */
data class TeamLeague(
    val key: String,
    val label: String,
    val espnPath: String,
    val kind: SportKind,
    val periods: Int,
    /** ESPN's team list is paginated for big college leagues. */
    val teamsQuery: String = "",
    /** College scoreboards default to ranked games only. */
    val scoreboardQuery: String = "",
)

object Leagues {
    val all: List<TeamLeague> = listOf(
        TeamLeague("nfl", "NFL", "football/nfl", SportKind.FOOTBALL, 4),
        TeamLeague("college-football", "College Football", "football/college-football", SportKind.FOOTBALL, 4, "?limit=1000", "?groups=80&limit=300"),
        TeamLeague("nba", "NBA", "basketball/nba", SportKind.BASKETBALL, 4),
        TeamLeague("wnba", "WNBA", "basketball/wnba", SportKind.BASKETBALL, 4),
        TeamLeague("mens-college-basketball", "College Basketball", "basketball/mens-college-basketball", SportKind.BASKETBALL, 2, "?limit=1000", "?groups=50&limit=300"),
        TeamLeague("mlb", "MLB", "baseball/mlb", SportKind.BASEBALL, 9),
        TeamLeague("nhl", "NHL", "hockey/nhl", SportKind.HOCKEY, 3),
        TeamLeague("mls", "MLS", "soccer/usa.1", SportKind.SOCCER, 2),
        TeamLeague("epl", "Premier League", "soccer/eng.1", SportKind.SOCCER, 2),
        TeamLeague("ucl", "Champions League", "soccer/uefa.champions", SportKind.SOCCER, 2),
        TeamLeague("laliga", "LaLiga", "soccer/esp.1", SportKind.SOCCER, 2),
    )

    fun byKey(key: String): TeamLeague? = all.firstOrNull { it.key == key }
    fun byLabel(label: String): TeamLeague? = all.firstOrNull { it.label == label }
}

/** Things to follow that aren't a single team. */
enum class EventFollow(val key: String, val label: String) {
    NFL_WEEK("nfl-week", "Every NFL game this week"),
    UFC("ufc", "UFC numbered events"),
    BOXING("boxing", "Big boxing fights"),
    F1("f1", "Formula 1"),
    GOLF("golf", "Golf majors"),
    TENNIS("tennis", "Tennis Grand Slams"),
}
