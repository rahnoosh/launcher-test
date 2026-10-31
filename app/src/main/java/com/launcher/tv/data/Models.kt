package com.launcher.tv.data

import kotlinx.serialization.Serializable

@Serializable
enum class MediaKind { EPISODE, MOVIE, SHOW }

@Serializable
data class MediaItem(
    val id: String,
    val kind: MediaKind,
    val title: String,
    val subtitle: String,
    /** Wide artwork (TMDB backdrop), used for the full-screen hero. */
    val imageUrl: String? = null,
    /** Portrait artwork (TMDB poster), used for tiles. */
    val posterUrl: String? = null,
    /** Air date: for "next up" episodes when it aired, for upcoming ones when it will air. */
    val timeMillis: Long? = null,
)

@Serializable
data class TeamInfo(
    val name: String,
    val abbr: String,
    /** Hex without '#', as ESPN sends it ("002244"). */
    val color: String? = null,
    val altColor: String? = null,
    val logo: String? = null,
    val score: String? = null,
)

@Serializable
data class SportsEvent(
    val id: String,
    val title: String,
    /** "NFL", "College Football", "UFC", "Boxing", "F1"... */
    val league: String,
    val startMillis: Long,
    val live: Boolean = false,
    /** Score or status line while live. */
    val status: String? = null,
    /** TV channels / streaming service. */
    val broadcast: String? = null,
    val imageUrl: String? = null,
    val venue: String? = null,
    /** Only for team sports (ESPN). */
    val home: TeamInfo? = null,
    val away: TeamInfo? = null,
)

/** Everything the home screen shows. Cached to disk so Home opens instantly. */
@Serializable
data class HomeData(
    val upNext: List<MediaItem> = emptyList(),
    val watchlist: List<MediaItem> = emptyList(),
    val upcomingEpisodes: List<MediaItem> = emptyList(),
    val myTeams: List<SportsEvent> = emptyList(),
    val nfl: List<SportsEvent> = emptyList(),
    val fights: List<SportsEvent> = emptyList(),
    val f1: List<SportsEvent> = emptyList(),
    val fetchedAt: Long = 0,
)
