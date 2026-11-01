package com.launcher.tv.data.simkl

import android.content.Context
import com.launcher.tv.BuildConfig
import com.launcher.tv.data.ApiKeys
import com.launcher.tv.data.Http
import com.launcher.tv.data.HttpException
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import com.launcher.tv.data.obj
import com.launcher.tv.data.str
import com.launcher.tv.data.tmdb.Tmdb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * Simkl, alongside (or instead of) Trakt: device sign-in with a QR code, a local copy of your
 * library kept current by Simkl's activities-then-deltas sync, the public calendar files for
 * upcoming episodes, and scrobbling.
 *
 * Simkl is strict about traffic (free accounts get 500 requests a day and timer polling can get an
 * app suspended), so HomeBase only syncs when you open the home screen, at most every 20 minutes,
 * or right after you've watched something or asked for a refresh. Docs: https://api.simkl.org
 */
class SimklClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("simkl", Context.MODE_PRIVATE)
    private val libraryFile get() = File(appContext.filesDir, "simkl_library.json")
    private val clientId: String get() = ApiKeys.simklId(appContext)

    val isConfigured: Boolean get() = clientId.isNotBlank()
    val isSignedIn: Boolean get() = prefs.getString(KEY_REFRESH, null) != null || prefs.getString(KEY_ACCESS, null) != null

    // ---------- Sign-in (OAuth 2 device flow) ----------

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        /** The approval page with the code already filled in: what the QR code opens. */
        val verificationUriComplete: String,
        val expiresIn: Int,
        val interval: Int,
    )

    suspend fun startDeviceAuth(): DeviceCode {
        val o = parseObject(Http.postForm("$BASE/oauth2/device", mapOf("client_id" to clientId, "scope" to SCOPE), userAgent()))
        val user = o.str("user_code") ?: throw IOException("Simkl didn't send a code")
        val uri = o.str("verification_uri") ?: "https://simkl.com/pin"
        return DeviceCode(
            deviceCode = o.str("device_code") ?: throw IOException("Simkl didn't send a device code"),
            userCode = user,
            verificationUri = uri,
            verificationUriComplete = o.str("verification_uri_complete") ?: "$uri?user_code=$user",
            expiresIn = o.str("expires_in")?.toIntOrNull() ?: 900,
            interval = o.str("interval")?.toIntOrNull() ?: 5,
        )
    }

    /**
     * Polls until you approve on your phone. Simkl never reports a refusal (it keeps answering
     * "pending"), so this gives up after 10 minutes. Returns true when signed in.
     */
    suspend fun awaitDeviceToken(code: DeviceCode): Boolean {
        val deadline = System.currentTimeMillis() + minOf(code.expiresIn, 600) * 1000L
        var interval = maxOf(code.interval, 5) * 1000L
        val form = mapOf(
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "client_id" to clientId,
            "device_code" to code.deviceCode,
        )
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            try {
                saveToken(Http.postForm("$BASE/oauth2/token", form, userAgent()))
                prefs.edit().putBoolean(KEY_DIRTY, true).apply()
                return true
            } catch (e: HttpException) {
                when {
                    oauthError(e.body) == "authorization_pending" -> Unit
                    oauthError(e.body) == "slow_down" -> interval += 5_000 // and actually wait: the attempt resets Simkl's timer
                    e.code >= 500 -> Unit
                    else -> return false // expired_token, invalid_client (a V1 client id), …
                }
            } catch (e: IOException) {
                // Network blip: keep polling until the deadline.
            }
        }
        return false
    }

    /** Write access (scrobbling) was granted. Simkl silently downgrades a bad scope to read-only. */
    val canWrite: Boolean get() = prefs.getString(KEY_SCOPE, null).let { it.isNullOrBlank() || "media:write" in it }

    /** Revokes this box's sign-in on Simkl and forgets the local library. */
    suspend fun signOut() {
        val token = prefs.getString(KEY_REFRESH, null) ?: prefs.getString(KEY_ACCESS, null)
        clearTokens()
        withContext(Dispatchers.IO) { libraryFile.delete() }
        if (token != null && clientId.isNotBlank()) {
            try {
                Http.postForm("$BASE/oauth2/revoke", mapOf("client_id" to clientId, "token" to token), userAgent())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Always 200 by design; a network error just leaves the grant to expire.
            }
        }
    }

    private fun saveToken(text: String) {
        val o = parseObject(text)
        val access = o.str("access_token") ?: throw IOException("Simkl didn't send a token")
        val expiresIn = o.str("expires_in")?.toLongOrNull() ?: 7 * 86_400L
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .apply { o.str("refresh_token")?.let { putString(KEY_REFRESH, it) } }
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + expiresIn * 1000L)
            .putString(KEY_SCOPE, o.str("scope").orEmpty())
            .apply()
    }

    private fun clearTokens() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES).remove(KEY_SCOPE).remove(KEY_LAST_SYNC).apply()
    }

    private fun expiringSoon() = System.currentTimeMillis() > prefs.getLong(KEY_EXPIRES, Long.MAX_VALUE) - 86_400_000L

    /**
     * Refreshing replaces the grant's access token, so two refreshes in parallel would cut each other
     * off: one at a time, and skip it if someone else already refreshed the token we were using.
     */
    private suspend fun refresh(used: String?) = REFRESH_LOCK.withLock {
        val current = prefs.getString(KEY_ACCESS, null)
        if (current != null && current != used && !expiringSoon()) return@withLock
        val refresh = prefs.getString(KEY_REFRESH, null) ?: throw IOException("Not signed in to Simkl")
        try {
            saveToken(
                Http.postForm(
                    "$BASE/oauth2/token",
                    mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refresh),
                    userAgent(),
                )
            )
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) clearTokens() // revoked, or unused for 6 months: sign in again
            throw e
        }
    }

    private suspend fun authed(call: suspend (Map<String, String>) -> String): String {
        if (expiringSoon()) refresh(prefs.getString(KEY_ACCESS, null))
        val token = prefs.getString(KEY_ACCESS, null) ?: throw IOException("Not signed in to Simkl")
        return try {
            call(userAgent() + ("Authorization" to "Bearer $token"))
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            refresh(token)
            val fresh = prefs.getString(KEY_ACCESS, null) ?: throw e
            call(userAgent() + ("Authorization" to "Bearer $fresh"))
        }
    }

    private suspend fun get(path: String): String = authed { h -> Http.get(url(path), h) }

    /** Every Simkl request carries client_id, app-name and app-version, plus a User-Agent. */
    private fun url(path: String): String = BASE + path + (if ('?' in path) "&" else "?") + params()

    private fun params() = "client_id=${enc(clientId)}&app-name=$APP_NAME&app-version=${enc(BuildConfig.VERSION_NAME)}"

    private fun userAgent() = mapOf("User-Agent" to "HomeBase/${BuildConfig.VERSION_NAME}")

    // ---------- Sync ----------

    enum class Sync {
        /** Use the local library as it is (screensaver, background refreshes). */
        NONE,
        /** On opening the home screen: at most every 20 minutes, or sooner after you've watched something. */
        IF_DUE,
        /** You asked for it (refresh, just signed in). */
        NOW,
    }

    /** Marks the library stale, e.g. after HomeBase reported something you watched. */
    fun markDirty() = prefs.edit().putBoolean(KEY_DIRTY, true).apply()

    /** Brings the local library up to date. Returns true if it changed. */
    suspend fun sync(mode: Sync): Boolean {
        if (mode == Sync.NONE || !isConfigured || !isSignedIn) return false
        return SYNC_LOCK.withLock {
            val now = System.currentTimeMillis()
            val dirty = prefs.getBoolean(KEY_DIRTY, false)
            if (mode == Sync.IF_DUE && !dirty && now - prefs.getLong(KEY_LAST_SYNC, 0L) < THROTTLE_MS) return@withLock false
            prefs.edit().putLong(KEY_LAST_SYNC, now).putBoolean(KEY_DIRTY, false).apply()
            try {
                runSync()
            } catch (e: Exception) {
                if (dirty) markDirty()
                throw e
            }
        }
    }

    private suspend fun runSync(): Boolean {
        val lib = loadLibrary()
        // The snapshot is read before the items it anchors, and saved only if every fetch succeeds.
        val freshText = get("/sync/activities")
        val fresh = parseObject(freshText)
        val saved = lib.snapshot?.let { runCatching { parseObject(it) }.getOrNull() }
        val changes = SimklData.changes(saved, fresh)
        val items: Map<String, SimklItem> = when {
            changes == null -> {
                // First sync: the whole library, one type after another (never in parallel).
                val all = mutableListOf<SimklItem>()
                for (type in SimklData.TYPES) {
                    val extra = if (type == "movies") "" else "?next_watch_info=yes"
                    all += SimklData.parseAllItems(Http.json.parseToJsonElement(get("/sync/all-items/$type$extra")))
                }
                all.associateBy { it.key }
            }
            !changes.any -> lib.items
            else -> {
                var m = lib.items
                val from = saved?.str("all") ?: throw IOException("Simkl snapshot has no timestamp")
                if (changes.items) {
                    val delta = get("/sync/all-items?date_from=${enc(from)}&next_watch_info=yes")
                    m = SimklData.merge(m, SimklData.parseAllItems(Http.json.parseToJsonElement(delta)))
                }
                if (changes.removals) {
                    // Simkl keeps no record of removals: compare with the ids you have now.
                    val keep = SimklData.parseKeys(Http.json.parseToJsonElement(get("/sync/all-items?extended=simkl_ids_only")))
                    m = m.filterKeys { it in keep }
                }
                m
            }
        }
        // Only the lists HomeBase shows are kept; a title that comes back to one returns in a later delta.
        val kept = items.filterValues { it.status in KEPT_STATUSES }
        val changed = kept != lib.items
        saveLibrary(SimklLibrary(kept, freshText))
        return changed
    }

    suspend fun loadLibrary(): SimklLibrary = withContext(Dispatchers.IO) {
        runCatching { Http.json.decodeFromString(SimklLibrary.serializer(), libraryFile.readText()) }.getOrDefault(SimklLibrary())
    }

    private suspend fun saveLibrary(lib: SimklLibrary) = withContext(Dispatchers.IO) {
        val tmp = File(libraryFile.path + ".tmp")
        tmp.writeText(Http.json.encodeToString(SimklLibrary.serializer(), lib))
        if (!tmp.renameTo(libraryFile)) { libraryFile.delete(); tmp.renameTo(libraryFile) }
    }

    // ---------- Home rows (from the local library: no Simkl requests) ----------

    suspend fun upNext(): List<MediaItem> = withArtwork(SimklData.upNext(loadLibrary()))

    suspend fun watchlistMovies(): List<MediaItem> = withArtwork(SimklData.watchlistMovies(loadLibrary()))

    suspend fun upcomingEpisodes(): List<MediaItem> {
        val lib = loadLibrary()
        val followed = SimklData.followed(lib)
        if (followed.isEmpty()) return emptyList()
        val airings = calendar("tv", followed.keys) +
            if (followed.values.any { it.type == "anime" }) calendar("anime", followed.keys) else emptyList()
        return withArtwork(SimklData.upcoming(lib, airings, System.currentTimeMillis()))
    }

    /** Simkl's calendar files are public CDN files that don't count against your quota; refreshed every 6 hours. */
    private suspend fun calendar(kind: String, wanted: Set<Long>): List<SimklAiring> = withContext(Dispatchers.IO) {
        val file = File(appContext.filesDir, "simkl_calendar_$kind.json")
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > CALENDAR_MAX_AGE_MS) {
            try {
                val text = Http.get("https://data.simkl.in/calendar/v2/$kind.json?" + params(), userAgent())
                file.writeText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the older copy.
            }
        }
        if (!file.exists()) return@withContext emptyList()
        runCatching { SimklData.parseCalendar(file.readText(), wanted) }.getOrDefault(emptyList())
    }

    /** TMDB artwork and descriptions when Simkl gives a TMDB id (Simkl asks apps to use TMDB for metadata). */
    private suspend fun withArtwork(items: List<MediaItem>): List<MediaItem> = coroutineScope {
        items.map { m ->
            async {
                val id = m.tmdbId ?: return@async m
                val type = if (m.kind == MediaKind.MOVIE) Tmdb.Type.MOVIE else Tmdb.Type.TV
                m.copy(
                    imageUrl = Tmdb.backdrop(type, id) ?: m.imageUrl,
                    posterUrl = Tmdb.poster(type, id) ?: m.posterUrl,
                    overview = Tmdb.overview(type, id) ?: m.overview,
                )
            }
        }.awaitAll()
    }

    // ---------- Player: identify and scrobble ----------

    /** What Simkl's file-name matcher made of something playing through the TiviMate bridge. */
    data class FileMatch(
        val movie: Boolean,
        val title: String,
        val year: Int?,
        val simkl: Long?,
        val tmdb: Long?,
        val imdb: String?,
        val tvdb: String?,
        val season: Int?,
        val episode: Int?,
        val anime: Boolean,
    )

    /** Built for players: "Stranger.Things.S01E03.1080p.mkv" -> the show and the episode. One call per playback. */
    suspend fun searchFile(fileName: String): FileMatch? {
        val body = buildJsonObject { put("file", fileName) }.toString()
        val root = Http.json.parseToJsonElement(authed { h -> Http.post(url("/search/file"), body, h) }).obj() ?: return null
        val type = root.str("type") ?: return null
        val media = (if (type == "movie") root.obj("movie") else root.obj("show")) ?: return null
        val ids = media.obj("ids") ?: JsonObject(emptyMap())
        val ep = root.obj("episode")
        return FileMatch(
            movie = type == "movie",
            title = media.str("title") ?: return null,
            year = media.str("year")?.toIntOrNull(),
            simkl = (ids["simkl"] as? JsonPrimitive)?.longOrNull ?: ids.str("simkl")?.toLongOrNull(),
            tmdb = (ids.str("tmdb") ?: ids.str("moviedb") ?: ids.str("tmdbtv"))?.toLongOrNull(),
            imdb = ids.str("imdb"),
            tvdb = ids.str("tvdb"),
            season = ep?.str("season")?.toIntOrNull(),
            episode = ep?.str("episode")?.toIntOrNull(),
            anime = listOf("mal", "anidb", "anilist", "kitsu").any { ids.str(it) != null },
        )
    }

    /** start / pause / stop. Stop at 80% or more marks it watched. */
    suspend fun scrobble(action: String, body: JsonObject) {
        authed { h -> Http.post(url("/scrobble/$action"), body.toString(), h) }
        if (action == "stop") markDirty()
    }

    private fun parseObject(text: String): JsonObject =
        Http.json.parseToJsonElement(text).obj() ?: throw IOException("Unexpected reply from Simkl")

    private fun oauthError(body: String): String? = runCatching { parseObject(body).str("error") }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val BASE = "https://api.simkl.com"
        const val APP_NAME = "homebase"
        private const val SCOPE = "media:read media:write"
        private const val THROTTLE_MS = 20 * 60_000L
        private const val CALENDAR_MAX_AGE_MS = 6 * 3_600_000L
        private val KEPT_STATUSES = setOf("watching", "plantowatch", "hold")
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_SCOPE = "scope"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_DIRTY = "dirty"
        private val REFRESH_LOCK = Mutex()
        private val SYNC_LOCK = Mutex()
    }
}
