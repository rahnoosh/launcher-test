package com.launcher.tv.data.simkl

import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import com.launcher.tv.data.arr
import com.launcher.tv.data.obj
import com.launcher.tv.data.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime

/** One title in the user's Simkl library, trimmed to what HomeBase shows. */
@Serializable
data class SimklItem(
    /** The all-items bucket it came from: "shows", "movies" or "anime". */
    val type: String,
    val simkl: Long,
    val title: String,
    val year: Int? = null,
    val slug: String? = null,
    /** Simkl image path, e.g. "24/24273cee77f9d9f". */
    val poster: String? = null,
    val tmdb: Long? = null,
    val imdb: String? = null,
    val tvdb: String? = null,
    /** watching, plantowatch, hold, completed or dropped. */
    val status: String? = null,
    /** "S02E04" (or "E12" for some anime); null when the user is caught up. */
    val nextToWatch: String? = null,
    val nextTitle: String? = null,
    val nextAired: String? = null,
    val lastWatchedAt: String? = null,
    val addedAt: String? = null,
) {
    val key: String get() = "$type:$simkl"
}

/** The local copy of the user's Simkl library and the /sync/activities snapshot it is current to. */
@Serializable
data class SimklLibrary(
    val items: Map<String, SimklItem> = emptyMap(),
    /** The whole /sync/activities response, saved only after every fetch it anchors succeeded. */
    val snapshot: String? = null,
)

/** A scheduled episode from Simkl's calendar files. */
data class SimklAiring(val simkl: Long, val atMillis: Long, val season: Int?, val episode: Int, val title: String?, val fanart: String?, val poster: String?)

/**
 * Parsing and the sync rules from https://api.simkl.org/guides/sync, kept free of Android and network code
 * so they can be unit-tested. [SimklClient] does the I/O.
 */
object SimklData {
    val TYPES = listOf("shows", "anime", "movies")
    /** Activities blocks for each all-items bucket. */
    private val ACTIVITY_BLOCK = mapOf("shows" to "tv_shows", "anime" to "anime", "movies" to "movies")
    /** Older client ids see "notinteresting" where newer ones see "dropped". */
    private val STATUSES = listOf("watching", "plantowatch", "hold", "completed", "dropped", "notinteresting")

    // ---------- parsing ----------

    /** Items of a /sync/all-items response ({ shows: [...], anime: [...], movies: [...] }, or {} when empty). */
    fun parseAllItems(root: JsonElement?): List<SimklItem> {
        val o = root.obj() ?: return emptyList()
        return TYPES.flatMap { type ->
            o.arr(type).orEmpty().mapNotNull { e -> parseEntry(type, e.obj() ?: return@mapNotNull null) }
        }
    }

    private fun parseEntry(type: String, e: JsonObject): SimklItem? {
        val media = e.obj("show") ?: e.obj("movie") ?: e.obj("anime") ?: return null
        val ids = media.obj("ids") ?: return null
        val simkl = idLong(ids, "simkl") ?: idLong(ids, "simkl_id") ?: return null
        val next = e.obj("next_to_watch_info")
        return SimklItem(
            type = type,
            simkl = simkl,
            title = media.str("title") ?: return null,
            year = media.str("year")?.toIntOrNull(),
            slug = ids.str("slug"),
            poster = media.str("poster"),
            tmdb = (ids.str("tmdb") ?: ids.str("moviedb"))?.toLongOrNull(),
            imdb = ids.str("imdb"),
            tvdb = ids.str("tvdb"),
            status = e.str("status"),
            nextToWatch = e.str("next_to_watch"),
            nextTitle = next?.str("title"),
            nextAired = next?.str("date"),
            lastWatchedAt = e.str("last_watched_at"),
            addedAt = e.str("added_to_watchlist_at"),
        )
    }

    /** Keys ("type:simklId") of an all-items response requested with extended=simkl_ids_only. */
    fun parseKeys(root: JsonElement?): Set<String> {
        val o = root.obj() ?: return emptySet()
        return TYPES.flatMapTo(mutableSetOf()) { type ->
            o.arr(type).orEmpty().mapNotNull { e ->
                val entry = e.obj() ?: return@mapNotNull null
                val ids = (entry.obj("show") ?: entry.obj("movie") ?: entry.obj("anime"))?.obj("ids") ?: entry.obj("ids")
                (ids?.let { idLong(it, "simkl") ?: idLong(it, "simkl_id") })?.let { "$type:$it" }
            }
        }
    }

