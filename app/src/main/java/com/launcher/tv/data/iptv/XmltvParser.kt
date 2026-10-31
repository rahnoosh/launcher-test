package com.launcher.tv.data.iptv

import java.io.Reader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal streaming XMLTV reader. Guide files can be 100+ MB, so it scans for <channel> and
 * <programme> blocks in a rolling buffer and keeps only programmes inside [from, to].
 */
object XmltvParser {
    data class Result(
        val programmes: Map<String, List<EpgEntry>>,
        /** Normalized display name -> channel id, for playlists without tvg-id. */
        val names: Map<String, String>,
    )

    private val attrRx = Regex("""([a-zA-Z_-]+)="([^"]*)"""")
    private val titleRx = Regex("""<title[^>]*>([\s\S]*?)</title>""")
    private val subRx = Regex("""<sub-title[^>]*>([\s\S]*?)</sub-title>""")
    private val descRx = Regex("""<desc[^>]*>([\s\S]*?)</desc>""")
    private val nameRx = Regex("""<display-name[^>]*>([\s\S]*?)</display-name>""")
    private val withOffset = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val noOffset = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(reader: Reader, from: Long, to: Long, maxPerChannel: Int = 60): Result {
        val programmes = HashMap<String, MutableList<EpgEntry>>()
        val names = HashMap<String, String>()
        val sb = StringBuilder()
        val chunk = CharArray(64 * 1024)

        fun drain() {
            var pos = 0
            while (true) {
                val p = sb.indexOf("<programme", pos)
                val c = sb.indexOf("<channel", pos)
                val start = when {
                    p < 0 -> c
                    c < 0 -> p
                    else -> minOf(p, c)
                }
                if (start < 0) {
                    // Keep a short tail in case a tag is split across reads.
                    val keep = minOf(sb.length, 16)
                    sb.delete(0, sb.length - keep)
                    return
                }
                val isProgramme = start == p
                val end = sb.indexOf(if (isProgramme) "</programme>" else "</channel>", start)
                if (end < 0) {
                    sb.delete(0, start)
                    return
                }
                val stop = end + if (isProgramme) "</programme>".length else "</channel>".length
                val block = sb.substring(start, stop)
                if (isProgramme) programme(block, from, to)?.let { (ch, e) ->
                    val list = programmes.getOrPut(ch) { mutableListOf() }
                    if (list.size < maxPerChannel) list += e
                } else channel(block)?.let { (id, displayNames) ->
                    displayNames.forEach { n -> names.putIfAbsent(Names.tokens(n).joinToString(" "), id) }
                }
                pos = stop
            }
        }

        while (true) {
            val n = reader.read(chunk)
            if (n < 0) break
            sb.append(chunk, 0, n)
            drain()
        }
        drain()
        return Result(programmes.mapValues { (_, v) -> v.sortedBy { it.startMillis } }, names)
    }

    private fun programme(block: String, from: Long, to: Long): Pair<String, EpgEntry>? {
        val head = block.substringBefore('>')
        val a = attrRx.findAll(head).associate { it.groupValues[1] to it.groupValues[2] }
        val channel = a["channel"] ?: return null
        val start = time(a["start"]) ?: return null
        val stop = time(a["stop"]) ?: (start + 3600_000L)
        if (stop < from || start > to) return null
        val title = titleRx.find(block)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        val sub = subRx.find(block)?.groupValues?.get(1)?.let(::unescape)
        val desc = descRx.find(block)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        return channel to EpgEntry(if (sub.isNullOrBlank()) title else "$title: $sub", desc.take(300), start, stop)
    }

    private fun channel(block: String): Pair<String, List<String>>? {
        val head = block.substringBefore('>')
        val id = attrRx.findAll(head).firstOrNull { it.groupValues[1] == "id" }?.groupValues?.get(2) ?: return null
        return id to nameRx.findAll(block).map { unescape(it.groupValues[1]) }.toList()
    }

    fun time(s: String?): Long? {
        val t = s?.trim() ?: return null
        return runCatching { OffsetDateTime.parse(t, withOffset).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t.take(14), noOffset).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    private val entity = Regex("""&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);""")
    fun unescape(s: String): String = entity.replace(s.replace("<![CDATA[", "").replace("]]>", "")) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> runCatching {
                val code = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                String(Character.toChars(code))
            }.getOrDefault(m.value)
        }
    }.trim()
}
