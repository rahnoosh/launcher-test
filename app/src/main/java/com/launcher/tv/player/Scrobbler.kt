package com.launcher.tv.player

import com.launcher.tv.data.simkl.SimklClient
import com.launcher.tv.data.tmdb.Tmdb
import com.launcher.tv.data.trakt.TraktClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Works out what's playing and reports it to Trakt and Simkl (whichever you've connected).
 * Both mark it watched when playback stops at 80% or more, and keep a resume point below that.
 * Docs: https://trakt.docs.apiary.io/#reference/scrobble and https://api.simkl.org/guides/scrobble
 */
class Scrobbler(private val trakt: TraktClient, private val simkl: SimklClient) {

    /** A movie or episode, with every id known for it. */
    data class Match(
        val movie: Boolean,
        /** Show or movie title. */
        val title: String,
        val year: Int? = null,
        /** Null for anime numbered Simkl's way (episodes restart in each cour). */
        val season: Int? = null,
        val episode: Int? = null,
        val traktId: Long? = null,
        val simklId: Long? = null,
        val tmdbId: Long? = null,
        val imdbId: String? = null,
        val tvdbId: String? = null,
        val anime: Boolean = false,
        val displayTitle: String = title,
        val subtitle: String = "",
        val imageUrl: String? = null,
        val posterUrl: String? = null,
    )

    private val traktOn: Boolean get() = trakt.isConfigured && trakt.isSignedIn
    private val simklOn: Boolean get() = simkl.isConfigured && simkl.isSignedIn

    val enabled: Boolean get() = traktOn || simklOn

    /** Identifies something played through the TiviMate bridge: Trakt search first, then Simkl's file-name matcher. */
    suspend fun identify(parsed: TitleParser.Parsed): Match? {
        if (!enabled || !parsed.canScrobble) return null
        if (traktOn) quietly { identifyWithTrakt(parsed) }?.let { return it }
        if (simklOn) quietly { identifyWithSimkl(parsed) }?.let { return it }
        return null
    }

    private suspend fun identifyWithTrakt(parsed: TitleParser.Parsed): Match? = when (parsed.kind) {
        TitleParser.Kind.MOVIE -> {
            val movie = trakt.searchMovie(parsed.name, parsed.year)
                ?: parsed.year?.let { trakt.searchMovie(parsed.name, null) } // provider years are often off by one
            movie?.let {
                Match(
                    movie = true, title = it.title, year = it.year,
                    traktId = it.ids.trakt, tmdbId = it.ids.tmdb, imdbId = it.ids.imdb, tvdbId = it.ids.tvdb?.toString(),
                    subtitle = it.year?.toString().orEmpty(),
                    imageUrl = Tmdb.backdrop(Tmdb.Type.MOVIE, it.ids.tmdb),
                    posterUrl = Tmdb.poster(Tmdb.Type.MOVIE, it.ids.tmdb),
                )
            }
        }
        TitleParser.Kind.EPISODE -> {
            val show = trakt.searchShow(parsed.name, parsed.year)
                ?: parsed.year?.let { trakt.searchShow(parsed.name, null) }
            val s = parsed.season
            val e = parsed.episode
            if (show == null || s == null || e == null) null else Match(
                movie = false, title = show.title, year = show.year, season = s, episode = e,
                traktId = show.ids.trakt, tmdbId = show.ids.tmdb, imdbId = show.ids.imdb, tvdbId = show.ids.tvdb?.toString(),
                subtitle = "S%02dE%02d".format(s, e),
                imageUrl = Tmdb.backdrop(Tmdb.Type.TV, show.ids.tmdb),
                posterUrl = Tmdb.poster(Tmdb.Type.TV, show.ids.tmdb),
            )
        }
        else -> null
    }

