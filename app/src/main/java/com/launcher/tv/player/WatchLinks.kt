package com.launcher.tv.player

import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind

/** How Stremio and Nuvio want a movie or episode handed to them (see [WatchApps]). */
object WatchLinks {
    private fun isMovie(item: MediaItem) = item.kind == MediaKind.MOVIE

    /** Stremio's stream list for the movie, or for the episode ("tt…:season:episode", Cinemeta's id). */
    fun stremioUri(item: MediaItem, imdb: String): String = when {
        isMovie(item) -> "stremio:///detail/movie/$imdb/$imdb"
        item.season != null && item.episode != null -> "stremio:///detail/series/$imdb/$imdb:${item.season}:${item.episode}"
        else -> "stremio:///detail/series/$imdb"
    }

    /** Nuvio's own link to the title's page (used if the direct launch isn't accepted). */
    fun nuvioUri(item: MediaItem, imdb: String): String =
        "nuvio://${if (isMovie(item)) "movie" else "series"}/$imdb"

    /** The extras Nuvio's Continue Watching channel puts on its launch intent ("stream" mode opens the stream list). */
    fun nuvioExtras(item: MediaItem, imdb: String): Map<String, Any> = buildMap {
        val movie = isMovie(item)
        val season = item.season
        val episode = item.episode
        put("contentId", imdb)
        put("contentType", if (movie) "movie" else "series")
        put("name", item.title)
        if (movie || (season != null && episode != null)) {
            put("launchMode", "stream")
            put("videoId", if (movie) imdb else "$imdb:$season:$episode")
        }
        if (!movie && season != null && episode != null) {
            put("season", season)
            put("episode", episode)
        }
        item.posterUrl?.let { put("poster", it) }
        item.imageUrl?.let { put("backdrop", it) }
    }
}
