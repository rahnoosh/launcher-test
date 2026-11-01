package com.launcher.tv.data.iptv

import android.content.Context
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID

/** All configured providers (Xtream and M3U), searched together. */
class IptvLibrary private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("iptv_sources", Context.MODE_PRIVATE)
    @Volatile private var built: List<IptvSource>? = null

    fun configs(): List<SourceConfig> {
        val json = prefs.getString("list", null) ?: return migrateLegacy()
        return runCatching { XJson.decodeFromString(ListSerializer(SourceConfig.serializer()), json) }.getOrDefault(emptyList())
    }

    val sources: List<IptvSource>
        get() = built ?: configs().map { create(context, it) }.also { built = it }

    val isConfigured: Boolean get() = configs().isNotEmpty()

    fun add(config: SourceConfig) = save(configs().filter { it.id != config.id } + config)

    fun remove(id: String) {
        save(configs().filter { it.id != id })
        File(context.filesDir, "iptv/$id").deleteRecursively()
    }

    private fun save(list: List<SourceConfig>) {
        prefs.edit().putString("list", XJson.encodeToString(ListSerializer(SourceConfig.serializer()), list)).apply()
        built = null
        com.launcher.tv.data.ChangeBus.emit(com.launcher.tv.data.ChangeBus.PROVIDERS)
    }

    /** Restore from a backup. */
    fun replaceAll(list: List<SourceConfig>) = save(list)

    /** Movies/episodes whose title contains [query], across providers (phone page search). */
    suspend fun searchVod(query: String, limit: Int = 12): List<Pair<String, Playable>> =
        sources.flatMap { s -> safe { s.searchVod(query, limit) }.orEmpty() }.take(limit)

    /** First version stored a single Xtream login under "iptv". */
    private fun migrateLegacy(): List<SourceConfig> {
        val old = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        val s = old.getString("server", null) ?: return emptyList()
        val u = old.getString("username", null) ?: return emptyList()
        val p = old.getString("password", null) ?: return emptyList()
        val list = listOf(SourceConfig.Xtream(newId(), hostOf(s), s, u, p))
        save(list)
        old.edit().clear().apply()
        return list
    }

    fun source(id: String): IptvSource? = sources.firstOrNull { it.config.id == id }

    suspend fun channels(): List<IptvChannel> = coroutineScope {
        sources.map { s -> async { safe { s.channels() }.orEmpty() } }.awaitAll().flatten()
    }

    suspend fun guide(ch: IptvChannel): List<EpgEntry> = source(ch.sourceId)?.let { safe { it.guide(ch) } }.orEmpty()

    fun liveUrl(ch: IptvChannel): String? = source(ch.sourceId)?.liveUrl(ch)

    fun catchupUrl(ch: IptvChannel, startMillis: Long, durationMinutes: Int): String? =
        source(ch.sourceId)?.catchupUrl(ch, startMillis, durationMinutes)

    fun headers(ch: IptvChannel): Map<String, String> = source(ch.sourceId)?.headers(ch).orEmpty()

    suspend fun find(item: MediaItem): Playable? {
        for (s in sources) {
            val hit = safe {
                if (item.kind == MediaKind.MOVIE) s.findMovie(item.title, item.year, item.tmdbId)
                else s.findEpisode(item.title, item.year, item.tmdbId, item.season ?: return@safe null, item.episode ?: return@safe null)
            }
            if (hit != null) return hit
        }
        return null
    }

    suspend fun prefetch() = sources.forEach { safe { it.prefetch() } }

    private suspend fun <T> safe(block: suspend () -> T?): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    companion object {
        @Volatile private var instance: IptvLibrary? = null
        fun get(context: Context): IptvLibrary =
            instance ?: synchronized(this) { instance ?: IptvLibrary(context.applicationContext).also { instance = it } }

        fun newId(): String = UUID.randomUUID().toString().take(8)

        fun hostOf(url: String): String =
            (if (url.contains("://")) url else "http://$url").toHttpUrlOrNull()?.host ?: url.take(30)

        fun create(context: Context, c: SourceConfig): IptvSource = when (c) {
            is SourceConfig.Xtream -> XtreamClient(context, c)
            is SourceConfig.M3u -> M3uSource(context, c)
        }
    }
}
