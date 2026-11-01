package com.launcher.tv.data.tmdb

import com.launcher.tv.BuildConfig
import com.launcher.tv.data.Http
import com.launcher.tv.data.obj
import com.launcher.tv.data.str
import java.util.concurrent.ConcurrentHashMap

/**
 * Artwork from TMDB (Trakt doesn't host images). Optional: without a key, cards show text only.
 */
object Tmdb {
    /** Set at app start from [com.launcher.tv.data.ApiKeys] (entered on the TV / phone page). */
    @Volatile var apiKey: String = BuildConfig.TMDB_API_KEY

    enum class Type(val path: String) { TV("tv"), MOVIE("movie") }

    private data class Art(val poster: String?, val backdrop: String?, val overview: String?, val imdb: String? = null)

    private val cache = ConcurrentHashMap<String, Art>()
    private const val IMG = "https://image.tmdb.org/t/p"

    suspend fun poster(type: Type, tmdbId: Long?): String? =
        art(type, tmdbId)?.poster?.let { "$IMG/w342$it" }

    suspend fun backdrop(type: Type, tmdbId: Long?): String? =
        art(type, tmdbId)?.let { a -> (a.backdrop ?: a.poster)?.let { "$IMG/w780$it" } }

    /** Short description for the preview area. */
    suspend fun overview(type: Type, tmdbId: Long?): String? = art(type, tmdbId)?.overview

    /** The IMDb id (Stremio and Nuvio find titles by it). */
    suspend fun imdbId(type: Type, tmdbId: Long?): String? = art(type, tmdbId)?.imdb

    private suspend fun art(type: Type, tmdbId: Long?): Art? {
        val key = apiKey
        if (key.isBlank() || tmdbId == null) return null
        val cacheKey = "${type.path}/$tmdbId"
        cache[cacheKey]?.let { return it }
        return runCatching {
            val o = Http.getJson("https://api.themoviedb.org/3/${type.path}/$tmdbId?api_key=$key&append_to_response=external_ids").obj()
            // Movies carry imdb_id themselves; shows only in external_ids.
            Art(o?.str("poster_path"), o?.str("backdrop_path"), o?.str("overview"), o?.str("imdb_id") ?: o?.obj("external_ids")?.str("imdb_id"))
        }.getOrNull()?.also { cache[cacheKey] = it }
    }
}
