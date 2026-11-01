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
 * The home screen's photograph: Bing's photo of the day (no key needed), downloaded once a day and
 * kept on the box. Each photo belongs to its photographer, credited on screen.
 */
object DailyPhoto {
    data class Photo(val file: File, val credit: String)

    private const val API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US"

    /** Today's photo, downloading it if the one on the box is from another day. Falls back to the last one. */
    suspend fun today(context: Context): Photo? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "photo").apply { mkdirs() }
        val image = File(dir, "today.jpg")
        val meta = File(dir, "today.txt")
        val saved = if (image.exists() && meta.exists()) meta.readLines() else emptyList()
        val savedDay = saved.getOrNull(0)
        val savedCredit = saved.getOrNull(1).orEmpty()
        if (savedDay == LocalDate.now().toString() && image.length() > 0) return@withContext Photo(image, savedCredit)
        try {
            val (url, credit) = parse(Http.getJson(API)) ?: return@withContext existing(image, savedCredit)
            val bytes = Http.getBytes(url)
            if (bytes.size < 10_000) return@withContext existing(image, savedCredit)
            val tmp = File(dir, "today.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(image)) { image.delete(); tmp.renameTo(image) }
            meta.writeText(LocalDate.now().toString() + "\n" + credit)
            Photo(image, credit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            existing(image, savedCredit)
        }
    }

    private fun existing(image: File, credit: String) = if (image.exists() && image.length() > 0) Photo(image, credit) else null

    /** The 1080p image URL and its credit line, from Bing's archive JSON. */
    fun parse(root: JsonElement?): Pair<String, String>? {
        val img = root.obj()?.arr("images")?.firstOrNull().obj() ?: return null
        val base = img.str("urlbase")
        val url = when {
            base != null -> "https://www.bing.com${base}_1920x1080.jpg"
            img.str("url") != null -> "https://www.bing.com" + img.str("url")
            else -> return null
        }
        return url to (img.str("copyright") ?: img.str("title")).orEmpty()
    }
}
