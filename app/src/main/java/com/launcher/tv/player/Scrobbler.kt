package com.launcher.tv.player

import com.launcher.tv.data.Http
import com.launcher.tv.data.tmdb.Tmdb
import com.launcher.tv.data.trakt.TraktClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Matches what's playing to a Trakt movie/episode and reports playback to Trakt's scrobble API.
 * Trakt itself marks the item watched when a scrobble stops at >= 80% progress, and keeps
 * a resume point below that. Docs: https://trakt.docs.apiary.io/#reference/scrobble
 */
class Scrobbler(private val trakt: TraktClient) {

    data class Match(
        /** Body fragment identifying the item: {"movie": {...}} or {"show": {...}, "episode": {...}}. */
        val item: JsonObject,
        val displayTitle: String,
        val subtitle: String,
        val imageUrl: String?,
        val posterUrl: String? = null,
    )

    val enabled: Boolean get() = trakt.isConfigured && trakt.isSignedIn

    suspend fun identify(parsed: TitleParser.Parsed): Match? {
        if (!enabled || !parsed.canScrobble) return null
        return try {
            when (parsed.kind) {
                TitleParser.Kind.MOVIE -> {
                    val movie = trakt.searchMovie(parsed.name, parsed.year)
                        ?: parsed.year?.let { trakt.searchMovie(parsed.name, null) } // provider years are often off by one
                        ?: return null
                    Match(
                        item = buildJsonObject {
                            putJsonObject("movie") { putJsonObject("ids") { put("trakt", movie.ids.trakt) } }
                        },
                        displayTitle = movie.title,
                        subtitle = movie.year?.toString().orEmpty(),
                        imageUrl = Tmdb.backdrop(Tmdb.Type.MOVIE, movie.ids.tmdb),
                        posterUrl = Tmdb.poster(Tmdb.Type.MOVIE, movie.ids.tmdb),
                    )
                }
                TitleParser.Kind.EPISODE -> {
                    val show = trakt.searchShow(parsed.name, parsed.year)
                        ?: parsed.year?.let { trakt.searchShow(parsed.name, null) }
                        ?: return null
                    val s = parsed.season ?: return null
                    val e = parsed.episode ?: return null
                    Match(
                        item = buildJsonObject {
                            putJsonObject("show") { putJsonObject("ids") { put("trakt", show.ids.trakt) } }
                            putJsonObject("episode") { put("season", s); put("number", e) }
                        },
                        displayTitle = show.title,
                        subtitle = "S%02dE%02d".format(s, e),
                        imageUrl = Tmdb.backdrop(Tmdb.Type.TV, show.ids.tmdb),
                        posterUrl = Tmdb.poster(Tmdb.Type.TV, show.ids.tmdb),
                    )
                }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun start(match: Match, progress: Float) = send("start", match, progress)
    suspend fun pause(match: Match, progress: Float) = send("pause", match, progress)
    suspend fun stop(match: Match, progress: Float) = send("stop", match, progress)

    private suspend fun send(action: String, match: Match, progress: Float) {
        val body = JsonObject(match.item + ("progress" to kotlinx.serialization.json.JsonPrimitive(progress.coerceIn(0f, 100f))))
        try {
            trakt.authedPost("/scrobble/$action", Http.json.encodeToString(JsonObject.serializer(), body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 409 = already scrobbled recently; network errors are not worth interrupting playback for.
        }
    }
}
