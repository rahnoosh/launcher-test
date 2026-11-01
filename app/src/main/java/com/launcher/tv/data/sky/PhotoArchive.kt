package com.launcher.tv.data.sky

import android.content.Context
import com.launcher.tv.data.Http
import com.launcher.tv.data.arr
import com.launcher.tv.data.obj
import com.launcher.tv.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.time.LocalDate

/**
 * The screensaver's photographs: Bing's photos of the last couple of weeks (no key needed),
 * downloaded once a day and kept on the box, each with its place and photographer.
 */
object PhotoArchive {
    data class Photo(val file: File, val place: String, val credit: String?)

    private const val MAX = 15

    private fun api(idx: Int) = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=en-US"

    /** What's on the box, refreshed from Bing once a day (old photos are deleted). */
    suspend fun photos(context: Context): List<Photo> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val index = File(dir, "index.txt")
        val lines = if (index.exists()) index.readLines() else emptyList()
        var entries = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf('|')
            if (i <= 0) null else File(dir, l.substring(0, i)).takeIf { it.exists() }?.let { it to l.substring(i + 1) }
        }
        if (lines.firstOrNull() != LocalDate.now().toString()) {
            try {
                // Bing serves at most 8 per call and goes back about 15 days.
                val list = (parseAll(Http.getJson(api(0))) + parseAll(Http.getJson(api(7)))).distinctBy { it.first }.take(MAX)
                val kept = mutableListOf<Pair<File, String>>()
                for ((url, copyright) in list) {
                    val f = File(dir, "p" + Integer.toHexString(url.hashCode()) + ".jpg")
                    if (!f.exists() || f.length() == 0L) {
                        val bytes = runCatching { Http.getBytes(url) }.getOrNull() ?: continue
                        if (bytes.size < 10_000) continue
                        f.writeBytes(bytes)
                    }
                    kept += f to copyright.replace('\n', ' ').replace('|', '/')
                }
                if (kept.isNotEmpty()) {
                    index.writeText((listOf(LocalDate.now().toString()) + kept.map { "${it.first.name}|${it.second}" }).joinToString("\n"))
                    dir.listFiles()?.filter { f -> f.name.endsWith(".jpg") && kept.none { it.first.name == f.name } }?.forEach { it.delete() }
                    entries = kept
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: keep what's here.
            }
        }
        entries.map { (f, c) -> splitCredit(c).let { (place, credit) -> Photo(f, place, credit) } }
    }

    /** Every image in a Bing archive response: its 1080p URL and copyright line. */
    fun parseAll(root: JsonElement?): List<Pair<String, String>> =
        root.obj()?.arr("images").orEmpty().mapNotNull { el ->
            val img = el.obj() ?: return@mapNotNull null
            val url = img.str("urlbase")?.let { "https://www.bing.com${it}_1920x1080.jpg" }
                ?: img.str("url")?.let { "https://www.bing.com$it" }
                ?: return@mapNotNull null
            url to (img.str("copyright") ?: img.str("title")).orEmpty()
        }

    /** "Larch trees in the Dolomites, Italy (© Jane Doe/Getty Images)" -> the place, and "© Jane Doe/Getty Images". */
    fun splitCredit(copyright: String): Pair<String, String?> {
        val i = copyright.lastIndexOf("(©")
        if (i < 0) return copyright.trim() to null
        return copyright.substring(0, i).trim() to copyright.substring(i + 1).trimEnd(')', ' ').trim()
    }
}
