package com.launcher.tv.data.iptv

import android.content.Context
import com.launcher.tv.data.Http
import com.launcher.tv.data.HttpException
import com.launcher.tv.data.arr
import com.launcher.tv.data.obj
import com.launcher.tv.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Xtream Codes API client (player_api.php). Catalogues are downloaded once a day, streamed
 * straight into lean objects (big providers list tens of thousands of entries) and cached on disk.
 */
class XtreamClient private constructor(context: Context) {

    data class Config(val server: String, val username: String, val password: String)

    private val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "iptv").apply { mkdirs() }

    val config: Config?
        get() {
            val s = prefs.getString("server", null) ?: return null
            val u = prefs.getString("username", null) ?: return null
            val p = prefs.getString("password", null) ?: return null
            return Config(s, u, p)
        }

    val isConfigured: Boolean get() = config != null

    var serverTimezone: String?
        get() = prefs.getString("timezone", null)
        private set(v) { prefs.edit().putString("timezone", v).apply() }

    fun save(cfg: Config) {
        prefs.edit().putString("server", cfg.server).putString("username", cfg.username).putString("password", cfg.password).apply()
        clearCaches()
    }

    fun clear() {
        prefs.edit().clear().apply()
        clearCaches()
    }

    private fun clearCaches() {
        channelCache.clear(); seriesCache.clear(); vodCache.clear(); categoryCache.clear(); episodeCache.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    // ---------------- account ----------------

    suspend fun account(cfg: Config = config ?: error("IPTV not set up")): XtreamAccount {
        val root = Http.getJson(api(cfg, null)).obj() ?: error("Unexpected response from server")
        val user = root.obj("user_info") ?: error("Login failed: check username and password")
        val auth = user.str("auth")
        if (auth != null && auth != "1") error("Login failed: check username and password")
        val tz = root.obj("server_info")?.str("timezone")
        if (cfg == config) serverTimezone = tz
        return XtreamAccount(
            active = user.str("status").equals("Active", ignoreCase = true),
            status = user.str("status") ?: "Unknown",
            expiresMillis = user.str("exp_date")?.toLongOrNull()?.times(1000),
            maxConnections = user.str("max_connections"),
            serverTimezone = tz,
        )
    }

    // ---------------- catalogues ----------------

    private class Cached<T>(val name: String, val serializer: KSerializer<List<T>>, val action: String) {
        val mutex = Mutex()
        @Volatile var items: List<T>? = null
        @Volatile var loadedAt = 0L
        fun clear() { items = null; loadedAt = 0 }
    }

    private val channelCache = Cached("live", ListSerializer(XtreamChannel.serializer()), "get_live_streams")
    private val categoryCache = Cached("live_categories", ListSerializer(XtreamCategory.serializer()), "get_live_categories")
    private val seriesCache = Cached("series", ListSerializer(XtreamSeries.serializer()), "get_series")
    private val vodCache = Cached("vod", ListSerializer(XtreamVod.serializer()), "get_vod_streams")
    private val episodeCache = ConcurrentHashMap<String, List<XtreamEpisode>>()

    suspend fun channels(): List<XtreamChannel> = load(channelCache)
    suspend fun liveCategories(): Map<String, String> = load(categoryCache).associate { it.id to it.name }
    suspend fun series(): List<XtreamSeries> = load(seriesCache)
    suspend fun movies(): List<XtreamVod> = load(vodCache)

    /** Download catalogues in the background so the first "Play" is instant. */
    suspend fun prefetch() {
        if (!isConfigured) return
        for (c in listOf(channelCache, categoryCache, seriesCache, vodCache)) {
            try { load(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> load(c: Cached<T>): List<T> = c.mutex.withLock {
        val fresh = System.currentTimeMillis() - c.loadedAt < MAX_AGE
        c.items?.takeIf { fresh }?.let { return it }
        val cfg = config ?: error("IPTV not set up")
        val file = File(dir, "${c.name}.json")
        withContext(Dispatchers.IO) {
            // Disk cache first.
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE) {
                runCatching { file.inputStream().buffered().use { XJson.decodeFromStream(c.serializer, it) } }
                    .getOrNull()?.let { items ->
                        c.items = items; c.loadedAt = file.lastModified()
                        return@withContext items
                    }
            }
            val items = Http.client.newCall(Request.Builder().url(api(cfg, c.action)).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, "")
                val body = resp.body ?: error("Empty response")
                XJson.decodeFromStream(c.serializer, body.byteStream())
            }
            runCatching { file.outputStream().buffered().use { XJson.encodeToStream(c.serializer, items, it) } }
            c.items = items
            c.loadedAt = System.currentTimeMillis()
            items
        }
    }

    suspend fun seriesEpisodes(seriesId: String): List<XtreamEpisode> {
        episodeCache[seriesId]?.let { return it }
        val cfg = config ?: error("IPTV not set up")
        val root = Http.getJson(api(cfg, "get_series_info") + "&series_id=$seriesId").obj() ?: return emptyList()
        val episodes = parseEpisodes(root)
        episodeCache[seriesId] = episodes
        return episodes
    }

    /** Upcoming programmes on a channel (titles come base64-encoded). */
    suspend fun shortEpg(streamId: String, limit: Int = 6): List<EpgEntry> {
        val cfg = config ?: return emptyList()
        val root = Http.getJson(api(cfg, "get_short_epg") + "&stream_id=$streamId&limit=$limit").obj() ?: return emptyList()
        return parseEpg(root)
    }

    // ---------------- stream URLs ----------------

    fun liveUrl(streamId: String): String = config!!.let { "${it.server}/live/${enc(it.username)}/${enc(it.password)}/$streamId.ts" }

    fun movieUrl(v: XtreamVod): String = config!!.let { "${it.server}/movie/${enc(it.username)}/${enc(it.password)}/${v.streamId}.${v.extension ?: "mp4"}" }

    fun episodeUrl(e: XtreamEpisode): String = config!!.let { "${it.server}/series/${enc(it.username)}/${enc(it.password)}/${e.id}.${e.extension}" }

    /** Catch-up: play a channel's archive from [startMillis]. Times are in the server's time zone. */
    fun timeshiftUrl(streamId: String, startMillis: Long, durationMinutes: Int): String = config!!.let {
        val zone = runCatching { ZoneId.of(serverTimezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
        val start = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").format(Instant.ofEpochMilli(startMillis).atZone(zone))
        "${it.server}/timeshift/${enc(it.username)}/${enc(it.password)}/$durationMinutes/$start/$streamId.ts"
    }

    private fun api(cfg: Config, action: String?): String =
        "${cfg.server}/player_api.php?username=${enc(cfg.username)}&password=${enc(cfg.password)}" +
            (action?.let { "&action=$it" } ?: "")

    companion object {
        private const val MAX_AGE = 24 * 3600_000L

        @Volatile private var instance: XtreamClient? = null
        fun get(context: Context): XtreamClient =
            instance ?: synchronized(this) { instance ?: XtreamClient(context.applicationContext).also { instance = it } }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /**
         * Accepts "host:port", "http://host:port/", or a full M3U link
         * ("http://host:port/get.php?username=U&password=P&type=m3u_plus"), which also supplies the login.
         */
        fun normalize(serverInput: String, username: String, password: String): Config {
            var s = serverInput.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: throw IllegalArgumentException("That server address doesn't look right")
            val user = username.trim().ifBlank { url.queryParameter("username").orEmpty() }
            val pass = password.trim().ifBlank { url.queryParameter("password").orEmpty() }
            require(user.isNotBlank() && pass.isNotBlank()) { "Enter your username and password" }
            val portPart = if ((url.scheme == "http" && url.port == 80) || (url.scheme == "https" && url.port == 443)) "" else ":${url.port}"
            return Config("${url.scheme}://${url.host}$portPart", user, pass)
        }

        internal fun parseEpisodes(root: JsonObject): List<XtreamEpisode> {
            val episodes = root["episodes"]
            // Usually {"1": [...], "2": [...]}, occasionally a flat array.
            val lists = when (episodes) {
                is JsonObject -> episodes.values.mapNotNull { it.arr() }
                is kotlinx.serialization.json.JsonArray -> listOf(episodes)
                else -> emptyList()
            }
            return lists.flatten().mapNotNull { el ->
                val o = el.obj() ?: return@mapNotNull null
                XtreamEpisode(
                    id = o.str("id") ?: return@mapNotNull null,
                    season = o.str("season")?.toIntOrNull() ?: return@mapNotNull null,
                    episode = o.str("episode_num")?.toIntOrNull() ?: return@mapNotNull null,
                    title = o.str("title").orEmpty(),
                    extension = o.str("container_extension") ?: "mp4",
                )
            }
        }

        internal fun parseEpg(root: JsonObject): List<EpgEntry> =
            root.arr("epg_listings").orEmpty().mapNotNull { el ->
                val o = el.obj() ?: return@mapNotNull null
                val start = o.str("start_timestamp")?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
                val end = o.str("stop_timestamp")?.toLongOrNull()?.times(1000) ?: (start + 3600_000L)
                EpgEntry(b64(o.str("title")), b64(o.str("description")), start, end)
            }

        private fun b64(s: String?): String =
            s?.let { runCatching { String(Base64.getDecoder().decode(it.trim()), Charsets.UTF_8) }.getOrDefault(it) }.orEmpty()
    }
}
