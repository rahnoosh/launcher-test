package com.launcher.tv.data.sports

/**
 * What the sports rows follow. Edit this file to change teams/leagues.
 *
 * ESPN team ids: open the team on espn.com, the number in the URL is the id
 * (e.g. espn.com/nfl/team/_/name/ne/new-england-patriots -> abbreviation "ne", id 17).
 */
object SportsConfig {
    data class Team(val name: String, val league: String, val espnPath: String, val espnId: String)

    val myTeams = listOf(
        Team("Alabama Crimson Tide", "College Football", "football/college-football", "333"),
        Team("New England Patriots", "NFL", "football/nfl", "17"),
        Team("Los Angeles Lakers", "NBA", "basketball/nba", "13"),
        Team("Los Angeles Dodgers", "MLB", "baseball/mlb", "19"),
    )

    /** How many upcoming games to show per team. */
    const val gamesPerTeam = 2

    /** Show every NFL game of the current week. */
    const val showAllNfl = true

    /**
     * UFC: only numbered events ("UFC 322: ..."), which were the pay-per-views.
     * Since 2026 these stream on Paramount+ rather than as PPV, but they're still the big cards.
     */
    val ufcBigEventPattern = Regex("""^UFC \d+""")

    /** TheSportsDB league id for boxing. */
    const val sportsDbBoxingLeague = "4445"

    /** Formula 1 race weekends. */
    const val showF1 = true
}
