package com.launcher.tv.data.iptv

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Builds catch-up ("start over") URLs for M3U channels. */
object Catchup {
    private val xtreamLive = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.(ts|m3u8))?$""")

    fun url(ch: IptvChannel, startMillis: Long, durationMinutes: Int, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneOffset.UTC): String? {
        val url = ch.url ?: return null
        val endMillis = startMillis + durationMinutes * 60_000L
        val template = ch.catchupSource
        val mode = ch.catchup?.lowercase()

        // Xtream-style stream URLs: the timeshift path works whatever the playlist says.
        val xt = xtreamLive.find(url)
        if (xt != null && (mode == null || mode == "xc" || mode == "default" || template == null)) {
            val (base, user, pass, id) = xt.destructured
            val start = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").format(Instant.ofEpochMilli(startMillis).atZone(zone))
            return "$base/timeshift/$user/$pass/$durationMinutes/$start/$id.ts"
        }
        if (!ch.archive) return null
        return when {
            template != null && (mode == "append" || (mode != "default" && !template.startsWith("http"))) -> url + fill(template, startMillis, endMillis, now)
            template != null -> fill(template, startMillis, endMillis, now)
            mode == "shift" || mode == "timeshift" || mode == null ->
                url + (if ('?' in url) "&" else "?") + "utc=${startMillis / 1000}&lutc=${now / 1000}"
            else -> null
        }
    }

    /** Fills the usual catch-up placeholders ({utc}, ${start}, {duration}, {Y}{m}{d}{H}{M}{S}...). */
    fun fill(t: String, start: Long, end: Long, now: Long): String {
        val s = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC)
        val dur = (end - start) / 1000
        return t
            .replace("{utc}", "${start / 1000}").replace("\${start}", "${start / 1000}").replace("{start}", "${start / 1000}")
            .replace("{utcend}", "${end / 1000}").replace("\${end}", "${end / 1000}").replace("{end}", "${end / 1000}")
            .replace("{lutc}", "${now / 1000}").replace("\${now}", "${now / 1000}").replace("{now}", "${now / 1000}")
            .replace("\${timestamp}", "${now / 1000}").replace("{timestamp}", "${now / 1000}")
            .replace("{duration}", "$dur").replace("\${duration}", "$dur")
            .replace("{offset}", "${(now - start) / 1000}").replace("\${offset}", "${(now - start) / 1000}")
            .replace("{Y}", "%04d".format(s.year)).replace("{m}", "%02d".format(s.monthValue)).replace("{d}", "%02d".format(s.dayOfMonth))
            .replace("{H}", "%02d".format(s.hour)).replace("{M}", "%02d".format(s.minute)).replace("{S}", "%02d".format(s.second))
    }
}
