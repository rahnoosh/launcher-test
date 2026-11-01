package com.launcher.tv.data.trakt

import android.content.Context
import com.launcher.tv.BuildConfig
import com.launcher.tv.data.Http
import com.launcher.tv.data.HttpException
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import com.launcher.tv.data.tmdb.Tmdb
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Trakt API client: device-code sign-in (built for TVs), next episodes,
 * movie watchlist and the personal episode calendar.
 * Docs: https://trakt.docs.apiary.io
 */
class TraktClient(context: Context) {
    private val prefs = com.launcher.tv.data.Profiles.prefs(context, "trakt")
    private val appContext = context.applicationContext
    // Read each time: keys can be entered later on the TV or the phone page.
    private val clientId: String get() = com.launcher.tv.data.ApiKeys.traktId(appContext)
    private val clientSecret: String get() = com.launcher.tv.data.ApiKeys.traktSecret(appContext)

    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val isSignedIn: Boolean get() = prefs.getString(KEY_ACCESS, null) != null

    // ---------- Sign-in ----------

    suspend fun startDeviceAuth(): DeviceCode {
        val body = buildJsonObject { put("client_id", clientId) }.toString()
        return Http.json.decodeFromString(Http.post("$BASE/oauth/device/code", body))
    }

    /** Polls until the user enters the code on trakt.tv/activate. Returns true on success. */
    suspend fun awaitDeviceToken(code: DeviceCode): Boolean {
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        var interval = code.interval.coerceAtLeast(1) * 1000L
        val body = buildJsonObject {
            put("code", code.deviceCode)
            put("client_id", clientId)
            put("client_secret", clientSecret)
        }.toString()
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            try {
                saveToken(Http.json.decodeFromString(Http.post("$BASE/oauth/device/token", body)))
                return true
            } catch (e: HttpException) {
                when (e.code) {
                    400 -> Unit                 // still waiting for the user
                    429 -> interval += 1000     // polling too fast
                    else -> return false        // 404 bad code, 409 used, 410 expired, 418 denied
                }
            }
        }
        return false
    }

    fun signOut() = prefs.edit().clear().apply()

    private fun saveToken(t: TokenResponse) {
        prefs.edit()
            .putString(KEY_ACCESS, t.accessToken)
            .putString(KEY_REFRESH, t.refreshToken)
            .putLong(KEY_EXPIRES, (t.createdAt + t.expiresIn) * 1000L)
            .apply()
    }

    private suspend fun refreshToken() {
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return
        val body = buildJsonObject {
            put("refresh_token", refresh)
            put("client_id", clientId)
            put("client_secret", clientSecret)
            put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
            put("grant_type", "refresh_token")
        }.toString()
        try {
            saveToken(Http.json.decodeFromString(Http.post("$BASE/oauth/token", body)))
        } catch (e: HttpException) {
            if (e.code in 400..401) signOut() // refresh token revoked: ask user to sign in again
            throw e
        }
    }

    private fun authHeaders() = mapOf(
        "trakt-api-version" to "2",
        "trakt-api-key" to clientId,
        "Authorization" to "Bearer ${prefs.getString(KEY_ACCESS, "")}",
    )

    private suspend fun authedGet(path: String): String = authed { Http.get("$BASE$path", authHeaders()) }

    suspend fun authedPost(path: String, body: String): String = authed { Http.post("$BASE$path", body, authHeaders()) }

    private suspend fun authed(call: suspend () -> String): String {
        if (System.currentTimeMillis() > prefs.getLong(KEY_EXPIRES, Long.MAX_VALUE) - 86_400_000L) {
            refreshToken()
        }
        return try {
            call()
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            refreshToken()
            call()
        }
    }

    // ---------- Search (used by the player to identify what's playing) ----------

    suspend fun searchShow(query: String, year: Int?): TraktShow? =
        search("show", query, year).firstNotNullOfOrNull { it.show }

    suspend fun searchMovie(query: String, year: Int?): TraktMovie? =
        search("movie", query, year).firstNotNullOfOrNull { it.movie }

    private suspend fun search(type: String, query: String, year: Int?): List<SearchResult> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val y = year?.let { "&years=$it" }.orEmpty()
        return Http.json.decodeFromString(authedGet("/search/$type?query=$q&fields=title,aliases&limit=5$y"))
    }

    // ---------- Data ----------

    /** Next unwatched episode for the shows you watched most recently. */
    suspend fun upNext(limit: Int = 20): List<MediaItem> = coroutineScope {
        val watched: List<WatchedShow> =
            Http.json.decodeFromString(authedGet("/sync/watched/shows?extended=noseasons"))
        watched
            .sortedByDescending { it.lastWatchedAt.orEmpty() }
            .take(limit)
            .map { ws ->
                async {
                    val id = ws.show.ids.trakt ?: return@async null
                    val progress = runCatching {
                        Http.json.decodeFromString<ShowProgress>(
                            authedGet("/shows/$id/progress/watched?hidden=false&specials=false&count_specials=false")
                        )
                    }.getOrNull() ?: return@async null
                    val next = progress.nextEpisode ?: return@async null
                    // Skip episodes that haven't aired yet; they belong in "Coming up".
                    val aired = parseTime(next.firstAired)
                    if (aired != null && aired > System.currentTimeMillis()) return@async null
                    MediaItem(
                        id = "upnext-$id",
                        kind = MediaKind.EPISODE,
                        title = ws.show.title,
                        subtitle = episodeLabel(next) +
                            if (progress.aired > progress.completed + 1) " · ${progress.aired - progress.completed} left" else "",
                        imageUrl = Tmdb.backdrop(Tmdb.Type.TV, ws.show.ids.tmdb),
                        posterUrl = Tmdb.poster(Tmdb.Type.TV, ws.show.ids.tmdb),
                        overview = Tmdb.overview(Tmdb.Type.TV, ws.show.ids.tmdb),
                        timeMillis = aired,
                        traktId = id,
                        tmdbId = ws.show.ids.tmdb,
                        imdbId = ws.show.ids.imdb,
                        year = ws.show.year,
                        season = next.season,
                        episode = next.number,
                        sortMillis = parseTime(ws.lastWatchedAt),
                    )
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    suspend fun watchlistMovies(limit: Int = 40): List<MediaItem> = coroutineScope {
        val list: List<WatchlistMovie> =
            Http.json.decodeFromString(authedGet("/sync/watchlist/movies/added"))
        list.take(limit).map { w ->
            async {
                MediaItem(
                    id = "movie-${w.movie.ids.trakt}",
                    kind = MediaKind.MOVIE,
                    title = w.movie.title,
                    subtitle = w.movie.year?.toString().orEmpty(),
                    imageUrl = Tmdb.backdrop(Tmdb.Type.MOVIE, w.movie.ids.tmdb),
                    posterUrl = Tmdb.poster(Tmdb.Type.MOVIE, w.movie.ids.tmdb),
                    overview = Tmdb.overview(Tmdb.Type.MOVIE, w.movie.ids.tmdb),
                    traktId = w.movie.ids.trakt,
                    tmdbId = w.movie.ids.tmdb,
                    imdbId = w.movie.ids.imdb,
                    year = w.movie.year,
                    sortMillis = parseTime(w.listedAt),
                )
            }
        }.awaitAll()
    }

    /** New episodes of shows you follow, airing over the next [days] days. */
    suspend fun upcomingEpisodes(days: Int = 14): List<MediaItem> = coroutineScope {
        val start = LocalDate.now().toString()
        val entries: List<CalendarEntry> =
            Http.json.decodeFromString(authedGet("/calendars/my/shows/$start/$days"))
        entries.map { c ->
            async {
                MediaItem(
                    id = "cal-${c.episode.ids.trakt}",
                    kind = MediaKind.EPISODE,
                    title = c.show.title,
                    subtitle = episodeLabel(c.episode),
                    imageUrl = Tmdb.backdrop(Tmdb.Type.TV, c.show.ids.tmdb),
                    posterUrl = Tmdb.poster(Tmdb.Type.TV, c.show.ids.tmdb),
                    overview = Tmdb.overview(Tmdb.Type.TV, c.show.ids.tmdb),
                    timeMillis = parseTime(c.firstAired),
                    traktId = c.show.ids.trakt,
                    tmdbId = c.show.ids.tmdb,
                    imdbId = c.show.ids.imdb,
                    year = c.show.year,
                    season = c.episode.season,
                    episode = c.episode.number,
                )
            }
        }.awaitAll().sortedBy { it.timeMillis ?: Long.MAX_VALUE }
    }

    private fun episodeLabel(e: TraktEpisode): String {
        val code = "S%02dE%02d".format(e.season, e.number)
        return if (e.title.isNullOrBlank()) code else "$code · ${e.title}"
    }

    private fun parseTime(iso: String?): Long? =
        iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    private companion object {
        const val BASE = "https://api.trakt.tv"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
    }
}
