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
 * Xtream Codes provider (player_api.php). Catalogues are downloaded once a day, streamed
 * straight into lean objects (big providers list tens of thousands of entries) and cached on disk.
 */
class XtreamClient(context: Context, override val config: SourceConfig.Xtream) : IptvSource {

    data class Config(val server: String, val username: String, val password: String)

    private val cfg = Config(config.server, config.username, config.password)
    private val prefs = context.getSharedPreferences("iptv_${config.id}", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "iptv/${config.id}").apply { mkdirs() }

    var serverTimezone: String?
        get() = prefs.getString("timezone", null)
        private set(v) { prefs.edit().putString("timezone", v).apply() }

    // ---------------- account ----------------

    suspend fun account(): XtreamAccount = account(cfg).also { serverTimezone = it.serverTimezone }

    override suspend fun status(): String {
        val a = account()
        val exp = a.expiresMillis?.let { " · expires " + DateTimeFormatter.ofPattern("MMM d, yyyy").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }.orEmpty()
        return "${a.status}$exp"
    }

    // ---------------- catalogues ----------------

    private class Cached<T>(val name: String, val serializer: KSerializer<List<T>>, val action: String) {
        val mutex = Mutex()
        @Volatile var items: List<T>? = null
        @Volatile var loadedAt = 0L
    }

    private val channelCache = Cached("live", ListSerializer(XtreamChannel.serializer()), "get_live_streams")
    private val categoryCache = Cached("live_categories", ListSerializer(XtreamCategory.serializer()), "get_live_categories")
    private val seriesCache = Cached("series", ListSerializer(XtreamSeries.serializer()), "get_series")
    private val vodCache = Cached("vod", ListSerializer(XtreamVod.serializer()), "get_vod_streams")
    private val episodeCache = ConcurrentHashMap<String, List<XtreamEpisode>>()
    @Volatile private var mapped: Pair<Long, List<IptvChannel>>? = null

    override suspend fun channels(): List<IptvChannel> {
        val raw = load(channelCache)
        mapped?.takeIf { it.first == channelCache.loadedAt }?.let { return it.second }
        val cats = runCatching { load(categoryCache) }.getOrDefault(emptyList()).associate { it.id to it.name }
        val list = raw.map {
            IptvChannel(
                config.id, it.streamId, it.name, cats[it.categoryId], it.epgChannelId?.ifBlank { null }, it.icon?.ifBlank { null },
                it.hasArchive, number = it.num?.ifBlank { null },
            )
        }
        mapped = channelCache.loadedAt to list
        return list
    }

    suspend fun series(): List<XtreamSeries> = load(seriesCache)
    suspend fun movies(): List<XtreamVod> = load(vodCache)

    override suspend fun prefetch() {
        for (c in listOf(channelCache, categoryCache, seriesCache, vodCache)) {
            try { load(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> load(c: Cached<T>): List<T> = c.mutex.withLock {
        val fresh = System.currentTimeMillis() - c.loadedAt < MAX_AGE
        c.items?.takeIf { fresh }?.let { return it }
        val file = File(dir, "${c.name}.json")
        withContext(Dispatchers.IO) {
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
        val root = Http.getJson(api(cfg, "get_series_info") + "&series_id=$seriesId").obj() ?: return emptyList()
        val episodes = parseEpisodes(root)
        episodeCache[seriesId] = episodes
        return episodes
    }

    override suspend fun guide(channel: IptvChannel): List<EpgEntry> {
        val root = Http.getJson(api(cfg, "get_short_epg") + "&stream_id=${channel.id}&limit=6").obj() ?: return emptyList()
        return parseEpg(root)
    }

    // ---------------- playback ----------------

    override fun liveUrl(channel: IptvChannel): String = "${cfg.server}/live/${enc(cfg.username)}/${enc(cfg.password)}/${channel.id}.ts"

    fun movieUrl(v: XtreamVod): String = "${cfg.server}/movie/${enc(cfg.username)}/${enc(cfg.password)}/${v.streamId}.${v.extension ?: "mp4"}"

    fun episodeUrl(e: XtreamEpisode): String = "${cfg.server}/series/${enc(cfg.username)}/${enc(cfg.password)}/${e.id}.${e.extension}"

    /** Times are in the server's time zone. */
    override fun catchupUrl(channel: IptvChannel, startMillis: Long, durationMinutes: Int): String? {
        if (!channel.archive) return null
        val zone = runCatching { ZoneId.of(serverTimezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
        val start = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").format(Instant.ofEpochMilli(startMillis).atZone(zone))
        return "${cfg.server}/timeshift/${enc(cfg.username)}/${enc(cfg.password)}/$durationMinutes/$start/${channel.id}.ts"
    }

    override suspend fun findEpisode(title: String, year: Int?, tmdbId: Long?, season: Int, episode: Int): Playable? {
        val all = series()
        val candidates = withContext(Dispatchers.Default) {
            CatalogMatcher.rank(all, title, year, tmdbId, { it.name }, { it.tmdbAny }, { it.yearAny })
        }
        for (s in candidates.take(4)) {
            val ep = seriesEpisodes(s.seriesId).firstOrNull { it.season == season && it.episode == episode } ?: continue
            return Playable(episodeUrl(ep), "%s S%02dE%02d".format(title, season, episode))
        }
        return null
    }

    override suspend fun findMovie(title: String, year: Int?, tmdbId: Long?): Playable? {
        val all = movies()
        val best = withContext(Dispatchers.Default) {
            CatalogMatcher.rank(all, title, year, tmdbId, { it.name }, { it.tmdbAny }, { Names.yearOf(it.name) ?: it.year?.toIntOrNull() }).firstOrNull()
        } ?: return null
        return Playable(movieUrl(best), year?.let { "$title ($it)" } ?: title)
    }

    companion object {
        private const val MAX_AGE = 24 * 3600_000L

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun api(cfg: Config, action: String?): String =
            "${cfg.server}/player_api.php?username=${enc(cfg.username)}&password=${enc(cfg.password)}" +
                (action?.let { "&action=$it" } ?: "")

        /** Login check without saving anything. */
        suspend fun account(cfg: Config): XtreamAccount {
            val root = Http.getJson(api(cfg, null)).obj() ?: error("Unexpected response from server")
            val user = root.obj("user_info") ?: error("Login failed: check username and password")
            val auth = user.str("auth")
            if (auth != null && auth != "1") error("Login failed: check username and password")
            return XtreamAccount(
                active = user.str("status").equals("Active", ignoreCase = true),
                status = user.str("status") ?: "Unknown",
                expiresMillis = user.str("exp_date")?.toLongOrNull()?.times(1000),
                maxConnections = user.str("max_connections"),
                serverTimezone = root.obj("server_info")?.str("timezone"),
            )
        }

        /** "get.php?username=..&password=.." links are Xtream logins in disguise. */
        fun isXtreamM3uLink(url: String): Boolean {
            val u = url.trim().let { if (it.contains("://")) it else "http://$it" }.toHttpUrlOrNull() ?: return false
            return u.encodedPath.endsWith("get.php") && !u.queryParameter("username").isNullOrBlank() && !u.queryParameter("password").isNullOrBlank()
        }

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
