package com.launcher.tv.data.iptv

import com.launcher.tv.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.BufferedSource
import java.util.concurrent.TimeUnit

/** How one stream behaved when we tried it. */
data class ProbeResult(
    val ok: Boolean,
    /** Time until the first video bytes arrived. */
    val startMs: Long? = null,
    /** Measured delivery rate. */
    val kbps: Int? = null,
    /** "1080p" from the HLS playlist, or guessed from the channel name. */
    val resolution: Int? = null,
    val error: String? = null,
)

/** Opens a stream for a few seconds and measures it. Used to compare providers for an event. */
object StreamProbe {
    private val client by lazy {
        Http.client.newBuilder().readTimeout(6, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    suspend fun probe(url: String, headers: Map<String, String>, budgetMs: Long = 4_000, maxBytes: Long = 3_000_000): ProbeResult =
        withContext(Dispatchers.IO) {
            val t0 = System.nanoTime()
            try {
                get(url, headers) { source, contentType ->
                    val first = elapsed(t0)
                    if (isPlaylist(source, contentType)) {
                        val text = source.readUtf8().take(400_000)
                        probeHls(url, text, headers, t0, budgetMs, maxBytes)
                    } else {
                        val (bytes, ms) = read(source, budgetMs, maxBytes)
                        if (bytes < 8_000) ProbeResult(false, first, error = "No video data")
                        else ProbeResult(true, first, kbps(bytes, ms))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProbeResult(false, error = e.message ?: e.javaClass.simpleName)
            }
        }

    private fun probeHls(url: String, text: String, headers: Map<String, String>, t0: Long, budgetMs: Long, maxBytes: Long): ProbeResult {
        var playlist = Hls.parse(text, url)
        var resolution: Int? = null
        if (playlist.variants.isNotEmpty()) {
            val best = playlist.variants.maxByOrNull { it.bandwidth } ?: return ProbeResult(false, error = "Empty playlist")
            resolution = best.height
            playlist = get(best.uri, headers) { s, _ -> Hls.parse(s.readUtf8().take(400_000), best.uri) }
        }
        // Near the live edge, but not the very last (possibly still being written) segment.
        val segment = playlist.segments.getOrNull(playlist.segments.size - 2) ?: playlist.segments.lastOrNull()
            ?: return ProbeResult(false, error = "No segments")
        return get(segment, headers) { s, _ ->
            val first = elapsed(t0)
            val (bytes, ms) = read(s, budgetMs, maxBytes)
            if (bytes < 8_000) ProbeResult(false, first, error = "No video data")
            else ProbeResult(true, first, kbps(bytes, ms), resolution)
        }
    }

    private fun <T> get(url: String, headers: Map<String, String>, use: (BufferedSource, String) -> T): T {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty response")
            use(body.source(), resp.header("Content-Type").orEmpty())
        }
    }

    private fun isPlaylist(source: BufferedSource, contentType: String): Boolean {
        if (contentType.contains("mpegurl", ignoreCase = true)) return true
        return runCatching { source.request(7) && source.peek().readUtf8(7) == "#EXTM3U" }.getOrDefault(false)
    }

    /** Reads for up to [budgetMs] or [maxBytes]; returns (bytes, milliseconds). */
    private fun read(source: BufferedSource, budgetMs: Long, maxBytes: Long): Pair<Long, Long> {
        val start = System.nanoTime()
        val buf = okio.Buffer()
        var total = 0L
        while (total < maxBytes && elapsed(start) < budgetMs) {
            val n = source.read(buf, 64 * 1024)
            if (n < 0) break
            total += n
            buf.clear()
        }
        return total to elapsed(start).coerceAtLeast(1)
    }

    private fun elapsed(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    private fun kbps(bytes: Long, ms: Long) = (bytes * 8 / ms).toInt()

    /** Resolution from the channel name when the stream doesn't say ("FHD" -> 1080). */
    fun guessResolution(name: String): Int? {
        val t = Names.tokens(name)
        return when {
            t.any { it == "4K" || it == "UHD" || it == "2160P" } -> 2160
            t.any { it == "FHD" || it == "1080P" || it == "1080" } -> 1080
            t.any { it == "HD" || it == "720P" || it == "720" } -> 720
            t.any { it == "SD" || it == "480P" } -> 480
            else -> null
        }
    }

    /** 0-100: how good this stream is for watching the event. */
    fun rating(r: ProbeResult, c: ChannelCandidate): Int {
        if (!r.ok) return 0
        val start = r.startMs ?: 9_999
        val speed = r.kbps ?: 0
        val res = r.resolution ?: guessResolution(c.channel.name) ?: 720
        var score = 40
        score += when { start < 800 -> 20; start < 2_000 -> 12; start < 4_000 -> 5; else -> 0 }
        score += when { speed >= 8_000 -> 20; speed >= 4_000 -> 15; speed >= 2_000 -> 8; else -> 0 }
        score += when { res >= 2160 -> 12; res >= 1080 -> 10; res >= 720 -> 5; else -> 0 }
        if (c.epgTitle != null && c.reason.contains("guide")) score += 6
        if (c.channel.archive) score += 2
        return score.coerceAtMost(100)
    }
}

/** Just enough HLS parsing to find the best variant and a recent segment. */
object Hls {
    data class Variant(val bandwidth: Long, val height: Int?, val uri: String)
    data class Playlist(val variants: List<Variant>, val segments: List<String>)

    fun parse(text: String, baseUrl: String): Playlist {
        val variants = mutableListOf<Variant>()
        val segments = mutableListOf<String>()
        var pending: Pair<Long, Int?>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val h = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    pending = bw to h
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val uri = resolve(baseUrl, line)
                    val p = pending
                    if (p != null) { variants += Variant(p.first, p.second, uri); pending = null } else segments += uri
                }
            }
        }
        return Playlist(variants, segments)
    }

    fun resolve(base: String, ref: String): String = when {
        ref.startsWith("http://") || ref.startsWith("https://") -> ref
        ref.startsWith("/") -> Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1).orEmpty() + ref
        else -> base.substringBefore('?').substringBeforeLast('/') + "/" + ref
    }
}
