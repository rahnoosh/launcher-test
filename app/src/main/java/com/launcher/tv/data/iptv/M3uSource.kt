package com.launcher.tv.data.iptv

import android.content.Context
import com.launcher.tv.data.Http
import com.launcher.tv.data.HttpException
import com.launcher.tv.player.TitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Provider given as an M3U playlist URL (plus optional XMLTV guide). Live channels feed the channel
 * finder; movie/episode entries are matched to Trakt by title (+ season/episode or year).
 */
class M3uSource(context: Context, override val config: SourceConfig.M3u) : IptvSource {
    private val dir = File(context.filesDir, "iptv/${config.id}").apply { mkdirs() }
    private val playlistLock = Mutex()
    private val epgLock = Mutex()

    @Serializable
    private data class PlaylistCache(val epgUrl: String? = null, val channels: List<IptvChannel>, val vod: List<M3uVod>)

    @Serializable
    private data class EpgCache(val programmes: Map<String, List<EpgEntry>>, val names: Map<String, String>)

    @Volatile private var playlist: PlaylistCache? = null
    @Volatile private var playlistAt = 0L
    @Volatile private var epg: EpgCache? = null
    @Volatile private var epgAt = 0L
    @Volatile private var episodeIndex: Map<String, List<Pair<TitleParser.Parsed, M3uVod>>>? = null
    @Volatile private var movieIndex: Map<String, List<Pair<TitleParser.Parsed, M3uVod>>>? = null

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun playlist(): PlaylistCache = playlistLock.withLock {
        playlist?.takeIf { System.currentTimeMillis() - playlistAt < PLAYLIST_AGE }?.let { return it }
        val file = File(dir, "playlist.json")
        withContext(Dispatchers.IO) {
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < PLAYLIST_AGE) {
                runCatching { file.inputStream().buffered().use { XJson.decodeFromStream(PlaylistCache.serializer(), it) } }.getOrNull()?.let {
                    playlist = it; playlistAt = file.lastModified(); episodeIndex = null; movieIndex = null
                    return@withContext it
                }
            }
            val parsed = download(config.url) { stream ->
                M3uParser.parse(stream.bufferedReader().lineSequence(), config.id)
            }
            if (parsed.channels.isEmpty() && parsed.vod.isEmpty()) error("That playlist is empty or isn't an M3U file")
            val cache = PlaylistCache(parsed.epgUrl, parsed.channels, parsed.vod)
            runCatching { file.outputStream().buffered().use { XJson.encodeToStream(PlaylistCache.serializer(), cache, it) } }
            playlist = cache; playlistAt = System.currentTimeMillis(); episodeIndex = null; movieIndex = null
            cache
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun epg(): EpgCache? = epgLock.withLock {
        epg?.takeIf { System.currentTimeMillis() - epgAt < EPG_AGE }?.let { return it }
        val url = config.epgUrl?.ifBlank { null } ?: playlist().epgUrl ?: return null
        val file = File(dir, "epg.json")
        withContext(Dispatchers.IO) {
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < EPG_AGE) {
                runCatching { file.inputStream().buffered().use { XJson.decodeFromStream(EpgCache.serializer(), it) } }.getOrNull()?.let {
                    epg = it; epgAt = file.lastModified()
                    return@withContext it
                }
            }
            val now = System.currentTimeMillis()
            val r = runCatching {
                download(url) { XmltvParser.parse(it.bufferedReader(), now - 3 * 3600_000L, now + 36 * 3600_000L) }
            }.getOrNull() ?: return@withContext null
            val cache = EpgCache(r.programmes, r.names)
            runCatching { file.outputStream().buffered().use { XJson.encodeToStream(EpgCache.serializer(), cache, it) } }
            epg = cache; epgAt = now
            cache
        }
    }

    private fun <T> download(url: String, read: (InputStream) -> T): T =
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "")
            val body = resp.body ?: error("Empty response")
            read(maybeGunzip(body.byteStream()))
        }

    override suspend fun channels(): List<IptvChannel> = playlist().channels

    override suspend fun guide(channel: IptvChannel): List<EpgEntry> {
        val e = epg() ?: return emptyList()
        channel.epgId?.let { id -> e.programmes[id]?.let { return it } }
        val byName = e.names[Names.tokens(channel.name).joinToString(" ")] ?: return emptyList()
        return e.programmes[byName].orEmpty()
    }

    override fun liveUrl(channel: IptvChannel): String = channel.url ?: error("Channel has no URL")

    override fun catchupUrl(channel: IptvChannel, startMillis: Long, durationMinutes: Int): String? =
        Catchup.url(channel, startMillis, durationMinutes)

    override suspend fun findEpisode(title: String, year: Int?, tmdbId: Long?, season: Int, episode: Int): Playable? {
        val index = episodeIndex ?: withContext(Dispatchers.Default) { buildIndex(episodes = true) }.also { episodeIndex = it }
        val hits = index[Names.titleKey(title)].orEmpty().filter { (p, _) -> p.season == season && p.episode == episode }
        val best = hits.sortedByDescending { (p, v) -> score(p, v, year) }.firstOrNull()?.second ?: return null
        return Playable(best.url, "%s S%02dE%02d".format(title, season, episode), best.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap())
    }

    override suspend fun findMovie(title: String, year: Int?, tmdbId: Long?): Playable? {
        val index = movieIndex ?: withContext(Dispatchers.Default) { buildIndex(episodes = false) }.also { movieIndex = it }
        val hits = index[Names.titleKey(title)].orEmpty()
            .filter { (p, _) -> year == null || p.year == null || kotlin.math.abs(p.year - year) <= 1 }
        val best = hits.sortedByDescending { (p, v) -> score(p, v, year) }.firstOrNull()?.second ?: return null
        return Playable(best.url, year?.let { "$title ($it)" } ?: title, best.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap())
    }

    private fun score(p: TitleParser.Parsed, v: M3uVod, year: Int?): Int =
        (if (year != null && p.year == year) 10 else 0) +
            (if (Regex("""^\W*(EN|ENG|US|UK)\b""", RegexOption.IGNORE_CASE).containsMatchIn(v.name)) 3 else 0)

    private suspend fun buildIndex(episodes: Boolean): Map<String, List<Pair<TitleParser.Parsed, M3uVod>>> =
        playlist().vod.mapNotNull { v ->
            val p = TitleParser.parse(v.name, v.url)
            val isEpisode = p.kind == TitleParser.Kind.EPISODE && p.season != null && p.episode != null
            if (isEpisode != episodes || p.name.isBlank()) null else Names.titleKey(p.name) to (p to v)
        }.groupBy({ it.first }, { it.second })

    override suspend fun prefetch() {
        playlist()
        runCatching { epg() }
    }

    override suspend fun status(): String {
        val p = playlist()
        val guide = if (epg() != null) " · TV guide" else ""
        return "%,d channels · %,d movies & episodes%s".format(p.channels.size, p.vod.size, guide)
    }

    companion object {
        private const val PLAYLIST_AGE = 24 * 3600_000L
        private const val EPG_AGE = 12 * 3600_000L

        /** XMLTV files are often served gzipped without a Content-Encoding header. */
        fun maybeGunzip(input: InputStream): InputStream {
            val b = BufferedInputStream(input)
            b.mark(2)
            val first = b.read()
            val second = b.read()
            b.reset()
            return if (first == 0x1f && second == 0x8b) GZIPInputStream(b) else b
        }
    }
}