    private suspend fun identifyWithSimkl(parsed: TitleParser.Parsed): Match? {
        val f = simkl.searchFile(fileName(parsed) ?: return null) ?: return null
        if (f.movie != (parsed.kind == TitleParser.Kind.MOVIE)) return null
        if (!f.movie && f.episode == null) return null
        val type = if (f.movie) Tmdb.Type.MOVIE else Tmdb.Type.TV
        // Simkl's own numbering for anime is per cour: keep it seasonless so it goes back with the anime's id.
        val season = if (f.anime) null else f.season
        return Match(
            movie = f.movie, title = f.title, year = f.year, season = season, episode = f.episode,
            simklId = f.simkl, tmdbId = f.tmdb, imdbId = f.imdb, tvdbId = f.tvdb, anime = f.anime,
            subtitle = when {
                f.movie -> f.year?.toString().orEmpty()
                season != null -> "S%02dE%02d".format(season, f.episode)
                else -> "Episode ${f.episode}"
            },
            imageUrl = Tmdb.backdrop(type, f.tmdb),
            posterUrl = Tmdb.poster(type, f.tmdb),
        )
    }

    suspend fun start(match: Match, progress: Float) = send("start", match, progress)
    suspend fun pause(match: Match, progress: Float) = send("pause", match, progress)
    suspend fun stop(match: Match, progress: Float) = send("stop", match, progress)

    private suspend fun send(action: String, match: Match, progress: Float) = coroutineScope {
        if (traktOn) traktBody(match, progress)?.let { body ->
            launch { quietly { trakt.authedPost("/scrobble/$action", body.toString()) } }
        }
        if (simklOn && simkl.canWrite) simklBody(match, progress)?.let { body ->
            launch { quietly { simkl.scrobble(action, body) } }
        }
    }

    /** 409 = already scrobbled, 429 = too soon; network errors aren't worth interrupting playback for. */
    private suspend fun <T> quietly(block: suspend () -> T): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    companion object {
        /** Trakt needs an id it knows, and a season for episodes. */
        fun traktBody(m: Match, progress: Float): JsonObject? {
            if (!m.movie && (m.season == null || m.episode == null)) return null
            val ids = buildJsonObject {
                m.traktId?.let { put("trakt", it) }
                m.tmdbId?.let { put("tmdb", it) }
                m.imdbId?.let { put("imdb", it) }
                m.tvdbId?.toLongOrNull()?.let { put("tvdb", it) }
            }
            if (ids.isEmpty()) return null
            return buildJsonObject {
                if (m.movie) {
                    putJsonObject("movie") { put("ids", ids) }
                } else {
                    putJsonObject("show") { put("ids", ids) }
                    putJsonObject("episode") { put("season", m.season); put("number", m.episode) }
                }
                put("progress", progress.coerceIn(0f, 100f))
            }
        }

        /**
         * Simkl takes the title, year and every id, and resolves them itself. Anime numbered Simkl's way
         * (no season) goes with the anime's own id; with a season the numbering is TVDB/TMDB style, so
         * those ids are sent instead.
         */
        fun simklBody(m: Match, progress: Float): JsonObject? {
            if (!m.movie && m.episode == null) return null
            val seasonless = m.anime && m.season == null
            val western = buildMap<String, JsonPrimitive> {
                m.imdbId?.let { put("imdb", JsonPrimitive(it)) }
                m.tmdbId?.let { put("tmdb", JsonPrimitive(it)) }
                m.tvdbId?.let { v -> put("tvdb", v.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)) }
            }
            val ids = buildJsonObject {
                if (m.simklId != null && (seasonless || !m.anime || western.isEmpty())) put("simkl", m.simklId)
                if (!seasonless) western.forEach { (k, v) -> put(k, v) }
            }
            return buildJsonObject {
                put("progress", progress.coerceIn(0f, 100f))
                val key = when {
                    m.movie -> "movie"
                    m.anime -> "anime"
                    else -> "show"
                }
                putJsonObject(key) {
                    if (m.title.isNotBlank()) put("title", m.title)
                    m.year?.let { put("year", it) }
                    put("ids", ids)
                }
                if (!m.movie) putJsonObject("episode") {
                    if (!seasonless) m.season?.let { put("season", it) }
                    put("number", m.episode)
                }
            }
        }

        /** A file name for Simkl's matcher, e.g. "The Bear (2022) S02E03.mkv". */
        fun fileName(p: TitleParser.Parsed): String? {
            val base = p.name.trim().ifEmpty { return null } + (p.year?.let { " ($it)" } ?: "")
            return when (p.kind) {
                TitleParser.Kind.MOVIE -> "$base.mkv"
                TitleParser.Kind.EPISODE -> "$base S%02dE%02d.mkv".format(p.season ?: return null, p.episode ?: return null)
                else -> null
            }
        }
    }
}