    private fun idLong(ids: JsonObject, key: String): Long? =
        (ids[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }

    /**
     * Episodes of shows in [wanted] from a v2 calendar file ({ calendar: [...], metadata: { id: {...} } }).
     * Decoded into small typed objects (unknown fields skipped) rather than one big JSON tree: the file is several MB.
     */
    fun parseCalendar(text: String, wanted: Set<Long>): List<SimklAiring> {
        val file = calendarJson.decodeFromString(CalendarFile.serializer(), text)
        return file.calendar.mapNotNull { c ->
            val id = c.simklId?.let(::primitiveLong) ?: return@mapNotNull null
            if (id !in wanted) return@mapNotNull null
            val ep = c.episode ?: return@mapNotNull null
            val number = ep.episode?.let(::primitiveLong)?.toInt() ?: return@mapNotNull null
            val m = file.metadata[id.toString()]
            SimklAiring(
                simkl = id,
                atMillis = parseTime(c.date) ?: return@mapNotNull null,
                season = ep.season?.let(::primitiveLong)?.toInt(),
                episode = number,
                title = ep.title,
                fanart = m?.fanart,
                poster = m?.poster,
            )
        }
    }

    private val calendarJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private fun primitiveLong(p: JsonPrimitive): Long? = p.longOrNull ?: p.content.toLongOrNull()

    @Serializable
    private data class CalendarFile(val calendar: List<CalendarEntry> = emptyList(), val metadata: Map<String, CalendarMeta> = emptyMap())

    @Serializable
    private data class CalendarEntry(
        @kotlinx.serialization.SerialName("simkl_id") val simklId: JsonPrimitive? = null,
        val date: String? = null,
        val episode: CalendarEpisode? = null,
    )

    @Serializable
    private data class CalendarEpisode(val season: JsonPrimitive? = null, val episode: JsonPrimitive? = null, val title: String? = null)

    @Serializable
    private data class CalendarMeta(val poster: String? = null, val fanart: String? = null)

    // ---------- sync rules ----------

    /** What a fresh /sync/activities response says needs fetching, compared with the saved snapshot. */
    data class Changes(val items: Boolean, val removals: Boolean) {
        val any get() = items || removals
    }

    /** Null means there's no usable snapshot: do the one-time full pull. */
    fun changes(saved: JsonObject?, fresh: JsonObject): Changes? {
        if (saved == null || saved.str("all") == null) return null
        if (saved.str("all") == fresh.str("all")) return Changes(items = false, removals = false)
        val blocks = ACTIVITY_BLOCK.values
        val items = blocks.any { b -> STATUSES.any { s -> saved.obj(b)?.str(s) != fresh.obj(b)?.str(s) } }
        val removals = blocks.any { b -> saved.obj(b)?.str("removed_from_list") != fresh.obj(b)?.str("removed_from_list") }
        return Changes(items, removals)
    }

    /** Deltas only carry added or changed items, so they're merged in, never replace the library. */
    fun merge(items: Map<String, SimklItem>, changed: List<SimklItem>): Map<String, SimklItem> =
        items + changed.associateBy { it.key }

    // ---------- home rows ----------

    /** Shows you're partway through. Simkl's next_to_watch is null once you're caught up (never use the counts). */
    fun upNext(lib: SimklLibrary, limit: Int = 20): List<MediaItem> =
        lib.items.values
            .filter { it.type != "movies" && it.status == "watching" && it.nextToWatch != null }
            .sortedByDescending { parseTime(it.lastWatchedAt) ?: 0L }
            .take(limit)
            .mapNotNull { s ->
                val (season, episode) = episodeCode(s.nextToWatch) ?: return@mapNotNull null
                val code = if (season != null) "S%02dE%02d".format(season, episode) else "Episode $episode"
                base(s, MediaKind.EPISODE, "upnext").copy(
                    subtitle = if (s.nextTitle.isNullOrBlank()) code else "$code · ${s.nextTitle}",
                    timeMillis = parseTime(s.nextAired),
                    season = season,
                    episode = episode,
                    sortMillis = parseTime(s.lastWatchedAt),
                )
            }

    /** Movies on your Plan to Watch list, newest first. */
    fun watchlistMovies(lib: SimklLibrary, limit: Int = 40): List<MediaItem> =
        lib.items.values
            .filter { it.type == "movies" && it.status == "plantowatch" }
            .sortedByDescending { parseTime(it.addedAt) ?: 0L }
            .take(limit)
            .map { base(it, MediaKind.MOVIE, "movie").copy(subtitle = it.year?.toString().orEmpty(), sortMillis = parseTime(it.addedAt)) }

    /** Shows and anime the calendar files should be checked for. */
    fun followed(lib: SimklLibrary): Map<Long, SimklItem> =
        lib.items.values.filter { it.type != "movies" && it.status in setOf("watching", "plantowatch", "hold") }.associateBy { it.simkl }

    /** New episodes of shows you follow, from today through [days] days ahead. */
    fun upcoming(lib: SimklLibrary, airings: List<SimklAiring>, now: Long, days: Int = 14): List<MediaItem> {
        val shows = followed(lib)
        val from = startOfDay(now)
        val to = now + days * 86_400_000L
        return airings.filter { it.atMillis in from..to }
            .mapNotNull { a ->
                val s = shows[a.simkl] ?: return@mapNotNull null
                val code = if (a.season != null) "S%02dE%02d".format(a.season, a.episode) else "Episode ${a.episode}"
                base(s, MediaKind.EPISODE, "cal").copy(
                    id = "cal-simkl-${a.simkl}-${a.season ?: 0}-${a.episode}",
                    subtitle = if (a.title.isNullOrBlank()) code else "$code · ${a.title}",
                    imageUrl = a.fanart?.let(::fanartUrl) ?: posterUrl(s.poster, wide = true),
                    timeMillis = a.atMillis,
                    season = a.season,
                    episode = a.episode,
                )
            }
            .distinctBy { it.id }
            .sortedBy { it.timeMillis ?: Long.MAX_VALUE }
    }

    private fun base(s: SimklItem, kind: MediaKind, prefix: String) = MediaItem(
        id = "$prefix-simkl-${s.simkl}",
        kind = kind,
        title = s.title,
        subtitle = "",
        imageUrl = posterUrl(s.poster, wide = true),
        posterUrl = posterUrl(s.poster, wide = false),
        tmdbId = s.tmdb,
        year = s.year,
        simklId = s.simkl,
        imdbId = s.imdb,
        anime = s.type == "anime",
        simklUrl = pageUrl(s.type, s.simkl, s.slug),
    )

    // ---------- helpers ----------

    /** "S02E04" -> (2, 4); "E12" -> (null, 12). */
    fun episodeCode(code: String?): Pair<Int?, Int>? {
        if (code == null) return null
        Regex("""^S(\d+)E(\d+)$""", RegexOption.IGNORE_CASE).find(code.trim())?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        Regex("""^E(\d+)$""", RegexOption.IGNORE_CASE).find(code.trim())?.let { return null to it.groupValues[1].toInt() }
        return null
    }

    /** Simkl serves images through the wsrv.nl proxy; sizes from https://api.simkl.org/conventions/images. */
    fun posterUrl(path: String?, wide: Boolean): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://wsrv.nl/?url=https://simkl.in/posters/${it}_${if (wide) "w" else "m"}.webp&q=90" }

    fun fanartUrl(path: String): String = "https://wsrv.nl/?url=https://simkl.in/fanart/${path}_medium.webp&q=90"

    /** The title's own Simkl page (Simkl asks apps to link back to it). */
    fun pageUrl(type: String, simkl: Long, slug: String?): String {
        val section = when (type) { "movies" -> "movies"; "anime" -> "anime"; else -> "tv" }
        return "https://simkl.com/$section/$simkl" + (slug?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: "")
    }

    fun parseTime(iso: String?): Long? = iso?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    }

    private fun startOfDay(now: Long): Long {
        val zone = java.time.ZoneId.systemDefault()
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
