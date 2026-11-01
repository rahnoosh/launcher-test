package com.launcher.tv.data

/**
 * Merges the Trakt and Simkl versions of the home rows: one card per title, carrying both
 * services' ids so what you watch is reported to both.
 */
object TrackerMerge {

    /** Next up: if a show is on both, the service you watched it on more recently knows the right next episode. */
    fun upNext(trakt: List<MediaItem>, simkl: List<MediaItem>): List<MediaItem> {
        val out = trakt.toMutableList()
        for (s in simkl) {
            val i = out.indexOfFirst { sameTitle(it, s) }
            if (i < 0) {
                out += s
                continue
            }
            val t = out[i]
            out[i] = if ((s.sortMillis ?: 0L) > (t.sortMillis ?: 0L)) combine(s, t) else combine(t, s)
        }
        return out.sortedByDescending { it.sortMillis ?: 0L }
    }

    /** Watchlist movies from both, most recently added first. */
    fun watchlist(trakt: List<MediaItem>, simkl: List<MediaItem>): List<MediaItem> {
        val out = trakt.toMutableList()
        for (s in simkl) {
            val i = out.indexOfFirst { sameTitle(it, s) }
            if (i < 0) out += s else out[i] = combine(out[i], s)
        }
        return out.sortedByDescending { it.sortMillis ?: 0L }
    }

    /** Upcoming episodes from both calendars, soonest first. */
    fun upcoming(trakt: List<MediaItem>, simkl: List<MediaItem>): List<MediaItem> {
        val out = trakt.toMutableList()
        for (s in simkl) {
            val i = out.indexOfFirst {
                sameTitle(it, s) && it.episode == s.episode && (it.season == null || s.season == null || it.season == s.season)
            }
            if (i < 0) out += s else out[i] = combine(out[i], s)
        }
        return out.sortedBy { it.timeMillis ?: Long.MAX_VALUE }
    }

    /** The same show or movie: a shared TMDB, IMDb or Simkl id, or the same title and year. */
    fun sameTitle(a: MediaItem, b: MediaItem): Boolean {
        if ((a.kind == MediaKind.MOVIE) != (b.kind == MediaKind.MOVIE)) return false
        if (a.tmdbId != null && a.tmdbId == b.tmdbId) return true
        if (a.imdbId != null && a.imdbId == b.imdbId) return true
        if (a.simklId != null && a.simklId == b.simklId) return true
        if (a.year != null && b.year != null && a.year != b.year) return false
        return norm(a.title).isNotEmpty() && norm(a.title) == norm(b.title)
    }

    /** [primary]'s episode and artwork, plus whatever ids and details only [other] has. */
    fun combine(primary: MediaItem, other: MediaItem): MediaItem = primary.copy(
        imageUrl = primary.imageUrl ?: other.imageUrl,
        posterUrl = primary.posterUrl ?: other.posterUrl,
        overview = primary.overview ?: other.overview,
        traktId = primary.traktId ?: other.traktId,
        tmdbId = primary.tmdbId ?: other.tmdbId,
        simklId = primary.simklId ?: other.simklId,
        imdbId = primary.imdbId ?: other.imdbId,
        simklUrl = primary.simklUrl ?: other.simklUrl,
        year = primary.year ?: other.year,
        anime = primary.anime || other.anime,
        sortMillis = maxOf(primary.sortMillis ?: 0L, other.sortMillis ?: 0L).takeIf { it > 0 },
    )

    private fun norm(title: String) = title.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]"), "")
}
